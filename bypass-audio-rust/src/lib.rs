use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::thread;
use std::time::Duration;
use jni::JNIEnv;
use jni::objects::{JClass, JByteArray};
use jni::sys::{jboolean, jint, jstring};

// Log integration with Android Logcat
use android_logger::Config;
use log::LevelFilter;

// --- IOCTL Helpers ---
// Linux ioctl encoding macros calculated dynamically at runtime
const IOC_NRSHIFT: u32 = 0;
const IOC_TYPESHIFT: u32 = 8;
const IOC_SIZESHIFT: u32 = 16;
const IOC_DIRSHIFT: u32 = 30;

const IOC_NONE: u32 = 0;
const IOC_WRITE: u32 = 1;
const IOC_READ: u32 = 2;

fn _ioc(dir: u32, type_: u8, nr: u8, size: u32) -> libc::c_ulong {
    (((dir) << IOC_DIRSHIFT) |
     ((type_ as u32) << IOC_TYPESHIFT) |
     ((nr as u32) << IOC_NRSHIFT) |
     ((size) << IOC_SIZESHIFT)) as libc::c_ulong
}

fn _iow(type_: u8, nr: u8, size: u32) -> libc::c_ulong {
    _ioc(IOC_WRITE, type_, nr, size)
}

fn _ior(type_: u8, nr: u8, size: u32) -> libc::c_ulong {
    _ioc(IOC_READ, type_, nr, size)
}

fn _io(type_: u8, nr: u8) -> libc::c_ulong {
    _ioc(IOC_NONE, type_, nr, 0)
}

// --- USB Structures ---
#[derive(Copy, Clone)]
#[repr(C)]
struct UsbdevfsClaiminterface {
    interface: libc::c_uint,
}

#[derive(Copy, Clone)]
#[repr(C)]
struct UsbdevfsSetinterface {
    interface: libc::c_uint,
    altsetting: libc::c_uint,
}

#[derive(Copy, Clone)]
#[repr(C)]
struct UsbdevfsIsoPacketDesc {
    length: libc::c_uint,
    actual_length: libc::c_uint,
    status: libc::c_uint,
}

#[derive(Copy, Clone)]
#[repr(C)]
struct UsbdevfsUrbHeader {
    urb_type: libc::c_uchar,
    endpoint: libc::c_uchar,
    status: libc::c_int,
    flags: libc::c_uint,
    buffer: *mut libc::c_void,
    buffer_length: libc::c_int,
    actual_length: libc::c_int,
    start_frame: libc::c_int,
    number_of_packets: libc::c_int,
    error_count: libc::c_int,
    signr: libc::c_uint,
    usercontext: *mut libc::c_void,
}

#[derive(Copy, Clone)]
#[repr(C)]
struct IsoUrb<const N: usize> {
    header: UsbdevfsUrbHeader,
    packets: [UsbdevfsIsoPacketDesc; N],
}

// --- Ring Buffer for PCM Audio ---
struct AudioRingBuffer {
    data: Vec<u8>,
    write_pos: usize,
    read_pos: usize,
    capacity: usize,
}

impl AudioRingBuffer {
    fn new(capacity: usize) -> Self {
        Self {
            data: vec![0; capacity],
            write_pos: 0,
            read_pos: 0,
            capacity,
        }
    }

    fn write(&mut self, bytes: &[u8]) -> usize {
        let mut written = 0;
        for &byte in bytes {
            let next_pos = (self.write_pos + 1) % self.capacity;
            if next_pos == self.read_pos {
                // Buffer full: drop older samples to maintain real-time constraints
                self.read_pos = (self.read_pos + 1) % self.capacity;
            }
            self.data[self.write_pos] = byte;
            self.write_pos = next_pos;
            written += 1;
        }
        written
    }

    fn read(&mut self, out: &mut [u8]) -> usize {
        let mut read = 0;
        for i in 0..out.len() {
            if self.read_pos == self.write_pos {
                break; // Buffer empty
            }
            out[i] = self.data[self.read_pos];
            self.read_pos = (self.read_pos + 1) % self.capacity;
            read += 1;
        }
        read
    }
}

// --- Global States ---
static RING_BUFFER: Mutex<Option<AudioRingBuffer>> = Mutex::new(None);
static RUNNING: AtomicBool = AtomicBool::new(false);
static mut WORKER_THREAD: Option<thread::JoinHandle<()>> = None;

// --- Telemetry Diagnostics ---
static URBS_SUBMIT_SUCCESS: AtomicU32 = AtomicU32::new(0);
static URBS_SUBMIT_FAILED: AtomicU32 = AtomicU32::new(0);
static LAST_KERNEL_ERRNO: AtomicI32 = AtomicI32::new(0);
static TOTAL_BYTES_REAPED: AtomicU64 = AtomicU64::new(0);
static TOTAL_PACKETS_REAPED: AtomicU64 = AtomicU64::new(0);

// --- Worker Thread Driver ---
fn usb_thread_fn(
    fd: i32,
    interface_index: u32,
    endpoint_address: u8,
    sample_rate: u32,
    channels: u32,
    max_packet_size: u32,
    alt_setting: u32,
    endpoint_type: u32,
) {
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Debug)
            .with_tag("BypassAudioRust"),
    );

    let is_bulk = endpoint_type == 2;

    log::info!(
        "Rust USB thread started. fd={}, interface={}, endpoint=0x{:02X}, rate={}, channels={}, maxPacket={}, altSetting={}, isBulk={}",
        fd, interface_index, endpoint_address, sample_rate, channels, max_packet_size, alt_setting, is_bulk
    );

    // 1. Claim USB Interface (USBDEVFS_CLAIMINTERFACE: _IOR('U', 15, unsigned int))
    let claim = UsbdevfsClaiminterface { interface: interface_index };
    let claim_req = _ior(b'U', 15, std::mem::size_of::<UsbdevfsClaiminterface>() as u32);
    unsafe {
        let res = libc::ioctl(fd, claim_req as _, &claim);
        if res < 0 {
            log::warn!("Claim interface failed with code {}, it might be already claimed by Kotlin", res);
        } else {
            log::info!("Claim interface successful");
        }
    }

    // 2. Set Alternate Setting (only if alt_setting > 0, to avoid stalling simple wireless dongles that only have altSetting 0)
    if alt_setting > 0 {
        let set_intf = UsbdevfsSetinterface {
            interface: interface_index,
            altsetting: alt_setting,
        };
        let set_intf_req = _ior(b'U', 4, std::mem::size_of::<UsbdevfsSetinterface>() as u32);
        unsafe {
            let res = libc::ioctl(fd, set_intf_req as _, &set_intf);
            if res < 0 {
                let err = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                log::warn!("Set alternate interface setting {} failed with code {} (errno={}), continuing", alt_setting, res, err);
            } else {
                log::info!("Set alternate interface setting {} successful", alt_setting);
            }
        }
    } else {
        log::info!("Endpoint is on default alternate setting 0; skipping SET_INTERFACE for wireless dongle compatibility.");
    }

    // 3. Determine packet sizes
    // For isochronous transfers, packet length CANNOT exceed max_packet_size, otherwise Linux kernel rejects URB with -EINVAL!
    let packet_size = if max_packet_size > 0 {
        max_packet_size as usize
    } else {
        192
    };
    log::info!("Calculated packet size: {} bytes (max_packet_size={})", packet_size, max_packet_size);

    // 4. Setup asynchronous URBs (USB Request Blocks)
    const ISO_PACKETS_PER_URB: usize = 8; // 8ms of buffer per URB
    const NUM_URBS: usize = 4; // Double/quadruple buffering (32ms total)
    
    let buffer_size = if is_bulk {
        std::cmp::max(packet_size * 16, 2048)
    } else {
        packet_size * ISO_PACKETS_PER_URB
    };

    let mut buffers: Vec<Vec<u8>> = (0..NUM_URBS)
        .map(|_| vec![0u8; buffer_size])
        .collect();

    let urb_type = if is_bulk { 3u8 } else { 0u8 }; // 3 = BULK, 0 = ISO
    let urb_flags = if is_bulk { 0u32 } else { 2u32 }; // 2 = ISO_ASAP
    let num_packets = if is_bulk { 0i32 } else { ISO_PACKETS_PER_URB as libc::c_int };

    let mut urbs: Vec<Box<IsoUrb<ISO_PACKETS_PER_URB>>> = (0..NUM_URBS)
        .map(|i| {
            let mut packets = [UsbdevfsIsoPacketDesc { length: 0, actual_length: 0, status: 0 }; ISO_PACKETS_PER_URB];
            if !is_bulk {
                for p in 0..ISO_PACKETS_PER_URB {
                    packets[p].length = packet_size as libc::c_uint;
                }
            }

            Box::new(IsoUrb {
                header: UsbdevfsUrbHeader {
                    urb_type,
                    endpoint: endpoint_address,
                    status: 0,
                    flags: urb_flags,
                    buffer: buffers[i].as_mut_ptr() as *mut libc::c_void,
                    buffer_length: buffer_size as libc::c_int,
                    actual_length: 0,
                    start_frame: 0,
                    number_of_packets: num_packets,
                    error_count: 0,
                    signr: 0,
                    usercontext: i as *mut libc::c_void,
                },
                packets,
            })
        })
        .collect();

    // Submit all URBs to populate the kernel queue.
    // USBDEVFS_SUBMITURB: _IOR('U', 10, struct usbdevfs_urb). Kernel expects header size in ioctl number.
    let submit_req = _ior(b'U', 10, std::mem::size_of::<UsbdevfsUrbHeader>() as u32);
    for i in 0..NUM_URBS {
        unsafe {
            let urb_ptr = &*urbs[i] as *const IsoUrb<ISO_PACKETS_PER_URB> as *mut libc::c_void;
            let res = libc::ioctl(fd, submit_req as _, urb_ptr);
            if res < 0 {
                let err = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                LAST_KERNEL_ERRNO.store(err, Ordering::Relaxed);
                URBS_SUBMIT_FAILED.fetch_add(1, Ordering::Relaxed);
                log::error!("Failed to submit initial URB {}: errno={}", i, err);
            } else {
                URBS_SUBMIT_SUCCESS.fetch_add(1, Ordering::Relaxed);
                log::debug!("Submitted initial URB {}", i);
            }
        }
    }

    // USBDEVFS_REAPURBNDELAY: _IOW('U', 13, void *) - allows responsive exit when RUNNING becomes false
    let reap_nodelay_req = _iow(b'U', 13, std::mem::size_of::<*mut libc::c_void>() as u32);
    log::info!("USB capture loop started successfully");

    // Main real-time reap & submit loop
    while RUNNING.load(Ordering::Relaxed) {
        let mut reaped_ptr: *mut libc::c_void = std::ptr::null_mut();
        unsafe {
            // Non-blocking reap call so the thread immediately observes STOP without hanging
            let res = libc::ioctl(fd, reap_nodelay_req as _, &mut reaped_ptr);
            if res < 0 {
                let err = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                if err == libc::EAGAIN || err == 11 {
                    // No URB ready yet; brief sleep and re-check RUNNING flag
                    thread::sleep(Duration::from_millis(1));
                    continue;
                }
                if err == libc::EINTR {
                    continue;
                }
                log::error!("Reap URB error: errno={}", err);
                thread::sleep(Duration::from_millis(2));
                continue;
            }
        }

        if reaped_ptr.is_null() {
            continue;
        }

        let urb_index = unsafe {
            let header_ptr = reaped_ptr as *const UsbdevfsUrbHeader;
            (*header_ptr).usercontext as usize
        };

        if urb_index >= NUM_URBS {
            log::error!("Reaped URB index out of bounds: {}", urb_index);
            continue;
        }

        // Copy audio data from the completed URB into the Ring Buffer
        let urb = &urbs[urb_index];
        let buffer = &buffers[urb_index];

        if is_bulk {
            let actual_len = unsafe {
                let header_ptr = reaped_ptr as *const UsbdevfsUrbHeader;
                (*header_ptr).actual_length as usize
            };
            if actual_len > 0 && actual_len <= buffer.len() {
                TOTAL_BYTES_REAPED.fetch_add(actual_len as u64, Ordering::Relaxed);
                TOTAL_PACKETS_REAPED.fetch_add(1, Ordering::Relaxed);
                let packet_data = &buffer[0..actual_len];
                if let Ok(mut guard) = RING_BUFFER.lock() {
                    if let Some(ref mut rb) = *guard {
                        rb.write(packet_data);
                    }
                }
            }
        } else {
            for p in 0..ISO_PACKETS_PER_URB {
                let packet = &urb.packets[p];
                if packet.status == 0 && packet.actual_length > 0 {
                    let offset = p * packet_size;
                    let actual_len = packet.actual_length as usize;
                    
                    if offset + actual_len <= buffer.len() {
                        TOTAL_BYTES_REAPED.fetch_add(actual_len as u64, Ordering::Relaxed);
                        TOTAL_PACKETS_REAPED.fetch_add(1, Ordering::Relaxed);
                        let packet_data = &buffer[offset..(offset + actual_len)];
                        if let Ok(mut guard) = RING_BUFFER.lock() {
                            if let Some(ref mut rb) = *guard {
                                rb.write(packet_data);
                            }
                        }
                    }
                }
            }
        }

        // Resubmit the reaped URB
        unsafe {
            let urb_mut_ptr = &mut *urbs[urb_index] as *mut IsoUrb<ISO_PACKETS_PER_URB>;
            (*urb_mut_ptr).header.status = 0;
            (*urb_mut_ptr).header.actual_length = 0;
            (*urb_mut_ptr).header.error_count = 0;
            if !is_bulk {
                for p in 0..ISO_PACKETS_PER_URB {
                    (*urb_mut_ptr).packets[p].length = packet_size as libc::c_uint;
                    (*urb_mut_ptr).packets[p].actual_length = 0;
                    (*urb_mut_ptr).packets[p].status = 0;
                }
            }
            
            let res = libc::ioctl(fd, submit_req as _, urb_mut_ptr as *mut libc::c_void);
            if res < 0 {
                let err = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                log::error!("Failed to resubmit URB {}: errno={}", urb_index, err);
            }
        }
    }

    log::info!("USB capture loop exiting. Discarding URBs and resetting hardware to alternate setting 0...");
    // 1. USBDEVFS_DISCARDURB: _IO('U', 11) to discard pending in-flight URBs
    let discard_req = _io(b'U', 11);
    for i in 0..NUM_URBS {
        unsafe {
            let urb_ptr = &*urbs[i] as *const IsoUrb<ISO_PACKETS_PER_URB> as *mut libc::c_void;
            let _ = libc::ioctl(fd, discard_req as _, urb_ptr);
        }
    }

    // 2. Set interface back to Alternate Setting 0 only if alt_setting was > 0
    if alt_setting > 0 {
        let set_zero = UsbdevfsSetinterface {
            interface: interface_index,
            altsetting: 0,
        };
        let set_intf_req = _ior(b'U', 4, std::mem::size_of::<UsbdevfsSetinterface>() as u32);
        unsafe {
            let res = libc::ioctl(fd, set_intf_req as _, &set_zero);
            if res < 0 {
                log::warn!("Set alternate setting 0 (standby) failed with code {}", res);
            } else {
                log::info!("Set alternate setting 0 (standby) successful. Microphone stopped transmitting.");
            }
        }
    }

    // 3. Release USB interface
    let release_req = _ior(b'U', 16, std::mem::size_of::<UsbdevfsClaiminterface>() as u32);
    unsafe {
        let res = libc::ioctl(fd, release_req as _, &claim);
        if res < 0 {
            log::warn!("Release interface failed with code {}", res);
        } else {
            log::info!("Release interface successful");
        }
    }
}

// --- JNI Bindings ---

#[no_mangle]
pub extern "system" fn Java_com_bypasscam_app_usb_BypassAudioJni_startAudioStream(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    interface_index: jint,
    endpoint_address: jint,
    sample_rate: jint,
    channels: jint,
    max_packet_size: jint,
    alt_setting: jint,
    endpoint_type: jint,
) -> jboolean {
    if RUNNING.load(Ordering::SeqCst) {
        return jni::sys::JNI_TRUE;
    }

    // Reset telemetry diagnostics
    URBS_SUBMIT_SUCCESS.store(0, Ordering::Relaxed);
    URBS_SUBMIT_FAILED.store(0, Ordering::Relaxed);
    LAST_KERNEL_ERRNO.store(0, Ordering::Relaxed);
    TOTAL_BYTES_REAPED.store(0, Ordering::Relaxed);
    TOTAL_PACKETS_REAPED.store(0, Ordering::Relaxed);

    // Allocate 512KB for the ring buffer
    if let Ok(mut guard) = RING_BUFFER.lock() {
        *guard = Some(AudioRingBuffer::new(512 * 1024));
    }

    RUNNING.store(true, Ordering::SeqCst);

    unsafe {
        WORKER_THREAD = Some(thread::spawn(move || {
            usb_thread_fn(
                fd as i32,
                interface_index as u32,
                endpoint_address as u8,
                sample_rate as u32,
                channels as u32,
                max_packet_size as u32,
                alt_setting as u32,
                endpoint_type as u32,
            );
        }));
    }

    jni::sys::JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_bypasscam_app_usb_BypassAudioJni_stopAudioStream(
    _env: JNIEnv,
    _class: JClass,
) {
    if !RUNNING.load(Ordering::SeqCst) {
        return;
    }

    RUNNING.store(false, Ordering::SeqCst);

    unsafe {
        if let Some(handle) = WORKER_THREAD.take() {
            let _ = handle.join();
        }
    }

    if let Ok(mut guard) = RING_BUFFER.lock() {
        *guard = None;
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bypasscam_app_usb_BypassAudioJni_readAudioData(
    mut env: JNIEnv,
    _class: JClass,
    buffer: JByteArray,
    size_in_bytes: jint,
) -> jint {
    if !RUNNING.load(Ordering::Relaxed) {
        return 0;
    }

    let mut temp_buf = vec![0u8; size_in_bytes as usize];
    let read_bytes = if let Ok(mut guard) = RING_BUFFER.lock() {
        if let Some(ref mut rb) = *guard {
            rb.read(&mut temp_buf)
        } else {
            0
        }
    } else {
        0
    };

    if read_bytes > 0 {
        // Copy Rust buffer data into Kotlin JByteArray
        let i8_slice: &[i8] = unsafe {
            std::slice::from_raw_parts(temp_buf.as_ptr() as *const i8, read_bytes)
        };
        let res = env.set_byte_array_region(&buffer, 0, i8_slice);
        if res.is_err() {
            return 0;
        }
    }

    read_bytes as jint
}

#[no_mangle]
pub extern "system" fn Java_com_bypasscam_app_usb_BypassAudioJni_getDriverStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let status_str = format!(
        "urbs_ok={}, urbs_err={}, last_errno={}, bytes_reaped={}, pkts_reaped={}",
        URBS_SUBMIT_SUCCESS.load(Ordering::Relaxed),
        URBS_SUBMIT_FAILED.load(Ordering::Relaxed),
        LAST_KERNEL_ERRNO.load(Ordering::Relaxed),
        TOTAL_BYTES_REAPED.load(Ordering::Relaxed),
        TOTAL_PACKETS_REAPED.load(Ordering::Relaxed),
    );
    env.new_string(status_str).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

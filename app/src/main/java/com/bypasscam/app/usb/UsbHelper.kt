package com.bypasscam.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Helper class to manage USB Audio device connections.
 * This class detects when a USB_CLASS_AUDIO device is connected,
 * claims its interface, and starts the Rust user-space driver.
 */
class UsbHelper(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var targetEndpoint: UsbEndpoint? = null
    
    private val _isUsbMicConnected = MutableStateFlow(false)
    val isUsbMicConnected: StateFlow<Boolean> = _isUsbMicConnected

    val usbDeviceInfo = MutableStateFlow("USB Mic: Disconnected")
    var hardwareAuditText: String = ""
        private set

    private val actionUsbPermission = "com.bypasscam.app.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.apply {
                                checkAndSetUsbAudioDevice(this)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { requestUsbPermission(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (isAudioDevice(it)) {
                            stopUsbAudioDriver()
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(actionUsbPermission)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        
        checkForConnectedDevices()
    }

    private fun checkForConnectedDevices() {
        usbManager.deviceList.values.forEach { device ->
            if (isAudioDevice(device)) {
                requestUsbPermission(device)
            }
        }
    }

    fun handleUsbDeviceAttached(device: UsbDevice) {
        requestUsbPermission(device)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (!isAudioDevice(device)) return
        
        if (!usbManager.hasPermission(device)) {
            val intent = Intent(actionUsbPermission).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            checkAndSetUsbAudioDevice(device)
        }
    }

    private val _audioChannels = MutableStateFlow(1)
    val audioChannelsFlow: StateFlow<Int> = _audioChannels
    val audioChannels: Int get() = _audioChannels.value

    private val _audioSampleRate = MutableStateFlow(48000)
    val audioSampleRateFlow: StateFlow<Int> = _audioSampleRate
    val audioSampleRate: Int get() = _audioSampleRate.value

    private fun checkAndSetUsbAudioDevice(device: UsbDevice) {
        if (isAudioDevice(device)) {
            val details = findAudioInputDetails(device)
            if (details != null) {
                val (usbInterface, endpoint) = details
                try {
                    val connection = usbManager.openDevice(device)
                    if (connection != null) {
                        if (connection.claimInterface(usbInterface, true)) {
                            usbDevice = device
                            usbConnection = connection
                            claimedInterface = usbInterface
                            targetEndpoint = endpoint

                            val maxPacketSize = endpoint.maxPacketSize
                            val resolvedChannels = if (maxPacketSize >= 192) 2 else 1
                            val resolvedSampleRate = if (maxPacketSize == 32 || maxPacketSize == 64) 16000 else 48000

                            _audioChannels.value = resolvedChannels
                            _audioSampleRate.value = resolvedSampleRate

                            generateHardwareAudit(device)

                            val epTypeStr = if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "BULK" else "ISOC"
                            val infoStr = "Mic 0x${device.vendorId.toString(16)}:0x${device.productId.toString(16)} ($epTypeStr, ${maxPacketSize}B, alt${usbInterface.alternateSetting})"
                            usbDeviceInfo.value = infoStr

                            _isUsbMicConnected.value = true
                            Log.i("UsbHelper", "USB Audio Microphone ready: $infoStr [channels=$resolvedChannels, rate=$resolvedSampleRate] (waiting for REC)")
                        } else {
                            Log.e("UsbHelper", "Failed to claim USB interface ${usbInterface.id}")
                            connection.close()
                        }
                    } else {
                        Log.e("UsbHelper", "Failed to open USB device connection.")
                    }
                } catch (e: Exception) {
                    Log.e("UsbHelper", "Error establishing USB device connection", e)
                }
            } else {
                Log.w("UsbHelper", "No suitable audio input endpoint found on device.")
            }
        }
    }

    /**
     * Wakes up the USB microphone and starts audio streaming when recording begins.
     * Compatible with both standard wired UAC mics and generic 2.4GHz wireless dongles.
     */
    fun startMicStreaming(): Boolean {
        val connection = usbConnection ?: run {
            Log.e("UsbHelper", "Cannot start streaming: usbConnection is null")
            return false
        }
        val intf = claimedInterface ?: run {
            Log.e("UsbHelper", "Cannot start streaming: claimedInterface is null")
            return false
        }
        val endpoint = targetEndpoint ?: run {
            Log.e("UsbHelper", "Cannot start streaming: targetEndpoint is null")
            return false
        }

        return try {
            // Ensure interface is claimed
            connection.claimInterface(intf, true)

            // Only switch alternate setting if the endpoint is on an alternate setting > 0 (standard wired UAC).
            // For generic wireless dongles whose endpoint is on alternate setting 0, do NOT call setInterface!
            val altSetting = intf.alternateSetting
            if (altSetting > 0) {
                val setIntfSuccess = connection.setInterface(intf)
                Log.i("UsbHelper", "Activating wired UAC streaming (setInterface alt=$altSetting): $setIntfSuccess")
            } else {
                Log.i("UsbHelper", "Endpoint is on default altSetting 0; skipping setInterface for wireless receiver compatibility")
            }

            val fd = connection.fileDescriptor
            val started = BypassAudioJni.startAudioStream(
                fd,
                intf.id,
                endpoint.address,
                audioSampleRate,
                audioChannels,
                endpoint.maxPacketSize,
                altSetting,
                endpoint.type
            )
            Log.i("UsbHelper", "BypassAudioJni.startAudioStream result: $started (fd=$fd, endpoint=0x${endpoint.address.toString(16)}, pktSize=${endpoint.maxPacketSize})")
            started
        } catch (e: Exception) {
            Log.e("UsbHelper", "Error starting microphone streaming", e)
            false
        }
    }

    /**
     * Informs the USB microphone to stop transmitting audio.
     * Triggered when the user presses STOP.
     */
    fun stopMicStreaming() {
        Log.i("UsbHelper", "Stopping microphone streaming (REC stopped)...")
        // Stop native Rust driver thread and reap loop
        BypassAudioJni.stopAudioStream()

        // Only set back to standby if this was a wired microphone with alternate setting > 0
        val connection = usbConnection
        val device = usbDevice
        val intf = claimedInterface
        if (connection != null && device != null && intf != null && intf.alternateSetting > 0) {
            try {
                val zeroAlt = findInterfaceByAltSetting(device, intf.id, 0)
                if (zeroAlt != null) {
                    val res = connection.setInterface(zeroAlt)
                    Log.i("UsbHelper", "Wired mic returned to standby (altSetting=0): $res")
                }
            } catch (e: Exception) {
                Log.w("UsbHelper", "Error setting standby alternate setting: ${e.message}")
            }
        }
    }

    private fun findInterfaceByAltSetting(device: UsbDevice, interfaceId: Int, altSetting: Int): UsbInterface? {
        for (c in 0 until device.configurationCount) {
            val config = device.getConfiguration(c)
            for (i in 0 until config.interfaceCount) {
                val intf = config.getInterface(i)
                if (intf.id == interfaceId && intf.alternateSetting == altSetting) {
                    return intf
                }
            }
        }
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.id == interfaceId && intf.alternateSetting == altSetting) {
                return intf
            }
        }
        return null
    }

    /**
     * Universal search for audio streaming IN endpoint.
     * Prioritizes AudioStreaming subclasses for wired mics, but seamlessly falls back
     * to any Audio class interface with an IN endpoint for wireless 2.4GHz dongles.
     */
    private fun findAudioInputDetails(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        // Priority 1: AudioStreaming subclass (subclass 2) with active endpoints (standard wired UAC mics)
        for (c in 0 until device.configurationCount) {
            val config = device.getConfiguration(c)
            for (i in 0 until config.interfaceCount) {
                val intf = config.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO && intf.interfaceSubclass == 2) {
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        if (ep.direction == UsbConstants.USB_DIR_IN && (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                            Log.d("UsbHelper", "Priority 1: Found UAC streaming interface ${intf.id} alt=${intf.alternateSetting}")
                            return Pair(intf, ep)
                        }
                    }
                }
            }
        }

        // Priority 2: Any Audio class interface with IN endpoint (wireless 2.4GHz dongles)
        for (c in 0 until device.configurationCount) {
            val config = device.getConfiguration(c)
            for (i in 0 until config.interfaceCount) {
                val intf = config.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        if (ep.direction == UsbConstants.USB_DIR_IN && (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                            Log.d("UsbHelper", "Priority 2: Found audio class interface ${intf.id} alt=${intf.alternateSetting}")
                            return Pair(intf, ep)
                        }
                    }
                }
            }
        }

        // Priority 3: Fallback across top-level interfaces
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN && (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                    Log.d("UsbHelper", "Priority 3: Found fallback interface ${intf.id} alt=${intf.alternateSetting}")
                    return Pair(intf, ep)
                }
            }
        }
        return null
    }

    private fun generateHardwareAudit(device: UsbDevice) {
        hardwareAuditText = buildString {
            appendLine("Device: ${device.deviceName} (VendorID: 0x${device.vendorId.toString(16)}, ProductID: 0x${device.productId.toString(16)})")
            appendLine("Device Class: ${device.deviceClass}, Subclass: ${device.deviceSubclass}, Protocol: ${device.deviceProtocol}")
            appendLine("Resolved Audio Pipeline: Channels=$audioChannels, SampleRate=$audioSampleRate Hz")
            appendLine("Configurations: ${device.configurationCount}, Root Interfaces: ${device.interfaceCount}")
            for (c in 0 until device.configurationCount) {
                val config = device.getConfiguration(c)
                appendLine("Config $c (id=${config.id}, interfaces=${config.interfaceCount}):")
                for (i in 0 until config.interfaceCount) {
                    val intf = config.getInterface(i)
                    appendLine("  Interface ${intf.id} (alt=${intf.alternateSetting}, class=${intf.interfaceClass}, subClass=${intf.interfaceSubclass}, endpoints=${intf.endpointCount})")
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                        val type = when (ep.type) {
                            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                            UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                            else -> "CONTROL"
                        }
                        appendLine("    Endpoint $j: addr=0x${ep.address.toString(16)} $dir $type maxPkt=${ep.maxPacketSize}")
                    }
                }
            }
        }
        Log.i("UsbHelper", "Hardware Audit:\n$hardwareAuditText")
    }

    fun stopUsbAudioDriver() {
        stopMicStreaming()
        try {
            claimedInterface?.let {
                usbConnection?.releaseInterface(it)
            }
        } catch (e: Exception) {
            Log.w("UsbHelper", "Error releasing interface: ${e.message}")
        }
        try {
            usbConnection?.close()
        } catch (e: Exception) {
            Log.w("UsbHelper", "Error closing connection: ${e.message}")
        }
        usbConnection = null
        claimedInterface = null
        targetEndpoint = null
        usbDevice = null
        _isUsbMicConnected.value = false
        usbDeviceInfo.value = "USB Mic: Disconnected"
        Log.i("UsbHelper", "USB Audio Driver stopped and clean up complete.")
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    fun unregister() {
        stopUsbAudioDriver()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }
}

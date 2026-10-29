# ByPassCam

**ByPassCam** is a professional video recording solution for Android that bypasses hardware restrictions imposed by device manufacturers (such as Samsung and Motorola) that block generic external USB microphones.

This technical achievement is realized using a **user-space USB Audio Class (UAC) driver written in Rust**, integrated via **JNI (Java Native Interface)** and the **Android NDK**, completely bypassing the operating system's default audio routing pipeline.

---

## 1. How the Solution Works (Architecture)

On certain devices from manufacturers like Motorola and Samsung, the operating system's audio layer (Audio HAL / AudioFlinger) refuses to route audio from generic USB microphones to video recording apps.

To bypass this barrier, ByPassCam operates as follows:
1. **USB Detection and Opening:** The Kotlin component [UsbHelper](file:///home/kreivesler/Documentos/projects/bypasscam/bypasscam/app/src/main/java/com/bypasscam/app/usb/UsbHelper.kt) detects the USB microphone and requests USB communication permission from the user.
2. **Native File Descriptor:** Once permission is granted, the connection is opened, and the native File Descriptor (`fd`) of the Linux device is extracted and passed to Rust.
3. **Rust USB Driver (User-Space):** The native Rust code ([lib.rs](file:///home/kreivesler/Documentos/projects/bypasscam/bypasscam/bypass-audio-rust/src/lib.rs)) takes control of the file descriptor. Using low-level Linux `ioctl` system calls (`usbdevfs`), it claims the physical audio interface and schedules asynchronous, isochronous USB Request Block (URB) transfers.
4. **Buffering and Encoding:** The native driver decodes PCM audio samples and writes them to a thread-safe circular Ring Buffer. The [AudioEncoder](file:///home/kreivesler/Documentos/projects/bypasscam/bypasscam/app/src/main/java/com/bypasscam/app/usb/AudioEncoder.kt) class consumes this data and encodes it into AAC format (.mp4) using Android's hardware encoder (`MediaCodec`).
5. **Seamless In-Recording Camera Flip & Audio Sync:** Users can switch between rear and front cameras mid-recording by pressing `Flip`. Audio capture is frame-synchronized by pausing during the camera lens transition and resuming precisely when the next sensor begins streaming, eliminating audio drift and delay.
6. **Multi-Segment Concatenation, Orientation Normalization & Safe Save UI:** When the stop button (white square) is pressed, a modal overlay alerts the user not to close the app while the video is finalized. For single-shot recordings, fast packet-level multiplexing ([MuxerHelper](file:///home/kreivesler/Documentos/projects/bypasscam/bypasscam/app/src/main/java/com/bypasscam/app/usb/MuxerHelper.kt)) without re-encoding is performed. For multi-camera flip recordings (switching between front and rear cameras in portrait or landscape orientations), [TransformerHelper](file:///home/kreivesler/Documentos/projects/bypasscam/bypasscam/app/src/main/java/com/bypasscam/app/usb/TransformerHelper.kt) uses Jetpack Media3 to automatically normalize differences in hardware sensor orientation matrices (preventing upside-down facecam footage) and output a single, unified MP4 with continuous synchronized audio.

---

## 2. Build and Usage Instructions

### Prerequisites
* **Android SDK and NDK:** The Android Gradle Plugin will download NDK `26.1.10909125` automatically.
* **Rust compiler and targets:** Up-to-date Rustup. The following targets must be installed:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
* **cargo-ndk:** Helper utility to cross-compile to Android:
  ```bash
  cargo install cargo-ndk
  ```

### Compilation Steps
With the prerequisites installed, set the `JAVA_HOME` variable to the Android Studio JBR path and run the Gradle build:

```bash
# Setting the JDK (example using Android Studio Snap installation on Linux)
export JAVA_HOME=/snap/android-studio/current/jbr

# Compile the Rust library and build the final APK
./gradlew assembleDebug
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Installing on a Device
To install the compiled application on a physical device:
1. Transfer the generated `app-debug.apk` file to your Android phone (via USB, email, Google Drive, or ADB).
2. On the device, open a file manager and navigate to the transferred APK file.
3. Tap the APK file to install it. If prompted by Android security, enable the "Install from Unknown Sources" permission for your file manager.
4. Once installed, open the app, connect the generic USB microphone, and grant the USB access permission popup.

---

## 3. Support Baseline (Three Reference Devices)

To validate performance, latency, and compatibility under different hardware and operating system constraints, we use these three devices as testing benchmarks:

1. **Motorola Moto g04 (Android 14) — *Entry-Level Baseline***
   * **Test Goal:** Extreme performance validation. Since it has a low-end Unisoc T606 processor and limited memory, it ensures that isochronous transfers in Rust run without frame drops or stutters during HD video recording.
2. **Samsung Galaxy S24 Ultra (Android 14) — *Premium Baseline***
   * **Test Goal:** Restrictive OS policies validation. Samsung devices have strict custom policies in their Audio HAL (via Knox/AudioFlinger). Success on this device guarantees that the user-space USB driver bypass is immune to manufacturer-specific routing rules.
3. **Xiaomi Redmi Note 13 (Android 13 / HyperOS) — *Mid-Range Baseline***
   * **Test Goal:** Lifecycle and power management validation. HyperOS/MIUI aggressively terminates background processes. This tests if the Rust driver thread and `MediaCodec` encoder persist reliably during long recording sessions.

---

## 4. Known Limitations

* **Standard UAC Microphones:** The driver supports USB microphones complying with standard USB Audio Class (UAC 1.0 & 2.0). Custom proprietary protocols are not supported.
* **OTG Power Limits:** Professional condenser microphones may draw more power than the phone's USB-C port can supply. A powered USB hub solves this.
* **Audio Format:** The driver currently parses 16-bit Mono PCM at 48,000 Hz (standard for generic lapels and soundcards).
* **Absence of Pause Function during Recording:** The USB recording bypass currently only supports Start/Stop flow.
  * *How to implement/resolve:* Pause the recording in CameraX (`recording.pause()`), and in [AudioEncoder.kt](file:///home/kreive/Documentos/androidapp/bypasscam/app/src/main/java/com/bypasscam/app/usb/AudioEncoder.kt), pause the PCM buffer consumption. Upon resuming (`recording.resume()`), calculate the pause duration and subtract it from the `presentationTimeUs` sent to the `MediaCodec` to maintain perfect lip-sync.

---

## 5. License and Distribution

This project is licensed under the **MIT License** with a custom attribution clause.

### Distribution Terms
The code is free for commercial use, modification, and private or public distribution by individuals or companies under the following condition:

> [!IMPORTANT]
> **Attribution/Credit Requirement:**
> Any application, commercial product, library, or distribution that incorporates or derives from this software must include the copyright notice and credit attribution in its legal notices, open-source license screens, help files, or technical documentation. No visual banner or logo is required on the main user interface.
>
> **Suggested Credit Text:**
> *"USB audio capture technology by ByPassCam (kreive.com.br)"*

### Corporate Information
* **Provided by:** Kreive Tech
* **Official Website:** [kreive.com.br](https://kreive.com.br)
* **Headquarters:** Viçosa - MG, Brazil
* **Founder:** Kreivesler Riley
* **General License:** MIT License (see the `LICENSE` file for details).

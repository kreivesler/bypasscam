package com.bypasscam.app.usb

import android.util.Log

object BypassAudioJni {
    private const val TAG = "BypassAudioJni"

    init {
        try {
            System.loadLibrary("bypass_audio_rust")
            Log.d(TAG, "Successfully loaded native library bypass_audio_rust")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library bypass_audio_rust", e)
        }
    }

    /**
     * Starts the user-space USB Audio streaming driver in a background thread.
     * @param fileDescriptor The native file descriptor of the UsbDeviceConnection.
     * @param interfaceIndex The interface number containing the audio stream.
     * @param endpointAddress The endpoint address to read audio from.
     * @param sampleRate The sample rate of the audio (e.g. 48000, 44100).
     * @param channels The number of channels (1 = mono, 2 = stereo).
     * @return true if successful, false otherwise.
     */
    external fun startAudioStream(
        fileDescriptor: Int,
        interfaceIndex: Int,
        endpointAddress: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean

    /**
     * Stops the background USB streaming driver.
     */
    external fun stopAudioStream()

    /**
     * Reads available PCM audio bytes from the Rust ring-buffer.
     * @param buffer The byte array to write the read PCM data to.
     * @param sizeInBytes The maximum number of bytes to read.
     * @return The actual number of bytes read, or 0 if no data is available.
     */
    external fun readAudioData(buffer: ByteArray, sizeInBytes: Int): Int
}

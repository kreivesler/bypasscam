package com.bypasscam.app.usb

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encodes PCM audio received from JNI into AAC format in a background thread
 * and outputs it to an MP4 container file.
 * Also monitors and observes physical USB audio reception in real time.
 */
class AudioEncoder {
    private val TAG = "AudioEncoder"
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var isRunning = false
    private var encoderThread: Thread? = null

    /**
     * "Audio arriving" (áudio chegando) observation variable.
     * Set to true as soon as the first physical audio chunk is read from the USB device.
     */
    @Volatile
    var isAudioArriving: Boolean = false
        private set

    @Volatile
    var totalBytesRead: Long = 0L
        private set

    @Volatile
    var totalChunksRead: Long = 0L
        private set

    @Volatile
    var recordingStartTimeMs: Long = 0L
        private set

    @Volatile
    var recordingEndTimeMs: Long = 0L
        private set

    @Volatile
    var firstPacketTimeMs: Long = 0L
        private set

    @Volatile
    var lastPacketTimeMs: Long = 0L
        private set

    @Volatile
    var sampleRateConfigured: Int = 48000
        private set

    @Volatile
    var channelsConfigured: Int = 2 // Always 2-channel Stereo AAC for universal video player compatibility
        private set

    @Volatile
    var sourceChannelsConfigured: Int = 1 // Input channels from USB microphone
        private set

    @Volatile
    var totalAudioFramesEncoded: Long = 0L
        private set

    fun start(outputFile: File, sampleRate: Int, channels: Int) {
        if (isRunning) return
        isRunning = true
        muxerStarted = false
        audioTrackIndex = -1

        // Reset observation state
        isAudioArriving = false
        totalBytesRead = 0L
        totalChunksRead = 0L
        firstPacketTimeMs = 0L
        lastPacketTimeMs = 0L
        recordingStartTimeMs = System.currentTimeMillis()
        recordingEndTimeMs = 0L
        sampleRateConfigured = sampleRate
        sourceChannelsConfigured = channels
        channelsConfigured = 2
        totalAudioFramesEncoded = 0L

        encoderThread = Thread {
            Log.d(TAG, "AudioEncoder background thread started (sourceChannels=$channels, outChannels=2, rate=$sampleRate).")
            try {
                // Video containers (MP4) expect standard Stereo AAC tracks
                val outputChannels = 2
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, outputChannels)
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 128000) // 128 kbps stereo AAC
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 32)

                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }

                mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val bufferInfo = MediaCodec.BufferInfo()
                val pcmBuffer = ByteArray(2048)
                val stereoBuffer = ByteArray(4096)
                var lastObservationLogTime = System.currentTimeMillis()

                while (isRunning) {
                    val codec = mediaCodec ?: break

                    // Read PCM audio chunk from Rust ring buffer via JNI
                    val readBytes = BypassAudioJni.readAudioData(pcmBuffer, pcmBuffer.size)

                    if (readBytes > 0) {
                        // Mark "audio arriving" state variable as requested
                        if (!isAudioArriving) {
                            isAudioArriving = true
                            firstPacketTimeMs = System.currentTimeMillis()
                            Log.i(TAG, "Audio arriving confirmed (audio chegando = true)! First audio packet received.")
                        }
                        lastPacketTimeMs = System.currentTimeMillis()
                        totalBytesRead += readBytes
                        totalChunksRead++

                        // Prepare stereo PCM data
                        val frameCount: Int
                        val bytesToQueue: Int
                        val bufferToQueue: ByteArray

                        if (sourceChannelsConfigured == 1) {
                            // 16-bit Mono PCM (2 bytes per sample) -> Expand to 16-bit Stereo (4 bytes per frame)
                            val validMonoBytes = readBytes - (readBytes % 2)
                            val numSamples = validMonoBytes / 2
                            val neededStereoBytes = numSamples * 4

                            val targetBuffer = if (stereoBuffer.size >= neededStereoBytes) stereoBuffer else ByteArray(neededStereoBytes)

                            for (i in 0 until numSamples) {
                                val b0 = pcmBuffer[2 * i]
                                val b1 = pcmBuffer[2 * i + 1]
                                // Left channel
                                targetBuffer[4 * i] = b0
                                targetBuffer[4 * i + 1] = b1
                                // Right channel (duplicate for centered dual-mono stereo)
                                targetBuffer[4 * i + 2] = b0
                                targetBuffer[4 * i + 3] = b1
                            }

                            frameCount = numSamples
                            bytesToQueue = neededStereoBytes
                            bufferToQueue = targetBuffer
                        } else {
                            // Already stereo (4 bytes per frame: 2 bytes Left + 2 bytes Right)
                            val validStereoBytes = readBytes - (readBytes % 4)
                            frameCount = validStereoBytes / 4
                            bytesToQueue = validStereoBytes
                            bufferToQueue = pcmBuffer
                        }

                        if (frameCount > 0) {
                            val inputBufferIndex = codec.dequeueInputBuffer(10000)
                            if (inputBufferIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                                if (inputBuffer != null) {
                                    inputBuffer.clear()
                                    inputBuffer.put(bufferToQueue, 0, bytesToQueue)

                                    // Exact drift-free presentation timestamp calculated directly from total frames
                                    val presentationTimeUs = (totalAudioFramesEncoded * 1_000_000L) / sampleRate
                                    totalAudioFramesEncoded += frameCount

                                    codec.queueInputBuffer(inputBufferIndex, 0, bytesToQueue, presentationTimeUs, 0)
                                }
                            }
                        }
                    } else {
                        // Brief sleep when waiting for USB packets
                        Thread.sleep(10)
                    }

                    // Periodic observation logging
                    val now = System.currentTimeMillis()
                    if (now - lastObservationLogTime >= 2000) {
                        val durationSec = if (sampleRate > 0) totalAudioFramesEncoded.toDouble() / sampleRate else 0.0
                        Log.d(TAG, "Audio observation: arriving=$isAudioArriving, bytes=$totalBytesRead, frames=$totalAudioFramesEncoded (${"%.2f".format(Locale.US, durationSec)}s)")
                        lastObservationLogTime = now
                    }

                    // Drain encoded AAC buffers
                    drainEncoder(codec, bufferInfo, false)
                }

                val finalDurationSec = if (sampleRate > 0) totalAudioFramesEncoded.toDouble() / sampleRate else 0.0
                Log.i(TAG, "AudioEncoder finished. Total audio bytes read: $totalBytesRead, frames encoded: $totalAudioFramesEncoded (${"%.2f".format(Locale.US, finalDurationSec)}s)")
                if (totalBytesRead == 0L) {
                    Log.w(TAG, "AudioEncoder: 0 bytes of audio were received from USB driver during recording!")
                }

                // Finalize encoding by sending End Of Stream (EOS)
                mediaCodec?.let { codec ->
                    val finalPts = (totalAudioFramesEncoded * 1_000_000L) / sampleRate
                    for (attempt in 0..10) {
                        val inputBufferIndex = codec.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, finalPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            break
                        }
                        Thread.sleep(10)
                    }
                    drainEncoder(codec, bufferInfo, true)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception in AudioEncoder thread", e)
            } finally {
                cleanUp()
                Log.d(TAG, "AudioEncoder background thread finished.")
            }
        }
        encoderThread?.start()
    }

    private fun drainEncoder(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        val muxer = mediaMuxer ?: return

        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)

            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        Log.w(TAG, "Format changed twice, ignoring")
                    } else {
                        val newFormat = codec.outputFormat
                        audioTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "MediaMuxer started with audio track: $audioTrackIndex")
                    }
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && muxerStarted) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
                else -> break
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        recordingEndTimeMs = System.currentTimeMillis()
        isRunning = false
        try {
            encoderThread?.join(3000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error joining encoder thread", e)
        }
        encoderThread = null
    }

    /**
     * Generates a detailed diagnostic report string to be saved as a .txt file.
     */
    fun generateObservationReport(hardwareAudit: String = "", jniDriverStatus: String = ""): String {
        val endTime = if (recordingEndTimeMs > 0) recordingEndTimeMs else System.currentTimeMillis()
        val durationMs = if (endTime > recordingStartTimeMs) endTime - recordingStartTimeMs else 0L
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val startDateStr = if (recordingStartTimeMs > 0) dateFormat.format(Date(recordingStartTimeMs)) else "N/A"
        val endDateStr = if (endTime > 0) dateFormat.format(Date(endTime)) else "N/A"
        val durationSec = durationMs / 1000.0
        val kbps = if (durationMs > 0) (totalBytesRead * 8.0) / durationMs else 0.0
        val audioDurationSec = if (sampleRateConfigured > 0) totalAudioFramesEncoded.toDouble() / sampleRateConfigured else 0.0

        return buildString {
            appendLine("==================================================")
            appendLine("       BYPASSCAM USB AUDIO RECORDING REPORT       ")
            appendLine("==================================================")
            appendLine("Audio Arriving State (audio chegando): $isAudioArriving")
            appendLine("Recording Started:         $startDateStr")
            appendLine("Recording Ended:           $endDateStr")
            appendLine("Wall Clock Duration:       ${"%.2f".format(Locale.US, durationSec)} seconds")
            appendLine("Audio Stream Duration:     ${"%.2f".format(Locale.US, audioDurationSec)} seconds ($totalAudioFramesEncoded frames)")
            appendLine("--------------------------------------------------")
            appendLine("Input Microphone Format:   $sampleRateConfigured Hz ${if (sourceChannelsConfigured == 1) "Mono (Expanded to Stereo)" else "Stereo"}")
            appendLine("Output Container Format:   $sampleRateConfigured Hz Stereo AAC")
            appendLine("Total Audio Bytes Read:    $totalBytesRead bytes")
            appendLine("Total Audio Chunks Read:   $totalChunksRead")
            appendLine("Estimated Bitrate:         ${"%.2f".format(Locale.US, kbps)} kbps")
            if (firstPacketTimeMs > 0) {
                val latencyMs = firstPacketTimeMs - recordingStartTimeMs
                appendLine("First Audio Chunk Latency: ${latencyMs} ms")
            }
            appendLine("--------------------------------------------------")
            appendLine("Rust Kernel Driver Telemetry:")
            appendLine("  ${if (jniDriverStatus.isNotBlank()) jniDriverStatus else "N/A"}")
            appendLine("--------------------------------------------------")
            if (hardwareAudit.isNotBlank()) {
                appendLine("USB Hardware Topology & Descriptors:")
                appendLine(hardwareAudit.trim())
                appendLine("--------------------------------------------------")
            }
            if (totalBytesRead > 0L) {
                appendLine("FINAL STATUS: PHYSICAL USB AUDIO STREAM CAPTURED SUCCESSFULLY")
            } else {
                appendLine("FINAL STATUS: 0 BYTES RECEIVED FROM USB MICROPHONE")
                appendLine("DIAGNOSTIC HINTS:")
                appendLine("1. Check wireless microphone transmitter: verify power and that pairing LED is solidly lit.")
                appendLine("2. Check kernel errno above (e.g. 22=EINVAL, 1=EPERM, 19=ENODEV).")
                appendLine("3. Verify if microphone operates at a fixed sample rate (e.g. 16kHz vs 48kHz).")
            }
            appendLine("==================================================")
        }
    }

    private fun cleanUp() {
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            // Ignore
        }
        if (muxerStarted) {
            try {
                mediaMuxer?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping mediaMuxer: ${e.message}")
            }
        }
        try {
            mediaMuxer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaCodec = null
        mediaMuxer = null
        audioTrackIndex = -1
        muxerStarted = false
    }
}

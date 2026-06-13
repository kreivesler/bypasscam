package com.bypasscam.app.usb

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes PCM audio received from JNI into AAC format in a background thread
 * and outputs it to an MP4 container file.
 */
class AudioEncoder {
    private val TAG = "AudioEncoder"
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var isRunning = false
    private var encoderThread: Thread? = null

    fun start(outputFile: File, sampleRate: Int, channels: Int) {
        if (isRunning) return
        isRunning = true
        encoderThread = Thread {
            Log.d(TAG, "AudioEncoder background thread started.")
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 16)

                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mediaCodec?.start()

                mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val bufferInfo = MediaCodec.BufferInfo()
                // Buffer to read PCM chunks from Rust JNI
                val pcmBuffer = ByteArray(2048) 
                var presentationTimeUs = 0L

                while (isRunning) {
                    // Read available PCM bytes from Rust Ring Buffer via JNI
                    val readBytes = BypassAudioJni.readAudioData(pcmBuffer, pcmBuffer.size)
                    if (readBytes <= 0) {
                        // Sleep briefly if no audio data is available
                        Thread.sleep(10)
                        continue
                    }

                    val codec = mediaCodec ?: break
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(pcmBuffer, 0, readBytes)

                            // 16-bit PCM = 2 bytes per sample.
                            val samples = readBytes / (2 * channels)
                            val durationUs = (samples.toDouble() / sampleRate * 1000000).toLong()

                            codec.queueInputBuffer(inputBufferIndex, 0, readBytes, presentationTimeUs, 0)
                            presentationTimeUs += durationUs
                        }
                    }

                    // Drain the encoded AAC buffers
                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size > 0) {
                                val muxer = mediaMuxer
                                if (muxer != null) {
                                    if (audioTrackIndex == -1) {
                                        audioTrackIndex = muxer.addTrack(codec.outputFormat)
                                        muxer.start()
                                    }
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }
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

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            encoderThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error joining encoder thread", e)
        }
        encoderThread = null
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
        try {
            mediaMuxer?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            mediaMuxer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaCodec = null
        mediaMuxer = null
        audioTrackIndex = -1
    }
}

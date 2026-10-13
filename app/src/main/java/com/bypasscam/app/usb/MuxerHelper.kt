package com.bypasscam.app.usb

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object MuxerHelper {
    private const val TAG = "MuxerHelper"

    /**
     * Muxes a video-only file and an audio-only file together into a single MP4 container file.
     * This is a metadata copy operation and does not re-encode the audio or video frames.
     */
    fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        Log.d(TAG, "Starting muxing process. Video: ${videoFile.length()} bytes, Audio: ${audioFile.length()} bytes")
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var hasAudioSource = false
        
        try {
            if (!videoFile.exists() || videoFile.length() == 0L) {
                Log.e(TAG, "Video file does not exist or is empty")
                return false
            }

            videoExtractor.setDataSource(videoFile.absolutePath)

            if (audioFile.exists() && audioFile.length() > 0L) {
                try {
                    audioExtractor.setDataSource(audioFile.absolutePath)
                    hasAudioSource = true
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open audio file datasource: ${e.message}")
                }
            } else {
                Log.w(TAG, "Audio file is missing or 0 bytes, proceeding with video only")
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 1. Identify and add video track
            var videoTrackIndex = -1
            var videoSourceTrack = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i)
                    videoSourceTrack = i
                    videoTrackIndex = muxer.addTrack(format)

                    // Preserve video rotation / orientation hint from source video
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        val rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                        muxer.setOrientationHint(rotation)
                        Log.d(TAG, "Applied video orientation hint: $rotation degrees")
                    }

                    Log.d(TAG, "Video track identified at index $i, added to muxer.")
                    break
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "No video track found in source video file")
                return false
            }

            // 2. Identify and add audio track (if available)
            var audioTrackIndex = -1
            var audioSourceTrack = -1
            if (hasAudioSource) {
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioExtractor.selectTrack(i)
                        audioSourceTrack = i
                        audioTrackIndex = muxer.addTrack(format)
                        Log.d(TAG, "Audio track identified at index $i, added to muxer.")
                        break
                    }
                }
            }

            // Start the muxer
            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            // 3. Write Video samples
            if (videoTrackIndex != -1 && videoSourceTrack != -1) {
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        Log.d(TAG, "Finished reading video samples.")
                        break
                    }
                    bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                    bufferInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    videoExtractor.advance()
                }
            }

            // 4. Write Audio samples
            if (audioTrackIndex != -1 && audioSourceTrack != -1) {
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        Log.d(TAG, "Finished reading audio samples.")
                        break
                    }
                    bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    bufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }

            Log.i(TAG, "Successfully muxed video and audio into: ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mux video and audio tracks", e)
            return false
        } finally {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping muxer: ${e.message}")
                }
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                // Ignore
            }
            videoExtractor.release()
            if (hasAudioSource) {
                audioExtractor.release()
            }
        }
    }
}

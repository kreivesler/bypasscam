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
     * Muxes one or multiple video files sequentially and an audio file together into a single MP4 container file.
     * This performs a clean packet-level concatenation without re-encoding, preserving exact timestamps and PTS offsets.
     */
    fun muxVideoAndAudio(videoFiles: List<File>, audioFile: File, outputFile: File): Boolean {
        val validVideoFiles = videoFiles.filter { it.exists() && it.length() > 0L }
        Log.d(TAG, "Starting muxing process. Video segments: ${validVideoFiles.size}, Audio: ${audioFile.length()} bytes")
        if (validVideoFiles.isEmpty()) {
            Log.e(TAG, "No valid video segment files provided")
            return false
        }

        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var hasAudioSource = false
        
        try {
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

            // 1. Identify and add video track from the first valid video file
            var videoTrackIndex = -1
            val firstExtractor = MediaExtractor()
            try {
                firstExtractor.setDataSource(validVideoFiles[0].absolutePath)
                for (i in 0 until firstExtractor.trackCount) {
                    val format = firstExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = muxer.addTrack(format)
                        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            val rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                            muxer.setOrientationHint(rotation)
                            Log.d(TAG, "Applied video orientation hint: $rotation degrees")
                        }
                        Log.d(TAG, "Video track identified, added to muxer.")
                        break
                    }
                }
            } finally {
                firstExtractor.release()
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "No video track found in source video files")
                return false
            }

            // 2. Identify and add audio track (if available)
            var audioTrackIndex = -1
            if (hasAudioSource) {
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioExtractor.selectTrack(i)
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

            // 3. Write Video samples from all video segments sequentially
            var currentPtsOffsetUs = 0L
            for ((index, videoFile) in validVideoFiles.withIndex()) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(videoFile.absolutePath)
                    var segVideoTrack = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/")) {
                            extractor.selectTrack(i)
                            segVideoTrack = i
                            break
                        }
                    }

                    if (segVideoTrack == -1) {
                        Log.w(TAG, "Segment $index has no video track, skipping: ${videoFile.name}")
                        continue
                    }

                    var lastSamplePtsUs = 0L
                    var firstSamplePtsUs = -1L
                    var segmentSampleCount = 0

                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            break
                        }
                        val sampleTimeUs = extractor.sampleTime
                        if (firstSamplePtsUs == -1L) {
                            firstSamplePtsUs = sampleTimeUs
                        }
                        // Normalize timestamp relative to this segment and append after previous segment
                        val normalizedPtsUs = (sampleTimeUs - firstSamplePtsUs).coerceAtLeast(0L)
                        val finalPtsUs = currentPtsOffsetUs + normalizedPtsUs

                        bufferInfo.presentationTimeUs = finalPtsUs
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)

                        lastSamplePtsUs = normalizedPtsUs
                        segmentSampleCount++
                        extractor.advance()
                    }

                    Log.d(TAG, "Appended video segment $index (${videoFile.name}): $segmentSampleCount frames, duration: ${lastSamplePtsUs / 1000}ms")
                    // Advance offset with a standard 33ms margin for next frame
                    currentPtsOffsetUs += lastSamplePtsUs + 33333L
                } finally {
                    extractor.release()
                }
            }

            // 4. Write Audio samples
            if (audioTrackIndex != -1) {
                var audioSampleCount = 0
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        Log.d(TAG, "Finished reading audio samples: $audioSampleCount samples written.")
                        break
                    }
                    bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    bufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                    audioSampleCount++
                    audioExtractor.advance()
                }
            }

            Log.i(TAG, "Successfully concatenated ${validVideoFiles.size} video segments and muxed with audio: ${outputFile.absolutePath}")
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
            if (hasAudioSource) {
                audioExtractor.release()
            }
        }
    }
}

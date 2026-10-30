package com.bypasscam.app.usb

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
object TransformerHelper {
    private const val TAG = "TransformerHelper"

    /**
     * Extracts exact video track duration in microseconds from an MP4 file header.
     */
    private fun getVideoDurationUs(videoFile: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                    return format.getLong(MediaFormat.KEY_DURATION)
                }
            }
            0L
        } catch (e: Exception) {
            Log.w(TAG, "Error getting video duration: ${e.message}")
            0L
        } finally {
            extractor.release()
        }
    }

    /**
     * Merges multiple video segments (which may have differing rotations between front and rear cameras)
     * and combines them with an optional continuous audio file into a single output MP4.
     * Media3 Transformer automatically handles the rotation metadata of each clip, ensuring all clips
     * are normalized and oriented right side up.
     * Cuts out the flip transition gaps from the continuous audio to maintain exact lip sync across all flips.
     *
     * This method can be called from a background thread; it coordinates execution on the main Looper
     * and blocks synchronously until completion or timeout (60 seconds).
     */
    fun mergeSegmentsWithAudioSync(
        context: Context,
        videoFiles: List<File>,
        segmentGapsUs: List<Long> = emptyList(),
        audioFile: File?,
        outputFile: File
    ): Boolean {
        val validVideos = videoFiles.filter { it.exists() && it.length() > 0L }
        if (validVideos.isEmpty()) {
            Log.e(TAG, "No valid video files to merge")
            return false
        }

        val hasUsbAudio = audioFile != null && audioFile.exists() && audioFile.length() > 0L
        val latch = CountDownLatch(1)
        var isSuccess = false

        Handler(Looper.getMainLooper()).post {
            try {
                // 1. Build video sequence
                val videoItems = validVideos.map { file ->
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveAudio(hasUsbAudio) // Drop camera mic audio if using USB mic
                        .build()
                }
                val videoSequence = EditedMediaItemSequence.Builder(videoItems).build()

                // 2. Build composition (cutting out transition gaps from audio to match video takes)
                val composition = if (hasUsbAudio) {
                    val audioItems = mutableListOf<EditedMediaItem>()
                    var currentAudioStartUs = 0L

                    for ((index, videoFile) in validVideos.withIndex()) {
                        val videoDurationUs = getVideoDurationUs(videoFile)
                        val audioEndUs = currentAudioStartUs + videoDurationUs

                        Log.d(TAG, "Audio slice #$index: [${currentAudioStartUs / 1000}ms -> ${audioEndUs / 1000}ms] (video dur=${videoDurationUs / 1000}ms)")

                        val audioMediaItem = MediaItem.Builder()
                            .setUri(Uri.fromFile(audioFile))
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionUs(currentAudioStartUs)
                                    .setEndPositionUs(audioEndUs)
                                    .build()
                            )
                            .build()

                        audioItems.add(EditedMediaItem.Builder(audioMediaItem).build())

                        // Next audio slice starts after video duration + measured camera transition gap
                        val gapUs = if (index < segmentGapsUs.size) segmentGapsUs[index] else 0L
                        currentAudioStartUs = audioEndUs + gapUs
                        Log.d(TAG, "Skipping flip gap after segment #$index: ${gapUs / 1000}ms (next audio slice starts at ${currentAudioStartUs / 1000}ms)")
                    }

                    val audioSequence = EditedMediaItemSequence.Builder(audioItems).build()
                    Composition.Builder(listOf(videoSequence, audioSequence)).build()
                } else {
                    Composition.Builder(listOf(videoSequence)).build()
                }

                // 3. Configure Transformer
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            Log.d(TAG, "Transformer export completed successfully: ${outputFile.name} (${outputFile.length()} bytes)")
                            isSuccess = true
                            latch.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "Transformer export error: ${exportException.message}", exportException)
                            isSuccess = false
                            latch.countDown()
                        }
                    })
                    .build()

                if (outputFile.exists()) {
                    outputFile.delete()
                }

                Log.d(TAG, "Starting Transformer export with ${validVideos.size} segments (hasUsbAudio=$hasUsbAudio)...")
                transformer.start(composition, outputFile.absolutePath)
            } catch (e: Throwable) {
                Log.e(TAG, "Exception initiating Transformer export", e)
                isSuccess = false
                latch.countDown()
            }
        }

        try {
            val finished = latch.await(60, TimeUnit.SECONDS)
            if (!finished) {
                Log.e(TAG, "Transformer export timed out after 60 seconds")
                return false
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Transformer export interrupted", e)
            return false
        }

        return isSuccess && outputFile.exists() && outputFile.length() > 0L
    }

    fun mergeSegmentsWithAudioSync(
        context: Context,
        videoFiles: List<File>,
        audioFile: File?,
        outputFile: File
    ): Boolean {
        return mergeSegmentsWithAudioSync(context, videoFiles, emptyList(), audioFile, outputFile)
    }
}

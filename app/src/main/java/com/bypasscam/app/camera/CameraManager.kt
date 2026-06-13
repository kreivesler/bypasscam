package com.bypasscam.app.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bypasscam.app.usb.AudioEncoder
import com.bypasscam.app.usb.MuxerHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Helper class that sets up CameraX components (Preview, ImageCapture, VideoCapture).
 * Optimized to balance performance and avoid frame drops on devices like Moto g04.
 */
class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    
    // Single thread executor to ensure operations are sequential
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var isFrontCamera = false

    // USB Bypass variables
    private var isUsingUsbMicDuringRecording = false
    private val audioEncoder = AudioEncoder()
    private var tempVideoFile: File? = null
    private var tempAudioFile: File? = null
    private var tempMuxedFile: File? = null
    private var pendingContentValues: ContentValues? = null

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview configuration
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(surfaceProvider)
                }

            // ImageCapture configuration
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // VideoCapture configuration - Optimized for Moto g04
            // Using HD quality to provide a stable frame rate without overwhelming the hardware.
            val qualitySelector = QualitySelector.from(
                Quality.SD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setExecutor(ContextCompat.getMainExecutor(context))
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BypassCam")
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun toggleRecording(isUsbMicConnected: Boolean, onRecordingStateChanged: (Boolean) -> Unit) {
        val videoCapture = this.videoCapture ?: run {
            Log.e(TAG, "videoCapture está nulo!")
            return
        }

        val curRecording = recording
        if (curRecording != null) {
            Log.d(TAG, "Parando gravação ativa...")
            curRecording.stop()
            recording = null
            if (isUsingUsbMicDuringRecording) {
                audioEncoder.stop()
            }
            onRecordingStateChanged(false)
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BypassCam")
        }

        isUsingUsbMicDuringRecording = isUsbMicConnected
        pendingContentValues = contentValues

        val pendingRecording = if (isUsbMicConnected) {
            Log.d(TAG, "Iniciando gravação com bypass de driver USB genérico...")
            
            // Set up temp cache files
            val videoFile = File(context.cacheDir, "temp_video.mp4")
            if (videoFile.exists()) videoFile.delete()
            tempVideoFile = videoFile

            val audioFile = File(context.cacheDir, "temp_audio.mp4")
            if (audioFile.exists()) audioFile.delete()
            tempAudioFile = audioFile

            val muxedFile = File(context.cacheDir, "temp_muxed.mp4")
            if (muxedFile.exists()) muxedFile.delete()
            tempMuxedFile = muxedFile

            // Start recording audio from the Rust Ring Buffer via the encoder
            audioEncoder.start(audioFile, 48000, 1)

            // Prepare video-only recording
            val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()
            videoCapture.output.prepareRecording(context, fileOutputOptions)
        } else {
            Log.d(TAG, "Iniciando gravação padrão (microfone interno)...")
            val mediaStoreOutputOptions = MediaStoreOutputOptions
                .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build()
            videoCapture.output.prepareRecording(context, mediaStoreOutputOptions)
                .withAudioEnabled()
        }

        Log.d(TAG, "Botão pressionado. Iniciando processo de gravação...")

        recording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
            Log.d(TAG, "Event recebido: ${recordEvent::class.java.simpleName}")
            when(recordEvent) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "Gravação iniciada com sucesso no Recorder")
                    onRecordingStateChanged(true)
                }
                is VideoRecordEvent.Finalize -> {
                    onRecordingStateChanged(false)
                    if (isUsingUsbMicDuringRecording) {
                        audioEncoder.stop()
                    }

                    if (!recordEvent.hasError()) {
                        if (isUsingUsbMicDuringRecording) {
                            // Mux video and audio in a background thread
                            Thread {
                                val vFile = tempVideoFile
                                val aFile = tempAudioFile
                                val mFile = tempMuxedFile
                                val cValues = pendingContentValues

                                if (vFile != null && aFile != null && mFile != null && cValues != null) {
                                    val muxSuccess = MuxerHelper.muxVideoAndAudio(vFile, aFile, mFile)
                                    if (muxSuccess) {
                                        try {
                                            val finalUri = context.contentResolver.insert(
                                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                                cValues
                                            )
                                            if (finalUri != null) {
                                                context.contentResolver.openOutputStream(finalUri)?.use { out ->
                                                    mFile.inputStream().use { input ->
                                                        input.copyTo(out)
                                                    }
                                                }
                                                ContextCompat.getMainExecutor(context).execute {
                                                    Toast.makeText(context, "Video saved successfully!", Toast.LENGTH_SHORT).show()
                                                }
                                                Log.d(TAG, "Muxed video successfully saved to MediaStore: $finalUri")
                                            } else {
                                                Log.e(TAG, "Could not insert final video record into MediaStore.")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to copy muxed file to MediaStore", e)
                                        }
                                    } else {
                                        Log.e(TAG, "Failed to mux temporary video and audio files.")
                                    }
                                    // Clean up cache
                                    vFile.delete()
                                    aFile.delete()
                                    mFile.delete()
                                }
                            }.start()
                        } else {
                            val msg = "Video capture succeeded"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "Vídeo finalizado padrão: ${recordEvent.outputResults.outputUri}")
                        }
                    } else {
                        recording?.close()
                        recording = null
                        Log.e(TAG, "Erro na gravação: Código ${recordEvent.error} - Details: ${recordEvent.cause?.message}")
                        Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
                        
                        // Clean up temporary files on error
                        if (isUsingUsbMicDuringRecording) {
                            tempVideoFile?.delete()
                            tempAudioFile?.delete()
                            tempMuxedFile?.delete()
                        }
                    }
                }
            }
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraManager"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}

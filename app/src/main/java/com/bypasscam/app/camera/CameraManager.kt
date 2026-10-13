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
import com.bypasscam.app.usb.UsbHelper
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
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    // USB Bypass variables
    var usbHelper: UsbHelper? = null
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
        this.currentSurfaceProvider = surfaceProvider
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

            // VideoCapture configuration - Full HD (1080p) with automatic fallback
            val qualitySelector = QualitySelector.from(
                Quality.FHD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            )

            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor)
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            val targetSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val cameraSelector = if (cameraProvider.hasCamera(targetSelector)) {
                targetSelector
            } else {
                Log.w(TAG, "Selected camera lens is not available, falling back")
                isFrontCamera = !isFrontCamera
                if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                // Bind Preview, ImageCapture and VideoCapture simultaneously
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    videoCapture
                )
            } catch (exc: Exception) {
                Log.w(TAG, "Binding 3 use cases failed, falling back to preview and video: ${exc.message}")
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        videoCapture
                    )
                } catch (fallbackExc: Exception) {
                    Log.e(TAG, "Use case binding failed completely", fallbackExc)
                }
            }

        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Toggles between front and rear cameras safely.
     */
    fun flipCamera(lifecycleOwner: LifecycleOwner) {
        if (recording != null) {
            Log.w(TAG, "Cannot flip camera while recording is active")
            Toast.makeText(context, "Cannot switch camera during recording", Toast.LENGTH_SHORT).show()
            return
        }

        val surfaceProvider = currentSurfaceProvider ?: run {
            Log.e(TAG, "Surface provider not initialized yet")
            return
        }

        isFrontCamera = !isFrontCamera
        startCamera(lifecycleOwner, surfaceProvider)
    }

    /**
     * Captures a still photo and saves it to MediaStore (Pictures/BypassCam).
     */
    fun takePhoto() {
        val imageCapture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture use case is null")
            Toast.makeText(context, "Camera not ready for photo", Toast.LENGTH_SHORT).show()
            return
        }

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
                    val errorMsg = "Photo capture failed: ${exc.message}"
                    Log.e(TAG, errorMsg, exc)
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo saved to Pictures/BypassCam"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Photo capture succeeded: ${output.savedUri}")
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun toggleRecording(
        isUsbMicConnected: Boolean,
        channels: Int = 2,
        onRecordingStateChanged: (Boolean) -> Unit
    ) {
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
                usbHelper?.stopMicStreaming()
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
            
            // Wake up microphone hardware and start audio streaming now that REC is pressed
            usbHelper?.startMicStreaming()

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
            val sampleRate = usbHelper?.audioSampleRate ?: 48000
            val micChannels = usbHelper?.audioChannels ?: channels
            Log.i(TAG, "Starting AudioEncoder with dynamic sampleRate=$sampleRate, micChannels=$micChannels")
            audioEncoder.start(audioFile, sampleRate, micChannels)

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
                        usbHelper?.stopMicStreaming()
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
                                    val baseName = cValues.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: "BypassCam_${System.currentTimeMillis()}"

                                    // 1. Mux video and audio
                                    val muxSuccess = MuxerHelper.muxVideoAndAudio(vFile, aFile, mFile)
                                    val fileToSave = if (muxSuccess && mFile.exists() && mFile.length() > 0L) {
                                        mFile
                                    } else {
                                        Log.w(TAG, "Muxing was not successful or produced empty file. Falling back to video-only track.")
                                        vFile
                                    }

                                    var videoSavedSuccessfully = false
                                    try {
                                        val finalUri = context.contentResolver.insert(
                                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                            cValues
                                        )
                                        if (finalUri != null) {
                                            context.contentResolver.openOutputStream(finalUri)?.use { out ->
                                                fileToSave.inputStream().use { input ->
                                                    input.copyTo(out)
                                                }
                                            }
                                            videoSavedSuccessfully = true
                                            Log.d(TAG, "Video successfully saved to MediaStore: $finalUri")
                                        } else {
                                            Log.e(TAG, "Could not insert final video record into MediaStore.")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to copy video file to MediaStore", e)
                                    }

                                    // 2. Save standalone audio file separately in Movies/BypassCam
                                    // User requirement: save audio separately even though muxed, allowing direct audio inspection
                                    var audioSavedSuccessfully = false
                                    if (aFile.exists()) {
                                        try {
                                            val audioValues = ContentValues().apply {
                                                put(MediaStore.MediaColumns.DISPLAY_NAME, "${baseName}_audio.mp4")
                                                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BypassCam")
                                            }
                                            val audioUri = context.contentResolver.insert(
                                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                                audioValues
                                            )
                                            if (audioUri != null) {
                                                context.contentResolver.openOutputStream(audioUri)?.use { out ->
                                                    aFile.inputStream().use { input ->
                                                        input.copyTo(out)
                                                    }
                                                }
                                                audioSavedSuccessfully = true
                                                Log.d(TAG, "Standalone audio file successfully saved to MediaStore: $audioUri (${aFile.length()} bytes)")
                                            } else {
                                                Log.e(TAG, "Could not insert standalone audio record into MediaStore.")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to save standalone audio file to MediaStore", e)
                                        }
                                    }

                                    // 3. Audio observation report .txt generation
                                    // Generate diagnostic log with complete hardware audit and kernel telemetry
                                    val isAudioReceived = audioEncoder.isAudioArriving && audioEncoder.totalBytesRead > 0L
                                    var logSavedSuccessfully = false
                                    try {
                                        val audit = usbHelper?.hardwareAuditText ?: ""
                                        val jniStatus = try {
                                            com.bypasscam.app.usb.BypassAudioJni.getDriverStatus()
                                        } catch (e: Throwable) {
                                            "Error reading JNI status: ${e.message}"
                                        }
                                        val logContent = audioEncoder.generateObservationReport(audit, jniStatus)

                                        val logValues = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, "${baseName}_audio_log.txt")
                                            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/BypassCam")
                                        }
                                        val logUri = context.contentResolver.insert(
                                            MediaStore.Files.getContentUri("external"),
                                            logValues
                                        )
                                        if (logUri != null) {
                                            context.contentResolver.openOutputStream(logUri)?.use { out ->
                                                out.write(logContent.toByteArray(Charsets.UTF_8))
                                            }
                                            logSavedSuccessfully = true
                                            Log.d(TAG, "Audio observation diagnostic log saved to MediaStore: $logUri")
                                        } else {
                                            Log.e(TAG, "Could not insert audio log record into MediaStore.")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to save audio observation log to MediaStore", e)
                                    }

                                    // User notification Toast
                                    ContextCompat.getMainExecutor(context).execute {
                                        val feedbackMsg = when {
                                            videoSavedSuccessfully && isAudioReceived ->
                                                "Video, audio & log saved to Movies/BypassCam"
                                            videoSavedSuccessfully ->
                                                "Video saved (0B audio). Diagnostic log created!"
                                            else ->
                                                "Failed to save video to gallery"
                                        }
                                        Toast.makeText(context, feedbackMsg, Toast.LENGTH_LONG).show()
                                    }

                                    // Only delete temporary files when the definitive video was saved successfully
                                    if (videoSavedSuccessfully) {
                                        vFile.delete()
                                        aFile.delete()
                                        mFile.delete()
                                        Log.d(TAG, "Temporary cache files cleaned up after successful definitive save")
                                    } else {
                                        Log.w(TAG, "Definitive save failed; retaining temp cache files for recovery")
                                    }
                                }
                            }.start()
                        } else {
                            val msg = "Video saved to Movies/BypassCam"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "Standard video finalized: ${recordEvent.outputResults.outputUri}")
                        }
                    } else {
                        recording?.close()
                        recording = null
                        Log.e(TAG, "Recording error: Code ${recordEvent.error} - Details: ${recordEvent.cause?.message}")
                        Toast.makeText(context, "Recording failed: error ${recordEvent.error}", Toast.LENGTH_SHORT).show()
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

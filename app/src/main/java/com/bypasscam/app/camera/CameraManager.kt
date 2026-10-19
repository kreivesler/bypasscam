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
    private val tempVideoSegments = mutableListOf<File>()
    private var tempAudioFile: File? = null
    private var tempMuxedFile: File? = null
    private var pendingContentValues: ContentValues? = null
    
    // Multi-segment flip during recording variables
    private var isSessionRecording = false
    private var isFlippingDuringRecording = false
    private var currentChannels = 2
    private var onRecordingStateCallback: ((Boolean) -> Unit)? = null

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onReady: (() -> Unit)? = null
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

            onReady?.invoke()
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Toggles between front and rear cameras safely.
     * If recording is currently active, it smoothly transitions to the other camera lens
     * without stopping the overall recording session.
     */
    fun flipCamera(lifecycleOwner: LifecycleOwner) {
        val surfaceProvider = currentSurfaceProvider ?: run {
            Log.e(TAG, "Surface provider not initialized yet")
            return
        }

        if (isSessionRecording) {
            Log.d(TAG, "Flip requested during active recording. Performing seamless camera switch...")
            isFlippingDuringRecording = true
            // Stop current segment recording; finalize listener will switch camera lens and start next segment
            recording?.stop()
            recording = null
            
            isFrontCamera = !isFrontCamera
            startCamera(lifecycleOwner, surfaceProvider) {
                // Once new camera is bound, start next segment recording
                startNextVideoSegment()
            }
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

        if (isSessionRecording) {
            Log.d(TAG, "Parando sessão de gravação ativa...")
            isSessionRecording = false
            isFlippingDuringRecording = false
            recording?.stop()
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

        isSessionRecording = true
        isFlippingDuringRecording = false
        isUsingUsbMicDuringRecording = isUsbMicConnected
        pendingContentValues = contentValues
        currentChannels = channels
        onRecordingStateCallback = onRecordingStateChanged

        // Clear previous video segments
        tempVideoSegments.clear()

        // Set up temp cache files
        val audioFile = File(context.cacheDir, "temp_audio.mp4")
        if (audioFile.exists()) audioFile.delete()
        tempAudioFile = audioFile

        val muxedFile = File(context.cacheDir, "temp_muxed.mp4")
        if (muxedFile.exists()) muxedFile.delete()
        tempMuxedFile = muxedFile

        if (isUsbMicConnected) {
            Log.d(TAG, "Iniciando sessão com bypass de driver USB...")
            usbHelper?.startMicStreaming()
            val sampleRate = usbHelper?.audioSampleRate ?: 48000
            val micChannels = usbHelper?.audioChannels ?: channels
            Log.i(TAG, "Starting AudioEncoder with dynamic sampleRate=$sampleRate, micChannels=$micChannels")
            audioEncoder.start(audioFile, sampleRate, micChannels)
        } else {
            Log.d(TAG, "Iniciando sessão padrão com microfone interno...")
        }

        startNextVideoSegment()
    }

    /**
     * Starts a new video segment recording.
     */
    @SuppressLint("MissingPermission")
    private fun startNextVideoSegment() {
        if (!isSessionRecording) return

        val videoCapture = this.videoCapture ?: run {
            Log.e(TAG, "videoCapture nulo ao iniciar segmento!")
            return
        }

        val segmentIndex = tempVideoSegments.size
        val segmentFile = File(context.cacheDir, "temp_video_seg_$segmentIndex.mp4")
        if (segmentFile.exists()) segmentFile.delete()
        tempVideoSegments.add(segmentFile)

        val fileOutputOptions = FileOutputOptions.Builder(segmentFile).build()
        val pendingRecording = if (!isUsingUsbMicDuringRecording) {
            videoCapture.output.prepareRecording(context, fileOutputOptions).withAudioEnabled()
        } else {
            videoCapture.output.prepareRecording(context, fileOutputOptions)
        }

        Log.d(TAG, "Iniciando segmento de vídeo #$segmentIndex (${segmentFile.name})...")

        recording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "Segmento #$segmentIndex iniciado com sucesso")
                    isFlippingDuringRecording = false
                    onRecordingStateCallback?.invoke(true)
                }
                is VideoRecordEvent.Finalize -> {
                    Log.d(TAG, "Segmento #$segmentIndex finalizado (error: ${recordEvent.hasError()})")
                    if (isFlippingDuringRecording) {
                        Log.d(TAG, "Segmento finalizado devido a flip de câmera. Aguardando próximo segmento...")
                        return@start
                    }

                    if (!isSessionRecording) {
                        // User pressed STOP: finalize complete video
                        onRecordingStateCallback?.invoke(false)
                        if (isUsingUsbMicDuringRecording) {
                            audioEncoder.stop()
                            usbHelper?.stopMicStreaming()
                        }

                        if (!recordEvent.hasError()) {
                            finalizeAndSaveSession()
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
    }

    /**
     * Concatenates all recorded video segments and muxes with the audio track into a single definitive video.
     */
    private fun finalizeAndSaveSession() {
        Thread {
            val validSegments = tempVideoSegments.filter { it.exists() && it.length() > 0L }
            val aFile = tempAudioFile
            val mFile = tempMuxedFile
            val cValues = pendingContentValues

            if (validSegments.isNotEmpty() && cValues != null) {
                val baseName = cValues.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: "BypassCam_${System.currentTimeMillis()}"

                // 1. Mux video segments and audio into a single MP4
                val muxSuccess = if (mFile != null && aFile != null) {
                    MuxerHelper.muxVideoAndAudio(validSegments, aFile, mFile)
                } else false

                val fileToSave = if (muxSuccess && mFile != null && mFile.exists() && mFile.length() > 0L) {
                    mFile
                } else {
                    Log.w(TAG, "Muxing was not successful or produced empty file. Falling back to first video segment.")
                    validSegments[0]
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
                        Log.d(TAG, "Video with ${validSegments.size} segments successfully saved to MediaStore: $finalUri")
                    } else {
                        Log.e(TAG, "Could not insert final video record into MediaStore.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy video file to MediaStore", e)
                }

                // 2. Save standalone audio file separately in Movies/BypassCam if USB mic was used
                if (isUsingUsbMicDuringRecording && aFile != null && aFile.exists()) {
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
                            Log.d(TAG, "Standalone audio file saved: $audioUri")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save standalone audio file", e)
                    }
                }

                // 3. Audio observation report .txt generation
                val isAudioReceived = audioEncoder.isAudioArriving && audioEncoder.totalBytesRead > 0L
                if (isUsingUsbMicDuringRecording) {
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
                            Log.d(TAG, "Audio observation diagnostic log saved: $logUri")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save audio observation log", e)
                    }
                }

                // User notification Toast
                ContextCompat.getMainExecutor(context).execute {
                    val segmentsMsg = if (validSegments.size > 1) " (${validSegments.size} ângulos)" else ""
                    val feedbackMsg = when {
                        videoSavedSuccessfully && isAudioReceived ->
                            "Vídeo$segmentsMsg, áudio & log salvos em Movies/BypassCam"
                        videoSavedSuccessfully ->
                            "Vídeo$segmentsMsg salvo na galeria!"
                        else ->
                            "Falha ao salvar vídeo na galeria"
                    }
                    Toast.makeText(context, feedbackMsg, Toast.LENGTH_LONG).show()
                }

                // Cleanup temporary files
                if (videoSavedSuccessfully) {
                    validSegments.forEach { it.delete() }
                    aFile?.delete()
                    mFile?.delete()
                    tempVideoSegments.clear()
                    Log.d(TAG, "Temporary cache files cleaned up after successful definitive save")
                }
            }
        }.start()
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraManager"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}

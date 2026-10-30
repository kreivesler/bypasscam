package com.bypasscam.app.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bypasscam.app.usb.AudioEncoder
import com.bypasscam.app.usb.MuxerHelper
import com.bypasscam.app.usb.TransformerHelper
import com.bypasscam.app.usb.UsbHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class CameraTake(
    val videoFile: File,
    val audioFile: File?
)

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
    private var currentAudioEncoder: AudioEncoder? = null
    private var lastAudioEncoder: AudioEncoder? = null
    private val recordedTakes = mutableListOf<CameraTake>()
    private var currentTakeVideoFile: File? = null
    private var currentTakeAudioFile: File? = null
    private var tempMuxedFile: File? = null
    private var pendingContentValues: ContentValues? = null
    
    // Multi-segment flip during recording variables
    private var isSessionRecording = false
    private var isFlippingDuringRecording = false
    private var currentChannels = 2
    private var onRecordingStateCallback: ((Boolean) -> Unit)? = null
    private var onSegmentFinalizedForFlip: (() -> Unit)? = null

    // Video processing / saving state observable for UI blocking modal
    private val _isProcessingVideo = MutableStateFlow(false)
    val isProcessingVideo: StateFlow<Boolean> = _isProcessingVideo.asStateFlow()

    // Recording timer state flows
    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private val _isRecordingActive = MutableStateFlow(false)
    val isRecordingActive: StateFlow<Boolean> = _isRecordingActive.asStateFlow()

    private val _isRecordingPaused = MutableStateFlow(false)
    val isRecordingPaused: StateFlow<Boolean> = _isRecordingPaused.asStateFlow()

    private val timerHandler = Handler(Looper.getMainLooper())
    private var pendingTimerStartRunnable: Runnable? = null
    private var accumulatedElapsedMs = 0L
    private var lastResumeUptimeMs = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (_isRecordingActive.value && !_isRecordingPaused.value) {
                val now = SystemClock.uptimeMillis()
                val totalMs = accumulatedElapsedMs + (now - lastResumeUptimeMs)
                _recordingDurationSeconds.value = totalMs / 1000L
                timerHandler.postDelayed(this, 250L)
            }
        }
    }

    private fun startTimer() {
        accumulatedElapsedMs = 0L
        lastResumeUptimeMs = SystemClock.uptimeMillis()
        _recordingDurationSeconds.value = 0L
        _isRecordingActive.value = true
        _isRecordingPaused.value = false
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
    }

    private fun pauseTimer() {
        pendingTimerStartRunnable?.let { timerHandler.removeCallbacks(it) }
        pendingTimerStartRunnable = null
        if (_isRecordingActive.value && !_isRecordingPaused.value) {
            val now = SystemClock.uptimeMillis()
            accumulatedElapsedMs += (now - lastResumeUptimeMs)
            _isRecordingPaused.value = true
            timerHandler.removeCallbacks(timerRunnable)
        }
    }

    private fun resumeTimer() {
        if (_isRecordingActive.value && _isRecordingPaused.value) {
            lastResumeUptimeMs = SystemClock.uptimeMillis()
            _isRecordingPaused.value = false
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
        }
    }

    private fun stopTimer() {
        pendingTimerStartRunnable?.let { timerHandler.removeCallbacks(it) }
        pendingTimerStartRunnable = null
        _isRecordingActive.value = false
        _isRecordingPaused.value = false
        _recordingDurationSeconds.value = 0L
        accumulatedElapsedMs = 0L
        timerHandler.removeCallbacks(timerRunnable)
    }

    // Orientation handling for both Portrait and Landscape
    private var orientationEventListener: OrientationEventListener? = null
    private var currentTargetRotation: Int = Surface.ROTATION_0

    init {
        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (newRotation != currentTargetRotation) {
                    currentTargetRotation = newRotation
                    imageCapture?.targetRotation = newRotation
                    // Update video target rotation only between segments or when idle
                    if (!isSessionRecording || isFlippingDuringRecording) {
                        videoCapture?.targetRotation = newRotation
                    }
                }
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

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

            // ImageCapture configuration with dynamic target rotation
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(currentTargetRotation)
                .build()

            // VideoCapture configuration - Full HD (1080p) with mirror mode for front camera and target rotation
            val qualitySelector = QualitySelector.from(
                Quality.FHD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            )

            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor)
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.Builder(recorder)
                .setTargetRotation(currentTargetRotation)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .build()

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
     * If recording is currently active, it cleanly finalizes the current segment first,
     * switches the lens, and starts the next segment without interrupting the audio stream.
     */
    fun flipCamera(lifecycleOwner: LifecycleOwner) {
        val surfaceProvider = currentSurfaceProvider ?: run {
            Log.e(TAG, "Surface provider not initialized yet")
            return
        }

        if (isSessionRecording) {
            Log.d(TAG, "Flip requested during active recording. Finalizing current take cleanly...")
            isFlippingDuringRecording = true
            pauseTimer()

            onSegmentFinalizedForFlip = {
                Handler(Looper.getMainLooper()).post {
                    Log.d(TAG, "Take finalized. Switching camera lens now (isFrontCamera was $isFrontCamera)...")
                    isFrontCamera = !isFrontCamera
                    startCamera(lifecycleOwner, surfaceProvider) {
                        Log.d(TAG, "Camera preview bound. Starting next take...")
                        if (isSessionRecording) {
                            startNextVideoSegment()
                        }
                    }
                }
            }
            recording?.stop()
            recording = null
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
            _isProcessingVideo.value = true
            stopTimer()
            val activeRecording = recording
            if (activeRecording != null) {
                activeRecording.stop()
                recording = null
            } else {
                currentAudioEncoder?.stop()
                currentAudioEncoder = null
                usbHelper?.stopMicStreaming()
                finalizeAndSaveSession()
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

        // Clear previous takes
        recordedTakes.clear()
        currentAudioEncoder?.stop()
        currentAudioEncoder = null

        val muxedFile = File(context.cacheDir, "temp_muxed.mp4")
        if (muxedFile.exists()) muxedFile.delete()
        tempMuxedFile = muxedFile

        if (isUsbMicConnected) {
            Log.d(TAG, "Iniciando sessão com bypass de driver USB...")
            usbHelper?.startMicStreaming()
        } else {
            Log.d(TAG, "Iniciando sessão padrão com microfone interno...")
        }

        startNextVideoSegment()
    }

    /**
     * Starts a new video segment/take recording.
     */
    @SuppressLint("MissingPermission")
    private fun startNextVideoSegment() {
        if (!isSessionRecording) return

        val videoCapture = this.videoCapture ?: run {
            Log.e(TAG, "videoCapture nulo ao iniciar segmento!")
            return
        }

        val takeIndex = recordedTakes.size
        val segmentFile = File(context.cacheDir, "temp_video_take_$takeIndex.mp4")
        if (segmentFile.exists()) segmentFile.delete()
        currentTakeVideoFile = segmentFile

        val audioFile = if (isUsingUsbMicDuringRecording) {
            val aFile = File(context.cacheDir, "temp_audio_take_$takeIndex.mp4")
            if (aFile.exists()) aFile.delete()
            currentTakeAudioFile = aFile
            aFile
        } else {
            currentTakeAudioFile = null
            null
        }

        val fileOutputOptions = FileOutputOptions.Builder(segmentFile).build()
        val pendingRecording = if (!isUsingUsbMicDuringRecording) {
            videoCapture.output.prepareRecording(context, fileOutputOptions).withAudioEnabled()
        } else {
            videoCapture.output.prepareRecording(context, fileOutputOptions)
        }

        Log.d(TAG, "Iniciando take #$takeIndex (${segmentFile.name})...")

        recording = pendingRecording.start(cameraExecutor) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "Take #$takeIndex iniciado com sucesso (Camera Ready). Fôlego de 1s de configuração...")
                    isFlippingDuringRecording = false
                    Handler(Looper.getMainLooper()).post {
                        onRecordingStateCallback?.invoke(true)
                    }

                    if (isUsingUsbMicDuringRecording && audioFile != null) {
                        val encoder = AudioEncoder()
                        currentAudioEncoder = encoder
                        lastAudioEncoder = encoder
                        val sampleRate = usbHelper?.audioSampleRate ?: 48000
                        val micChannels = usbHelper?.audioChannels ?: currentChannels
                        Log.i(TAG, "Iniciando AudioEncoder com vácuo de 1s para take #$takeIndex (rate=$sampleRate, channels=$micChannels)")
                        encoder.start(audioFile, sampleRate, micChannels, startPaused = false, initialSilenceMs = 1000L)
                    }

                    // Fôlego de 1s para o contador de tempo antes de começar a rodar (tanto no início quanto no flip)
                    pendingTimerStartRunnable?.let { timerHandler.removeCallbacks(it) }
                    val timerDelayRunnable = Runnable {
                        pendingTimerStartRunnable = null
                        if (isSessionRecording) {
                            if (takeIndex == 0) {
                                startTimer()
                            } else {
                                resumeTimer()
                            }
                        }
                    }
                    pendingTimerStartRunnable = timerDelayRunnable
                    timerHandler.postDelayed(timerDelayRunnable, 1000L)
                }
                is VideoRecordEvent.Finalize -> {
                    Log.d(TAG, "Take #$takeIndex finalizado (error: ${recordEvent.hasError()})")

                    currentAudioEncoder?.stop()
                    currentAudioEncoder = null

                    if (!recordEvent.hasError() && segmentFile.exists() && segmentFile.length() > 0L) {
                        val validAudioFile = if (isUsingUsbMicDuringRecording && audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
                            audioFile
                        } else null
                        recordedTakes.add(CameraTake(segmentFile, validAudioFile))
                        Log.d(TAG, "Take #$takeIndex registrado (video=${segmentFile.length()}b, audio=${validAudioFile?.length() ?: 0}b)")
                    }

                    if (isFlippingDuringRecording) {
                        Log.d(TAG, "Segmento finalizado devido a flip de câmera. Executando transição de câmera...")
                        val action = onSegmentFinalizedForFlip
                        onSegmentFinalizedForFlip = null
                        action?.invoke()
                        return@start
                    }

                    if (!isSessionRecording) {
                        // User pressed STOP: finalize complete video
                        stopTimer()
                        Handler(Looper.getMainLooper()).post {
                            onRecordingStateCallback?.invoke(false)
                        }
                        if (isUsingUsbMicDuringRecording) {
                            usbHelper?.stopMicStreaming()
                        }

                        if (!recordEvent.hasError()) {
                            finalizeAndSaveSession()
                        } else {
                            _isProcessingVideo.value = false
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Recording error: Code ${recordEvent.error} - Details: ${recordEvent.cause?.message}")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, "Recording failed: error ${recordEvent.error}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Concatenates all recorded video takes and muxes with their audio into a single definitive video.
     */
    private fun finalizeAndSaveSession() {
        Thread {
            try {
                val validTakes = recordedTakes.filter { it.videoFile.exists() && it.videoFile.length() > 0L }
                val mFile = tempMuxedFile
                val cValues = pendingContentValues

                if (validTakes.isNotEmpty() && cValues != null) {
                    val baseName = cValues.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: "BypassCam_${System.currentTimeMillis()}"

                    var muxSuccess = false
                    val intermediateFilesToClean = mutableListOf<File>()

                    if (validTakes.size == 1) {
                        val singleTake = validTakes[0]
                        if (isUsingUsbMicDuringRecording && singleTake.audioFile != null && singleTake.audioFile.exists()) {
                            muxSuccess = if (mFile != null) {
                                MuxerHelper.muxVideoAndAudio(listOf(singleTake.videoFile), singleTake.audioFile, mFile)
                            } else false
                        } else {
                            // Single take with internal mic: video is already complete with audio
                            muxSuccess = true
                        }
                    } else {
                        // Multi-take with flip:
                        Log.d(TAG, "Processando ${validTakes.size} takes após flip de câmera...")
                        val takesToMerge = mutableListOf<File>()

                        if (isUsingUsbMicDuringRecording) {
                            // Mux each take with its own audio track first (~50ms per take, zero transcoding, exact lip sync)
                            for ((idx, take) in validTakes.withIndex()) {
                                if (take.audioFile != null && take.audioFile.exists() && take.audioFile.length() > 0L) {
                                    val muxedTake = File(context.cacheDir, "temp_take_${idx}_muxed.mp4")
                                    if (muxedTake.exists()) muxedTake.delete()
                                    val muxed = MuxerHelper.muxVideoAndAudio(listOf(take.videoFile), take.audioFile, muxedTake)
                                    if (muxed && muxedTake.exists() && muxedTake.length() > 0L) {
                                        takesToMerge.add(muxedTake)
                                        intermediateFilesToClean.add(muxedTake)
                                    } else {
                                        takesToMerge.add(take.videoFile)
                                    }
                                } else {
                                    takesToMerge.add(take.videoFile)
                                }
                            }
                        } else {
                            // Internal mic: each take already has its audio track embedded
                            takesToMerge.addAll(validTakes.map { it.videoFile })
                        }

                        // Merge all takes with Media3 Transformer: normalizes rotations (front 270°, rear 90°)
                        // without green screen/glitches, preserving the embedded audio track of each take
                        muxSuccess = if (mFile != null && takesToMerge.isNotEmpty()) {
                            TransformerHelper.mergeSegmentsWithAudioSync(
                                context = context,
                                videoFiles = takesToMerge,
                                audioFile = null, // preserves the embedded audio in each take!
                                outputFile = mFile
                            )
                        } else false
                    }

                    val fileToSave = if (muxSuccess && mFile != null && mFile.exists() && mFile.length() > 0L) {
                        mFile
                    } else if (validTakes.size == 1 && !isUsingUsbMicDuringRecording) {
                        validTakes[0].videoFile
                    } else {
                        Log.w(TAG, "Muxing was not successful or produced empty file. Falling back to first video segment.")
                        validTakes[0].videoFile
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
                            Log.d(TAG, "Video with ${validTakes.size} takes successfully saved to MediaStore: $finalUri")
                        } else {
                            Log.e(TAG, "Could not insert final video record into MediaStore.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy video file to MediaStore", e)
                    }

                    // 2. Save standalone audio file separately in Movies/BypassCam if USB mic was used
                    val primaryAudioFile = validTakes.firstOrNull { it.audioFile != null && it.audioFile.exists() }?.audioFile
                    if (isUsingUsbMicDuringRecording && primaryAudioFile != null) {
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
                                    primaryAudioFile.inputStream().use { input ->
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
                    val activeEncoder = lastAudioEncoder
                    val isAudioReceived = activeEncoder?.isAudioArriving == true && (activeEncoder.totalBytesRead) > 0L
                    if (isUsingUsbMicDuringRecording && activeEncoder != null) {
                        try {
                            val audit = usbHelper?.hardwareAuditText ?: ""
                            val jniStatus = try {
                                com.bypasscam.app.usb.BypassAudioJni.getDriverStatus()
                            } catch (e: Throwable) {
                                "Error reading JNI status: ${e.message}"
                            }
                            val logContent = activeEncoder.generateObservationReport(audit, jniStatus)

                            val logValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "${baseName}_audio_log.txt")
                                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BypassCam")
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
                        val takesMsg = if (validTakes.size > 1) " (${validTakes.size} ângulos)" else ""
                        val feedbackMsg = when {
                            videoSavedSuccessfully && isAudioReceived ->
                                "Vídeo$takesMsg, áudio & log salvos em Movies/BypassCam"
                            videoSavedSuccessfully ->
                                "Vídeo$takesMsg salvo na galeria!"
                            else ->
                                "Falha ao salvar vídeo na galeria"
                        }
                        Toast.makeText(context, feedbackMsg, Toast.LENGTH_LONG).show()
                    }

                    // Cleanup temporary files
                    if (videoSavedSuccessfully) {
                        validTakes.forEach { take ->
                            take.videoFile.delete()
                            take.audioFile?.delete()
                        }
                        intermediateFilesToClean.forEach { it.delete() }
                        mFile?.delete()
                        recordedTakes.clear()
                        Log.d(TAG, "Temporary cache files cleaned up after successful definitive save")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in finalizeAndSaveSession", e)
            } finally {
                _isProcessingVideo.value = false
            }
        }.start()
    }

    fun shutdown() {
        orientationEventListener?.disable()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraManager"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}

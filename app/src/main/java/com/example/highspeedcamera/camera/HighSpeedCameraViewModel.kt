package com.example.highspeedcamera.camera

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.highspeedcamera.encoder.EncoderWrapper
import com.example.highspeedcamera.utils.createTempVideoFile
import com.example.highspeedcamera.utils.saveToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "HighSpeedCameraVM"

enum class RecordingState { IDLE, RECORDING, SAVING }

@SuppressLint("MissingPermission")
class HighSpeedCameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState

    private val _cameraReady = MutableStateFlow(false)
    val cameraReady: StateFlow<Boolean> = _cameraReady

    private val _savedUri = MutableStateFlow<Uri?>(null)
    val savedUri: StateFlow<Uri?> = _savedUri

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val cameraThread = HandlerThread("HighSpeedCameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var cameraId: String = ""
    private var fps: Int = 120
    private var dynamicRange: Long = 0L
    private var videoCodecId: Int = 0
    private var width: Int = 1280
    private var height: Int = 720
    private var noPreview: Boolean = false
    private var iso: Int = 400
    private var shutterNs: Long = 1_000_000_000L / 120
    private var outputFile: File? = null

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: EncoderWrapper? = null
    private var hsWakeLock: PowerManager.WakeLock? = null

    @Volatile private var frameGate = false
    fun prepareCamera(
        context: Context,
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        dynamicRange: Long,
        videoCodecId: Int,
        noPreview: Boolean,
        previewSurface: Surface?,
        iso: Int = 400,
        shutterNs: Long = 1_000_000_000L / fps
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                frameGate = false
                try { session?.stopRepeating() } catch (_: Exception) {}
                try { session?.close() }         catch (_: Exception) {}
                try { camera?.close() }          catch (_: Exception) {}
                session = null
                camera  = null
                _cameraReady.value = false

                this@HighSpeedCameraViewModel.cameraId     = cameraId
                this@HighSpeedCameraViewModel.fps          = fps
                this@HighSpeedCameraViewModel.dynamicRange = dynamicRange
                this@HighSpeedCameraViewModel.videoCodecId = videoCodecId
                this@HighSpeedCameraViewModel.width        = width
                this@HighSpeedCameraViewModel.height       = height
                this@HighSpeedCameraViewModel.noPreview    = noPreview
                this@HighSpeedCameraViewModel.iso          = iso
                this@HighSpeedCameraViewModel.shutterNs    = shutterNs

                outputFile = createTempVideoFile(context)
                encoder = EncoderWrapper(
                    width            = width,
                    height           = height,
                    bitRate          = 20_000_000,
                    frameRate        = fps,
                    dynamicRange     = dynamicRange,
                    orientationHint  = 0,
                    outputFile       = outputFile!!,
                    useMediaRecorder = false,
                    videoCodecId     = videoCodecId,
                    isHighSpeed      = true
                )

                setupCamera(context, previewSurface)

            } catch (e: Exception) {
                Log.e(TAG, "prepareCamera failed", e)
                _errorMessage.value = "Camera setup failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun startRecording() {
        if (_recordingState.value != RecordingState.IDLE) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                hsWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HighSpeedCamera:Recording"
                ).also { it.acquire(30 * 60 * 1000L) }

                encoder?.start()
                frameGate = true
                _recordingState.value = RecordingState.RECORDING
                Log.d(TAG, "Recording started at $fps fps")
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                _errorMessage.value = "Failed to start recording: ${e.message}"
            }
        }
    }

    fun stopRecording() {
        if (_recordingState.value != RecordingState.RECORDING) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _recordingState.value = RecordingState.SAVING
                frameGate = false

                try { session?.stopRepeating() } catch (_: Exception) {}
                try { session?.close() }         catch (_: Exception) {}
                session = null

                hsWakeLock?.release()
                hsWakeLock = null
                val succeeded = encoder?.shutdown() ?: false
                encoder = null

                val uri = if (succeeded && outputFile != null) {
                    saveToGallery(
                        context     = getApplication(),
                        file        = outputFile!!,
                        fps         = fps,
                        iso         = iso,
                        shutterNs   = shutterNs
                    )
                } else null

                _savedUri.value = uri
                _recordingState.value = RecordingState.IDLE
                Log.d(TAG, "Recording stopped. Saved: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording failed", e)
                _errorMessage.value = "Failed to stop recording: ${e.message}"
                _recordingState.value = RecordingState.IDLE
            }
        }
    }

    private suspend fun setupCamera(context: Context, previewSurface: Surface?) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        cameraHandler.post {
            Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO)
        }

        camera = openCamera(cameraManager, cameraId, cameraHandler)

        val encoderSurface = encoder!!.getInputSurface()

        val recordTargets: List<Surface> = if (noPreview || previewSurface == null) {
            listOf(encoderSurface)
        } else {
            listOf(previewSurface, encoderSurface)
        }

        session = createHighSpeedCaptureSession(
            device  = camera!!,
            targets = recordTargets,
            handler = cameraHandler
        )

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val selectedSize = Size(width, height)
        val supportedRanges = config.getHighSpeedVideoFpsRangesFor(selectedSize)

        val exactRange: Range<Int> =
            supportedRanges.firstOrNull { it.lower == fps && it.upper == fps }
                ?: supportedRanges.first { it.upper == fps }

        Log.d(TAG, "High-speed AE range: $exactRange for $fps fps")

        val frameDurationNs = 1_000_000_000L / fps

        val hsRequest = session!!.device
            .createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                recordTargets.forEach { addTarget(it) }

                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, exactRange)

                set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)

                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)

                val maxExposure = frameDurationNs
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs.coerceIn(1_000_000L, maxExposure))
            }.build()

        val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
        session!!.setRepeatingBurst(
            highSpeedSession.createHighSpeedRequestList(hsRequest),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (frameGate) {
                        encoder?.frameAvailable()
                    }
                }
            },
            cameraHandler
        )

        Log.d(TAG, "High-speed session running at $fps fps")
        _cameraReady.value = true
    }

    fun clearState() {
        _savedUri.value       = null
        _errorMessage.value   = null
        _cameraReady.value    = false
        _recordingState.value = RecordingState.IDLE
        Log.d(TAG, "State cleared for new recording session")
    }

    fun releaseSession() {
        frameGate = false
        viewModelScope.launch(Dispatchers.IO) {
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { session?.close() }         catch (_: Exception) {}
            session = null
            _cameraReady.value = false
            Log.d(TAG, "Session released (surface destroyed)")
        }
    }

    override fun onCleared() {
        super.onCleared()
        frameGate = false
        _cameraReady.value = false
        hsWakeLock?.release()
        hsWakeLock = null
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() }  catch (_: Exception) {}
        cameraThread.quitSafely()
        Log.d(TAG, "ViewModel cleared — camera released")
    }
}

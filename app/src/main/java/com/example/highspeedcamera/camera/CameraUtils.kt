package com.example.highspeedcamera.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "CameraUtils"

@SuppressLint("MissingPermission")
suspend fun openCamera(
    manager: CameraManager,
    cameraId: String,
    handler: Handler
): CameraDevice = suspendCancellableCoroutine { cont ->
    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) = cont.resume(device)

        override fun onDisconnected(device: CameraDevice) {
            Log.w(TAG, "Camera $cameraId disconnected")
            device.close()
            if (cont.isActive) cont.cancel()
        }

        override fun onError(device: CameraDevice, error: Int) {
            val msg = when (error) {
                CameraDevice.StateCallback.ERROR_CAMERA_DEVICE   -> "Fatal (device)"
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Device policy"
                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE   -> "Camera in use"
                CameraDevice.StateCallback.ERROR_CAMERA_SERVICE  -> "Fatal (service)"
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                else -> "Unknown error $error"
            }
            val exc = RuntimeException("Camera $cameraId error: $msg")
            Log.e(TAG, exc.message, exc)
            if (cont.isActive) cont.resumeWithException(exc)
        }
    }, handler)
}

suspend fun createHighSpeedCaptureSession(
    device: CameraDevice,
    targets: List<Surface>,
    handler: Handler
): CameraCaptureSession = suspendCoroutine { cont ->
    device.createConstrainedHighSpeedCaptureSession(
        targets,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException(
                    "Constrained high-speed session config failed for camera ${device.id}"
                )
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        },
        handler
    )
}

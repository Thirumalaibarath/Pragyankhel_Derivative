package com.example.highspeedcamera.ui.screens

import android.content.Intent
import android.view.SurfaceHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import com.example.highspeedcamera.camera.HighSpeedCameraViewModel
import com.example.highspeedcamera.camera.RecordingState
import com.example.highspeedcamera.ui.ConfigViewModel
import com.example.highspeedcamera.utils.AutoFitSurfaceView
import kotlinx.coroutines.delay

@Composable
fun RecordScreen(
    configVm: ConfigViewModel,
    cameraVm: HighSpeedCameraViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by cameraVm.recordingState.collectAsState()
    val cameraReady by cameraVm.cameraReady.collectAsState()
    val savedUri by cameraVm.savedUri.collectAsState()
    val error by cameraVm.errorMessage.collectAsState()

    val noPreview by configVm.noPreview.collectAsState()
    val selectedCamera = configVm.selectedCamera.value
    val dynamicRange = configVm.selectedDynamicRange.value
    val codecId by configVm.selectedCodecId.collectAsState()
    val iso by configVm.selectedIso.collectAsState()
    val shutterNs by configVm.selectedShutterNs.collectAsState()

    LaunchedEffect(Unit) {
        cameraVm.clearState()
        if (noPreview && selectedCamera != null) {
            cameraVm.prepareCamera(
                context        = context,
                cameraId       = selectedCamera.cameraId,
                width          = selectedCamera.width,
                height         = selectedCamera.height,
                fps            = selectedCamera.fps,
                dynamicRange   = dynamicRange.profile,
                videoCodecId   = codecId,
                noPreview      = true,
                previewSurface = null,
                iso            = iso,
                shutterNs      = shutterNs
            )
        }
    }

    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        if (state == RecordingState.RECORDING) {
            elapsedSeconds = 0
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        } else {
            elapsedSeconds = 0
        }
    }

    LaunchedEffect(savedUri) {
        savedUri?.let { uri ->
            context.startActivity(Intent().apply {
                action = Intent.ACTION_VIEW
                type = "video/mp4"
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            onBack()
        }
    }

    val snackState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let { snackState.showSnackbar(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (noPreview || selectedCamera == null) {
                    Text(
                        "Recording (no preview)",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            AutoFitSurfaceView(ctx).also { surfaceView ->
                                surfaceView.setAspectRatio(
                                    selectedCamera.width, selectedCamera.height
                                )
                                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        cameraVm.prepareCamera(
                                            context       = ctx,
                                            cameraId      = selectedCamera.cameraId,
                                            width         = selectedCamera.width,
                                            height        = selectedCamera.height,
                                            fps           = selectedCamera.fps,
                                            dynamicRange  = dynamicRange.profile,
                                            videoCodecId  = codecId,
                                            noPreview     = false,
                                            previewSurface = holder.surface,
                                            iso           = iso,
                                            shutterNs     = shutterNs
                                        )
                                    }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                                    override fun surfaceDestroyed(h: SurfaceHolder) {
                                        cameraVm.releaseSession()
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state == RecordingState.RECORDING) {
                    val mins = elapsedSeconds / 60
                    val secs = elapsedSeconds % 60
                    Text(
                        text  = "%02d:%02d".format(mins, secs),
                        color = Color.Red,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                val statusText = when (state) {
                    RecordingState.IDLE      -> "Hold to Record"
                    RecordingState.RECORDING -> "Recording…  Release to Stop"
                    RecordingState.SAVING    -> "Saving to Gallery…"
                }
                Text(statusText, color = Color.White, style = MaterialTheme.typography.bodySmall)

                val buttonColor = when (state) {
                    RecordingState.RECORDING -> Color.Red
                    RecordingState.SAVING    -> Color.Gray
                    else                     -> Color.White
                }
                val buttonEnabled = (state == RecordingState.IDLE && cameraReady) ||
                                     state == RecordingState.RECORDING
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(buttonColor, CircleShape)
                        .clickable(enabled = buttonEnabled) {
                            when (state) {
                                RecordingState.IDLE      -> cameraVm.startRecording()
                                RecordingState.RECORDING -> cameraVm.stopRecording()
                                else -> {}
                            }
                        }
                )

                if (state == RecordingState.IDLE) {
                    TextButton(onClick = onBack) {
                        Text("← Back to settings", color = Color.LightGray)
                    }
                }
            }
        }
    }
}

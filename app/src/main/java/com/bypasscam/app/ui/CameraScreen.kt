package com.bypasscam.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bypasscam.app.camera.CameraManager

@Composable
fun CameraScreen(
    cameraManager: CameraManager,
    isUsbMicConnected: Boolean
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isRecording by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(0) } // Used to force preview refresh when switching camera

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                cameraManager.startCamera(lifecycleOwner, previewView.surfaceProvider)
                previewView
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                // Ensure camera binds correctly if key changes
                cameraManager.startCamera(lifecycleOwner, previewView.surfaceProvider)
            }
        )

        // Top Status Bar: USB Connection Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = if (isUsbMicConnected) "USB Mic: Connected" else "USB Mic: Disconnected",
                    color = if (isUsbMicConnected) Color.Green else Color.Red,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Bottom Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Switch Camera
            Button(
                onClick = {
                    cameraManager.isFrontCamera = !cameraManager.isFrontCamera
                    key++ // Trigger recomposition for AndroidView
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("Flip", color = Color.White)
            }

            // Capture Photo
            Button(
                onClick = { cameraManager.takePhoto() },
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("SNAP", color = Color.Black)
            }

            // Record Video
            Button(
                onClick = {
                    cameraManager.toggleRecording(isUsbMicConnected) { recordingState ->
                        isRecording = recordingState
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color.DarkGray
                ),
                modifier = Modifier.size(72.dp),
                shape = CircleShape
            ) {
                Text(if (isRecording) "STOP" else "REC", color = Color.White)
            }
        }
    }
}

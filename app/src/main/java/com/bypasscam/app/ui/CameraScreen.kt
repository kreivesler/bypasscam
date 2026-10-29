package com.bypasscam.app.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bypasscam.app.camera.CameraManager

@Composable
fun CameraScreen(
    cameraManager: CameraManager,
    isUsbMicConnected: Boolean,
    usbChannels: Int = 2,
    usbDeviceInfo: String = ""
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isRecording by remember { mutableStateOf(false) }
    val isProcessingVideo by cameraManager.isProcessingVideo.collectAsState()

    // Prevent closing the app accidentally while video is being processed and saved
    BackHandler(enabled = isProcessingVideo) {
        // App close blocked while processing
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                cameraManager.startCamera(lifecycleOwner, previewView.surfaceProvider)
                previewView
            },
            modifier = Modifier.fillMaxSize()
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
                color = Color.Black.copy(alpha = 0.75f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isUsbMicConnected) "USB Mic: Connected" else "USB Mic: Disconnected",
                        color = if (isUsbMicConnected) Color(0xFF00E676) else Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    if (isUsbMicConnected && usbDeviceInfo.isNotBlank()) {
                        Text(
                            text = usbDeviceInfo,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp
                        )
                    }
                }
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
            // Switch Camera lens (Front / Rear)
            Button(
                onClick = {
                    cameraManager.flipCamera(lifecycleOwner)
                },
                enabled = !isProcessingVideo,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.4f)),
                modifier = Modifier.border(width = 2.dp, color = Color.White, shape = RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Flip", color = Color.White)
            }

            // Capture Photo (Clean shutter button: outer ring with inner solid circle)
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .border(width = 4.dp, color = Color.White, shape = CircleShape)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { cameraManager.takePhoto() },
                    enabled = !isProcessingVideo && !isRecording,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxSize(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    contentPadding = PaddingValues(0.dp)
                ) {}
            }

            // Record Video (Shutter button: Red circle for record, White rounded square for stop)
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .border(
                        width = 4.dp,
                        color = Color.White,
                        shape = CircleShape
                    )
                    .padding(if (isRecording) 18.dp else 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        cameraManager.toggleRecording(isUsbMicConnected, usbChannels) { recordingState ->
                            isRecording = recordingState
                        }
                    },
                    enabled = !isProcessingVideo,
                    shape = if (isRecording) RoundedCornerShape(6.dp) else CircleShape,
                    modifier = Modifier.fillMaxSize(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color.White else Color.Red
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {}
            }
        }

        // Modal Overlay Alert: Blocks UI and instructs user not to close app while saving
        if (isProcessingVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E1E1E),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .padding(horizontal = 28.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E676),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Salvando seu vídeo...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Por favor, não feche o aplicativo enquanto o vídeo é processado e salvo na galeria.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

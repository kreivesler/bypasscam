package com.bypasscam.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.bypasscam.app.camera.CameraManager
import com.bypasscam.app.ui.CameraScreen
import com.bypasscam.app.usb.UsbHelper

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var usbHelper: UsbHelper

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            if (!it.value) allGranted = false
        }
        if (allGranted) {
            startApp()
        } else {
            Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cameraManager = CameraManager(this)
        usbHelper = UsbHelper(this)
        cameraManager.usbHelper = usbHelper

        if (allPermissionsGranted()) {
            startApp()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun startApp() {
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isUsbMicConnected by usbHelper.isUsbMicConnected.collectAsState()
                    val usbDeviceInfo by usbHelper.usbDeviceInfo.collectAsState()
                    val usbChannels by usbHelper.audioChannelsFlow.collectAsState()
                    CameraScreen(
                        cameraManager = cameraManager,
                        isUsbMicConnected = isUsbMicConnected,
                        usbChannels = usbChannels,
                        usbDeviceInfo = usbDeviceInfo
                    )
                }
            }
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { usbHelper.handleUsbDeviceAttached(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
        usbHelper.unregister()
    }
}

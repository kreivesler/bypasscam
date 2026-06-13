package com.bypasscam.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Helper class to manage USB Audio device connections.
 * This class detects when a USB_CLASS_AUDIO device is connected,
 * claims its interface, and starts the Rust user-space driver.
 */
class UsbHelper(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    
    private val _isUsbMicConnected = MutableStateFlow(false)
    val isUsbMicConnected: StateFlow<Boolean> = _isUsbMicConnected

    private val actionUsbPermission = "com.bypasscam.app.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.apply {
                                checkAndSetUsbAudioDevice(this)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { requestUsbPermission(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (isAudioDevice(it)) {
                            stopUsbAudioDriver()
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(actionUsbPermission)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        
        checkForConnectedDevices()
    }

    private fun checkForConnectedDevices() {
        usbManager.deviceList.values.forEach { device ->
            if (isAudioDevice(device)) {
                requestUsbPermission(device)
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (!isAudioDevice(device)) return
        
        if (!usbManager.hasPermission(device)) {
            val intent = Intent(actionUsbPermission).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            checkAndSetUsbAudioDevice(device)
        }
    }

    private fun checkAndSetUsbAudioDevice(device: UsbDevice) {
        if (isAudioDevice(device)) {
            val details = findAudioInputDetails(device)
            if (details != null) {
                val (usbInterface, endpoint) = details
                try {
                    val connection = usbManager.openDevice(device)
                    if (connection != null) {
                        if (connection.claimInterface(usbInterface, true)) {
                            usbConnection = connection
                            claimedInterface = usbInterface
                            val fd = connection.fileDescriptor
                            
                            // Start Rust USB Audio Driver
                            // Parameters: fd, interface index, endpoint address, 48kHz, 1 (Mono)
                            val started = BypassAudioJni.startAudioStream(
                                fd,
                                usbInterface.id,
                                endpoint.address,
                                48000,
                                1
                            )
                            if (started) {
                                _isUsbMicConnected.value = true
                                Log.i("UsbHelper", "Successfully initialized Rust USB Audio Class Driver (fd=$fd)")
                            } else {
                                Log.e("UsbHelper", "Rust driver failed to start.")
                                connection.releaseInterface(usbInterface)
                                connection.close()
                                usbConnection = null
                                claimedInterface = null
                            }
                        } else {
                            Log.e("UsbHelper", "Failed to claim USB interface.")
                            connection.close()
                        }
                    } else {
                        Log.e("UsbHelper", "Failed to open USB device connection.")
                    }
                } catch (e: Exception) {
                    Log.e("UsbHelper", "Error establishing USB device connection", e)
                }
            } else {
                Log.w("UsbHelper", "No suitable audio input endpoint found on device.")
            }
        }
    }

    private fun findAudioInputDetails(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            // Class 1 is USB Audio
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    // We need input streaming endpoint
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                        val type = endpoint.type
                        if (type == UsbConstants.USB_ENDPOINT_XFER_ISOC || type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            return Pair(usbInterface, endpoint)
                        }
                    }
                }
            }
        }
        return null
    }

    fun stopUsbAudioDriver() {
        BypassAudioJni.stopAudioStream()
        try {
            claimedInterface?.let {
                usbConnection?.releaseInterface(it)
            }
        } catch (e: Exception) {
            Log.w("UsbHelper", "Error releasing interface: ${e.message}")
        }
        try {
            usbConnection?.close()
        } catch (e: Exception) {
            Log.w("UsbHelper", "Error closing connection: ${e.message}")
        }
        usbConnection = null
        claimedInterface = null
        _isUsbMicConnected.value = false
        Log.i("UsbHelper", "USB Audio Driver stopped and clean up complete.")
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    fun unregister() {
        stopUsbAudioDriver()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }
}

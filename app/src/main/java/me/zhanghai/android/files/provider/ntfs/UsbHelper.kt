package me.zhanghai.android.files.provider.ntfs

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

class UsbHelper(private val context: Context) {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun listUsbDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun listMassStorageDevices(): List<UsbDevice> =
        listUsbDevices().filter { isMassStorageDevice(it) }

    fun isMassStorageDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return true
            }
        }
        return false
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (hasPermission(device)) {
            onResult(true)
            return
        }
        val action = "${context.packageName}.USB_PERMISSION_${device.deviceId}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == action) {
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    onResult(granted)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(action),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    fun openDevice(device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)

    fun getUsbFileDescriptor(device: UsbDevice): Int? {
        val connection = openDevice(device) ?: return null
        return connection.fileDescriptor
    }
}

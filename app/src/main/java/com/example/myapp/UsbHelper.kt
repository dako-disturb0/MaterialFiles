package com.example.myapp

import android.hardware.usb.*
import android.content.Context

class UsbHelper(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun getUsbFileDescriptor(device: UsbDevice): Int? {
        val connection = usbManager.openDevice(device) ?: return null
        return connection.fileDescriptor  // FD ini dikirim ke JNI
    }

    fun listUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }
}

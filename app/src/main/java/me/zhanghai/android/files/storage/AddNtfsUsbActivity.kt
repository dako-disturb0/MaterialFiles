/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.getParcelableExtraSafe

class AddNtfsUsbActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val device = intent.getParcelableExtraSafe<UsbDevice>(UsbManager.EXTRA_DEVICE)
            supportFragmentManager.commit {
                add(android.R.id.content, AddNtfsUsbFragment.newInstance(device))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val device = intent.getParcelableExtraSafe<UsbDevice>(UsbManager.EXTRA_DEVICE)
        val fragment = supportFragmentManager.findFragmentById(android.R.id.content) as? AddNtfsUsbFragment
        fragment?.onNewDeviceAttached(device)
    }
}

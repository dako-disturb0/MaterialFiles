/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import me.zhanghai.android.files.util.commitFragment

class EditMediaFireServerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val fragment = EditMediaFireServerFragment().apply { arguments = intent.extras }
            commitFragment(fragment)
        }
    }
}

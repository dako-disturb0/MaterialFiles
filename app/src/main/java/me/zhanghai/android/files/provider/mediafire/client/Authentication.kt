/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mediafire.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Authentication(
    val email: String,
    val password: String,
    val appId: String,
    val apiKey: String
) : Parcelable

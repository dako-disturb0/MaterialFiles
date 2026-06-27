/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.provider.common.UriAuthority

@Parcelize
data class Authority(val clientEmail: String) : Parcelable {
    fun toUriAuthority(): UriAuthority = UriAuthority(null, clientEmail, null)

    override fun toString(): String = clientEmail
}

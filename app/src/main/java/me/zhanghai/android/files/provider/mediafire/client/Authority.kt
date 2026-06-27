/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mediafire.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.provider.common.UriAuthority

@Parcelize
data class Authority(
    val email: String
) : Parcelable {
    fun toUriAuthority(): UriAuthority {
        return UriAuthority(email, null, null)
    }

    override fun toString(): String = email
}

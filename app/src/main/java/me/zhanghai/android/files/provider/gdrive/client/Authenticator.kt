/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive.client

interface Authenticator {
    fun getServiceAccountJson(authority: Authority): String?
    fun isViewWithMediaCacheEnabled(authority: Authority): Boolean
    fun getRootFolderId(authority: Authority): String
}

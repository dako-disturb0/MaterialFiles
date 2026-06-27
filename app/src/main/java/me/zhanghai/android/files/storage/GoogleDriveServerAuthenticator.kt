/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import me.zhanghai.android.files.provider.gdrive.client.Authenticator
import me.zhanghai.android.files.provider.gdrive.client.Authority
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object GoogleDriveServerAuthenticator : Authenticator {
    private val transientServers = mutableSetOf<GoogleDriveServer>()

    override fun getServiceAccountJson(authority: Authority): String? {
        val server = synchronized(transientServers) {
            transientServers.find { it.clientEmail == authority.clientEmail }
        } ?: Settings.STORAGES.valueCompat.find {
            it is GoogleDriveServer && it.clientEmail == authority.clientEmail
        } as GoogleDriveServer?
        return server?.serviceAccountJson
    }

    override fun isViewWithMediaCacheEnabled(authority: Authority): Boolean {
        val server = synchronized(transientServers) {
            transientServers.find { it.clientEmail == authority.clientEmail }
        } ?: Settings.STORAGES.valueCompat.find {
            it is GoogleDriveServer && it.clientEmail == authority.clientEmail
        } as GoogleDriveServer?
        return server?.viewWithMediaCache ?: true
    }

    override fun getRootFolderId(authority: Authority): String {
        val server = synchronized(transientServers) {
            transientServers.find { it.clientEmail == authority.clientEmail }
        } ?: Settings.STORAGES.valueCompat.find {
            it is GoogleDriveServer && it.clientEmail == authority.clientEmail
        } as GoogleDriveServer?
        return server?.rootFolderId ?: "root"
    }

    fun addTransientServer(server: GoogleDriveServer) {
        synchronized(transientServers) { transientServers += server }
    }

    fun removeTransientServer(server: GoogleDriveServer) {
        synchronized(transientServers) { transientServers -= server }
    }
}

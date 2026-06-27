/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import me.zhanghai.android.files.provider.mediafire.client.Authentication
import me.zhanghai.android.files.provider.mediafire.client.Authenticator
import me.zhanghai.android.files.provider.mediafire.client.Authority
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object MediaFireServerAuthenticator : Authenticator {
    private val transientServers = mutableSetOf<MediaFireServer>()

    override fun getAuthentication(authority: Authority): Authentication? {
        val server = synchronized(transientServers) {
            transientServers.find { it.email == authority.email }
        } ?: Settings.STORAGES.valueCompat.find {
            it is MediaFireServer && it.email == authority.email
        } as MediaFireServer?
        return server?.let {
            Authentication(it.email, it.password, it.appId, it.apiKey)
        }
    }

    fun addTransientServer(server: MediaFireServer) {
        synchronized(transientServers) { transientServers += server }
    }

    fun removeTransientServer(server: MediaFireServer) {
        synchronized(transientServers) { transientServers -= server }
    }
}

/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive

import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.WatchService
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.ByteStringListPathCreator
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.gdrive.client.Authority
import me.zhanghai.android.files.util.readParcelable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class GoogleDriveFileSystem(
    private val provider: GoogleDriveFileSystemProvider,
    val authority: Authority
) : FileSystem(), ByteStringListPathCreator, Parcelable {
    val rootDirectory = GoogleDrivePath(this, SEPARATOR_BYTE_STRING)

    init {
        if (!rootDirectory.isAbsolute) {
            throw AssertionError("Root directory must be absolute")
        }
        if (rootDirectory.nameCount != 0) {
            throw AssertionError("Root directory must contain no names")
        }
    }

    private val lock = Any()
    private var isOpen = true

    // Path to Key dynamic cache
    data class KeyInfo(
        val key: String, // Google Drive file ID
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val name: String
    )

    private val pathKeys = ConcurrentHashMap<String, KeyInfo>()

    fun getCachedKey(path: String): KeyInfo? = pathKeys[path]

    fun putCachedKey(path: String, info: KeyInfo) {
        pathKeys[path] = info
    }

    fun removeCachedKey(path: String) {
        pathKeys.remove(path)
        val prefix = if (path.endsWith("/")) path else "$path/"
        pathKeys.keys.removeIf { it.startsWith(prefix) }
    }

    fun clearCache() {
        pathKeys.clear()
    }

    val defaultDirectory: GoogleDrivePath
        get() = rootDirectory

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        synchronized(lock) {
            if (!isOpen) {
                return
            }
            clearCache()
            provider.removeFileSystem(this)
            isOpen = false
        }
    }

    override fun isOpen(): Boolean = synchronized(lock) { isOpen }

    override fun isReadOnly(): Boolean = false

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun getRootDirectories(): Iterable<Path> = listOf(rootDirectory)

    override fun getFileStores(): Iterable<FileStore> {
        throw UnsupportedOperationException()
    }

    override fun supportedFileAttributeViews(): Set<String> =
        GoogleDriveFileAttributeView.SUPPORTED_NAMES

    override fun getPath(first: String, vararg more: String): GoogleDrivePath {
        val path = ByteStringBuilder(first.toByteString())
            .apply { more.forEach { append(SEPARATOR).append(it.toByteString()) } }
            .toByteString()
        return GoogleDrivePath(this, path)
    }

    override fun getPath(first: ByteString, vararg more: ByteString): GoogleDrivePath {
        val path = ByteStringBuilder(first)
            .apply { more.forEach { append(SEPARATOR).append(it) } }
            .toByteString()
        return GoogleDrivePath(this, path)
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        throw UnsupportedOperationException()
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newWatchService(): WatchService = LocalWatchService()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as GoogleDriveFileSystem
        return authority == other.authority
    }

    override fun hashCode(): Int = authority.hashCode()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(authority, flags)
    }

    companion object {
        const val SEPARATOR = '/'.code.toByte()
        private val SEPARATOR_BYTE_STRING = SEPARATOR.toByteString()
        private const val SEPARATOR_STRING = SEPARATOR.toInt().toChar().toString()

        @JvmField
        val CREATOR = object : Parcelable.Creator<GoogleDriveFileSystem> {
            override fun createFromParcel(source: Parcel): GoogleDriveFileSystem {
                val authority = source.readParcelable<Authority>()!!
                return GoogleDriveFileSystemProvider.getOrNewFileSystem(authority)
            }

            override fun newArray(size: Int): Array<GoogleDriveFileSystem?> = arrayOfNulls(size)
        }
    }
}

/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive

import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringListPath
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.UriAuthority
import me.zhanghai.android.files.provider.gdrive.client.Authority
import me.zhanghai.android.files.util.readParcelable
import java.io.File
import java.io.IOException

internal class GoogleDrivePath : ByteStringListPath<GoogleDrivePath> {
    private val fileSystem: GoogleDriveFileSystem

    constructor(
        fileSystem: GoogleDriveFileSystem,
        path: ByteString
    ) : super(GoogleDriveFileSystem.SEPARATOR, path) {
        this.fileSystem = fileSystem
    }

    private constructor(
        fileSystem: GoogleDriveFileSystem,
        absolute: Boolean,
        segments: List<ByteString>
    ) : super(GoogleDriveFileSystem.SEPARATOR, absolute, segments) {
        this.fileSystem = fileSystem
    }

    override fun isPathAbsolute(path: ByteString): Boolean =
        path.isNotEmpty() && path[0] == GoogleDriveFileSystem.SEPARATOR

    override fun createPath(path: ByteString): GoogleDrivePath = GoogleDrivePath(fileSystem, path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): GoogleDrivePath =
        GoogleDrivePath(fileSystem, absolute, segments)

    override val uriScheme: String
        get() = "gdrive"

    override val uriAuthority: UriAuthority
        get() = fileSystem.authority.toUriAuthority()

    override val defaultDirectory: GoogleDrivePath
        get() = fileSystem.defaultDirectory

    override fun getFileSystem(): FileSystem = fileSystem

    override fun getRoot(): GoogleDrivePath? = if (isAbsolute) fileSystem.rootDirectory else null

    @Throws(IOException::class)
    override fun toRealPath(vararg options: LinkOption): GoogleDrivePath {
        throw UnsupportedOperationException()
    }

    override fun toFile(): File {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun register(
        watcher: WatchService,
        events: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): WatchKey {
        if (watcher !is LocalWatchService) {
            throw ProviderMismatchException(watcher.toString())
        }
        return watcher.register(this, events, *modifiers)
    }

    val authority: Authority
        get() = fileSystem.authority

    private constructor(source: Parcel) : super(source) {
        fileSystem = source.readParcelable()!!
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)

        dest.writeParcelable(fileSystem, flags)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<GoogleDrivePath> {
            override fun createFromParcel(source: Parcel): GoogleDrivePath = GoogleDrivePath(source)

            override fun newArray(size: Int): Array<GoogleDrivePath?> = arrayOfNulls(size)
        }
    }
}

val Path.isGoogleDrivePath: Boolean
    get() = this is GoogleDrivePath

/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mediafire

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
import me.zhanghai.android.files.provider.mediafire.client.Authority
import me.zhanghai.android.files.util.readParcelable
import java.io.File
import java.io.IOException

internal class MediaFirePath : ByteStringListPath<MediaFirePath> {
    private val fileSystem: MediaFireFileSystem

    constructor(
        fileSystem: MediaFireFileSystem,
        path: ByteString
    ) : super(MediaFireFileSystem.SEPARATOR, path) {
        this.fileSystem = fileSystem
    }

    private constructor(
        fileSystem: MediaFireFileSystem,
        absolute: Boolean,
        segments: List<ByteString>
    ) : super(MediaFireFileSystem.SEPARATOR, absolute, segments) {
        this.fileSystem = fileSystem
    }

    override fun isPathAbsolute(path: ByteString): Boolean =
        path.isNotEmpty() && path[0] == MediaFireFileSystem.SEPARATOR

    override fun createPath(path: ByteString): MediaFirePath = MediaFirePath(fileSystem, path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): MediaFirePath =
        MediaFirePath(fileSystem, absolute, segments)

    override val uriScheme: String
        get() = "mediafire"

    override val uriAuthority: UriAuthority
        get() = fileSystem.authority.toUriAuthority()

    override val defaultDirectory: MediaFirePath
        get() = fileSystem.defaultDirectory

    override fun getFileSystem(): FileSystem = fileSystem

    override fun getRoot(): MediaFirePath? = if (isAbsolute) fileSystem.rootDirectory else null

    @Throws(IOException::class)
    override fun toRealPath(vararg options: LinkOption): MediaFirePath {
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
        val CREATOR = object : Parcelable.Creator<MediaFirePath> {
            override fun createFromParcel(source: Parcel): MediaFirePath = MediaFirePath(source)

            override fun newArray(size: Int): Array<MediaFirePath?> = arrayOfNulls(size)
        }
    }
}

val Path.isMediaFirePath: Boolean
    get() = this is MediaFirePath

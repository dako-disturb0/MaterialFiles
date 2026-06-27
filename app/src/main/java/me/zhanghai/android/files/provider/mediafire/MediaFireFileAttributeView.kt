/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mediafire

import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.FileTime
import java.io.IOException

internal class MediaFireFileAttributeView(
    private val path: MediaFirePath,
    private val noFollowLinks: Boolean
) : BasicFileAttributeView {
    override fun name(): String = NAME

    @Throws(IOException::class)
    override fun readAttributes(): MediaFireFileAttributes {
        val info = MediaFireFileSystemProvider.getKeyInfo(path)
        return MediaFireFileAttributes.from(info, path)
    }

    override fun setTimes(
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        createTime: FileTime?
    ) {
        // Not supported by MediaFire API
        throw UnsupportedOperationException("setTimes")
    }

    companion object {
        private const val NAME = "mediafire"

        val SUPPORTED_NAMES = setOf("basic", NAME)
    }
}

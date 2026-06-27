/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive

import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.FileTime
import java.io.IOException

internal class GoogleDriveFileAttributeView(
    private val path: GoogleDrivePath,
    private val noFollowLinks: Boolean
) : BasicFileAttributeView {
    override fun name(): String = NAME

    @Throws(IOException::class)
    override fun readAttributes(): GoogleDriveFileAttributes {
        val info = GoogleDriveFileSystemProvider.getKeyInfo(path)
        return GoogleDriveFileAttributes.from(info, path)
    }

    override fun setTimes(
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        createTime: FileTime?
    ) {
        throw UnsupportedOperationException("setTimes")
    }

    companion object {
        private const val NAME = "gdrive"

        val SUPPORTED_NAMES = setOf("basic", NAME)
    }
}

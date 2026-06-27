/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mediafire

import android.os.Parcelable
import java8.nio.file.attribute.FileTime
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.provider.common.AbstractBasicFileAttributes
import me.zhanghai.android.files.provider.common.BasicFileType
import me.zhanghai.android.files.provider.common.FileTimeParceler

@Parcelize
internal data class MediaFireFileAttributes(
    override val lastModifiedTime: @WriteWith<FileTimeParceler> FileTime,
    override val lastAccessTime: @WriteWith<FileTimeParceler> FileTime,
    override val creationTime: @WriteWith<FileTimeParceler> FileTime,
    override val type: BasicFileType,
    override val size: Long,
    override val fileKey: Parcelable
) : AbstractBasicFileAttributes() {
    companion object {
        fun from(info: MediaFireFileSystem.KeyInfo, path: MediaFirePath): MediaFireFileAttributes {
            val fileTime = FileTime.fromMillis(info.lastModified)
            val type = if (info.isDirectory) BasicFileType.DIRECTORY else BasicFileType.REGULAR_FILE
            return MediaFireFileAttributes(
                lastModifiedTime = fileTime,
                lastAccessTime = fileTime,
                creationTime = fileTime,
                type = type,
                size = info.size,
                fileKey = path
            )
        }
    }
}

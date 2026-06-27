/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive

import java8.nio.file.Path
import me.zhanghai.android.files.provider.gdrive.client.Authority

fun Authority.createGoogleDriveRootPath(): Path =
    GoogleDriveFileSystemProvider.getOrNewFileSystem(this).rootDirectory

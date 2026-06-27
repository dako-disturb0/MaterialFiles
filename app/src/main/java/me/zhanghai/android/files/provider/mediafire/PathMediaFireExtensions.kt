/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mediafire

import java8.nio.file.Path
import me.zhanghai.android.files.provider.mediafire.client.Authority

fun Authority.createMediaFireRootPath(): Path =
    MediaFireFileSystemProvider.getOrNewFileSystem(this).rootDirectory

/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.gdrive.client.Authority
import me.zhanghai.android.files.provider.gdrive.createGoogleDriveRootPath
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import kotlin.random.Random

@Parcelize
class GoogleDriveServer(
    override val id: Long,
    override val customName: String?,
    val clientEmail: String,
    val serviceAccountJson: String,
    val viewWithMediaCache: Boolean,
    val rootFolderId: String = "root"
) : Storage() {
    constructor(
        id: Long?,
        customName: String?,
        clientEmail: String,
        serviceAccountJson: String,
        viewWithMediaCache: Boolean,
        rootFolderId: String = "root"
    ) : this(id ?: Random.nextLong(), customName, clientEmail, serviceAccountJson, viewWithMediaCache, rootFolderId)

    val authority: Authority
        get() = Authority(clientEmail)

    override val iconRes: Int
        @DrawableRes
        get() = R.drawable.computer_icon_white_24dp

    override fun getDefaultName(context: Context): String = clientEmail

    override val description: String
        get() = clientEmail

    override val path: Path
        get() = authority.createGoogleDriveRootPath()

    override fun createEditIntent(): Intent =
        EditGoogleDriveServerActivity::class.createIntent().putArgs(EditGoogleDriveServerFragment.Args(this))
}

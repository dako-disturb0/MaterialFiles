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
import me.zhanghai.android.files.provider.mediafire.client.Authority
import me.zhanghai.android.files.provider.mediafire.createMediaFireRootPath
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import kotlin.random.Random

@Parcelize
class MediaFireServer(
    override val id: Long,
    override val customName: String?,
    val email: String,
    val password: String,
    val appId: String,
    val apiKey: String,
    val relativePath: String = ""
) : Storage() {
    constructor(
        id: Long?,
        customName: String?,
        email: String,
        password: String,
        appId: String,
        apiKey: String,
        relativePath: String = ""
    ) : this(id ?: Random.nextLong(), customName, email, password, appId, apiKey, relativePath)

    val authority: Authority
        get() = Authority(email)

    override val iconRes: Int
        @DrawableRes
        get() = R.drawable.computer_icon_white_24dp

    override fun getDefaultName(context: Context): String =
        if (relativePath.isNotEmpty()) "$email/$relativePath" else email

    override val description: String
        get() = email

    override val path: Path
        get() = authority.createMediaFireRootPath().resolve(relativePath)

    override fun createEditIntent(): Intent =
        EditMediaFireServerActivity::class.createIntent().putArgs(EditMediaFireServerFragment.Args(this))
}

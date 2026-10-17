/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.text.format.Formatter
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.NtfsFileItemBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater

data class NtfsFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long
)

class NtfsFileAdapter(
    private val listener: (NtfsFileItem) -> Unit
) : SimpleAdapter<NtfsFileItem, NtfsFileAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(NtfsFileItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val binding = holder.binding
        binding.itemLayout.setOnClickListener { listener(item) }
        binding.nameText.text = item.name
        if (item.isDirectory) {
            binding.iconImage.setImageResource(R.drawable.directory_icon_white_24dp)
            binding.infoText.text = holder.itemView.context.getString(R.string.file_open_as_type_directory)
        } else {
            binding.iconImage.setImageResource(R.drawable.file_icon_white_24dp)
            binding.infoText.text = Formatter.formatFileSize(holder.itemView.context, item.size)
        }
    }

    class ViewHolder(val binding: NtfsFileItemBinding) : RecyclerView.ViewHolder(binding.root)
}

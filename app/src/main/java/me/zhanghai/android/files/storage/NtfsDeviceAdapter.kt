/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.hardware.usb.UsbDevice
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.databinding.NtfsDeviceItemBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater

class NtfsDeviceAdapter(
    private val listener: (UsbDevice) -> Unit
) : SimpleAdapter<UsbDevice, NtfsDeviceAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).deviceId.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(NtfsDeviceItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = getItem(position)
        val binding = holder.binding
        binding.itemLayout.setOnClickListener { listener(device) }
        val name = device.productName ?: device.deviceName
        binding.titleText.text = name
        val manufacturer = device.manufacturerName
        binding.subtitleText.text = if (!manufacturer.isNullOrEmpty()) {
            "$manufacturer (${device.deviceName})"
        } else {
            device.deviceName
        }
    }

    class ViewHolder(val binding: NtfsDeviceItemBinding) : RecyclerView.ViewHolder(binding.root)
}

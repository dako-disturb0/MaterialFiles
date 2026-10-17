/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AddNtfsUsbFragmentBinding
import me.zhanghai.android.files.provider.ntfs.NtfsLib
import me.zhanghai.android.files.provider.ntfs.UsbHelper
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getParcelableExtraSafe

class AddNtfsUsbFragment : Fragment() {
    private lateinit var binding: AddNtfsUsbFragmentBinding
    private lateinit var usbHelper: UsbHelper

    private lateinit var deviceAdapter: NtfsDeviceAdapter
    private lateinit var fileAdapter: NtfsFileAdapter

    private var activeConnection: UsbDeviceConnection? = null
    private var isVolumeMounted = false
    private var currentDirectory = "/"
    private var activeDeviceName: String? = null
    private var activeDevice: UsbDevice? = null
    private var initialDevice: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtraSafe<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && usbHelper.isMassStorageDevice(device)) {
                        loadDevices(autoConnectDevice = device)
                    } else {
                        loadDevices(autoConnectDevice = null)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtraSafe<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device == null || activeConnection == null || device.deviceId == activeDevice?.deviceId) {
                        closeVolumeAndShowDevices()
                    } else {
                        loadDevices(autoConnectDevice = null)
                    }
                }
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isVolumeMounted) {
                if (currentDirectory != "/" && currentDirectory.isNotEmpty()) {
                    val parent = currentDirectory.substringBeforeLast('/', "").ifEmpty { "/" }
                    loadDirectory(parent)
                } else {
                    closeVolumeAndShowDevices()
                }
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        initialDevice = arguments?.getParcelable(EXTRA_DEVICE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        AddNtfsUsbFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { backPressedCallback.handleOnBackPressed() }
        activity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        usbHelper = UsbHelper(requireContext())

        deviceAdapter = NtfsDeviceAdapter { onDeviceClicked(it) }
        fileAdapter = NtfsFileAdapter { onFileClicked(it) }

        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.adapter = deviceAdapter

        binding.swipeRefreshLayout.setOnRefreshListener {
            if (isVolumeMounted) {
                loadDirectory(currentDirectory)
            } else {
                loadDevices(autoConnectDevice = null)
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            requireContext(),
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        loadDevices(autoConnectDevice = initialDevice)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        closeVolume()
    }

    fun onNewDeviceAttached(device: UsbDevice?) {
        if (device != null && usbHelper.isMassStorageDevice(device)) {
            loadDevices(autoConnectDevice = device)
        } else {
            loadDevices(autoConnectDevice = null)
        }
    }

    private fun loadDevices(autoConnectDevice: UsbDevice? = null) {
        binding.progress.fadeToVisibilityUnsafe(false)
        binding.swipeRefreshLayout.isRefreshing = false
        val devices = usbHelper.listMassStorageDevices().ifEmpty { usbHelper.listUsbDevices() }
        deviceAdapter.replace(devices)
        val hasDevices = devices.isNotEmpty()
        binding.emptyView.fadeToVisibilityUnsafe(!hasDevices)
        if (!hasDevices) {
            binding.emptyView.setText(R.string.ntfs_usb_no_devices)
        }
        val activity = requireActivity() as AppCompatActivity
        activity.supportActionBar?.title = getString(R.string.storage_add_storage_ntfs_usb_otg)
        activity.supportActionBar?.subtitle = null

        val deviceToConnect = autoConnectDevice ?: if (devices.size == 1) devices.first() else null
        if (deviceToConnect != null && !isVolumeMounted) {
            onDeviceClicked(deviceToConnect)
        }
    }

    private fun onDeviceClicked(device: UsbDevice) {
        activeDevice = device
        if (!usbHelper.hasPermission(device)) {
            usbHelper.requestPermission(device) { granted ->
                if (granted) {
                    openDeviceVolume(device)
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.ntfs_usb_permission_required,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            openDeviceVolume(device)
        }
    }

    private fun openDeviceVolume(device: UsbDevice) {
        activeDevice = device
        binding.progress.fadeToVisibilityUnsafe(true)
        activeDeviceName = device.productName ?: device.deviceName

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val connection = usbHelper.openDevice(device) ?: return@withContext false
                activeConnection = connection
                val fd = connection.fileDescriptor
                NtfsLib.openVolume(fd)
            }

            binding.progress.fadeToVisibilityUnsafe(false)
            if (success) {
                isVolumeMounted = true
                binding.recyclerView.adapter = fileAdapter
                binding.emptyView.fadeToVisibilityUnsafe(false)
                loadDirectory("/")
            } else {
                closeVolume()
                Toast.makeText(
                    requireContext(),
                    R.string.ntfs_usb_mount_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadDirectory(path: String) {
        currentDirectory = path
        binding.progress.fadeToVisibilityUnsafe(true)
        val activity = requireActivity() as AppCompatActivity
        activity.supportActionBar?.title = activeDeviceName ?: getString(R.string.ntfs_usb_title)
        activity.supportActionBar?.subtitle = path

        lifecycleScope.launch {
            val fileNames = withContext(Dispatchers.IO) {
                NtfsLib.listFiles(path)
            }
            binding.progress.fadeToVisibilityUnsafe(false)
            binding.swipeRefreshLayout.isRefreshing = false

            if (fileNames != null) {
                val items = withContext(Dispatchers.IO) {
                    fileNames.map { name ->
                        val subPath = if (path == "/") "/$name" else "$path/$name"
                        val isDir = NtfsLib.isDirectory(subPath)
                        val size = if (isDir) 0L else NtfsLib.getFileSize(subPath)
                        NtfsFileItem(name, subPath, isDir, size)
                    }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                }
                fileAdapter.replace(items)
                binding.emptyView.fadeToVisibilityUnsafe(items.isEmpty())
                if (items.isEmpty()) {
                    binding.emptyView.text = getString(R.string.file_list_empty)
                }
            } else {
                fileAdapter.replace(emptyList())
                binding.emptyView.fadeToVisibilityUnsafe(true)
                binding.emptyView.text = getString(R.string.file_list_empty)
            }
        }
    }

    private fun onFileClicked(item: NtfsFileItem) {
        if (item.isDirectory) {
            loadDirectory(item.path)
        } else {
            lifecycleScope.launch {
                binding.progress.fadeToVisibilityUnsafe(true)
                val previewBytes = withContext(Dispatchers.IO) {
                    NtfsLib.readFile(item.path, 0, minOf(item.size, 1024L))
                }
                binding.progress.fadeToVisibilityUnsafe(false)
                if (previewBytes != null) {
                    Toast.makeText(
                        requireContext(),
                        "${item.name}: ${item.size} bytes (${previewBytes.size} bytes read)",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.ntfs_usb_read_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun closeVolumeAndShowDevices() {
        closeVolume()
        binding.recyclerView.adapter = deviceAdapter
        loadDevices(autoConnectDevice = null)
    }

    private fun closeVolume() {
        if (isVolumeMounted) {
            NtfsLib.closeVolume()
            isVolumeMounted = false
        }
        activeConnection?.close()
        activeConnection = null
        activeDevice = null
    }

    companion object {
        private const val EXTRA_DEVICE = "extra_device"

        fun newInstance(device: UsbDevice? = null): AddNtfsUsbFragment =
            AddNtfsUsbFragment().apply {
                if (device != null) {
                    arguments = Bundle().apply { putParcelable(EXTRA_DEVICE, device) }
                }
            }
    }
}

/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive.client

import me.zhanghai.android.files.app.okHttpClient
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.EMPTY
import me.zhanghai.android.files.provider.common.readFully
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class GoogleDriveFileByteChannel(
    private val clientEmail: String,
    private var fileId: String,
    private val parentId: String,
    private val filename: String,
    private val isWrite: Boolean,
    isAppend: Boolean,
    private val initialSize: Long
) : AbstractFileByteChannel(isAppend) {
    private var tempFile: File? = null
    private var tempFileChannel: FileChannel? = null

    init {
        if (isWrite) {
            val file = File.createTempFile("gdrive-upload-", null)
            tempFile = file
            tempFileChannel = RandomAccessFile(file, "rw").channel
            if (fileId.isNotEmpty()) {
                // Pre-populate with existing file contents
                try {
                    downloadToTempFile(file)
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun downloadToTempFile(file: File) {
        val request = GoogleDriveClient.getDownloadRequest(clientEmail, fileId)
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP error: ${response.code} ${response.message}")
            val body = response.body ?: throw IOException("Empty response body")
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        tempFileChannel?.let {
            it.position(it.size())
        }
    }

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        if (isWrite) {
            val channel = tempFileChannel ?: throw IOException("Write channel closed")
            val destination = ByteBuffer.allocate(size)
            channel.position(position)
            val limit = channel.read(destination)
            if (limit == -1) {
                return ByteBuffer::class.EMPTY
            }
            destination.flip()
            return destination
        }

        val request = GoogleDriveClient.getDownloadRequest(clientEmail, fileId, "bytes=$position-${position + size - 1}")
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 416) {
                return ByteBuffer::class.EMPTY
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP error: ${response.code} ${response.message}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val destination = ByteBuffer.allocate(size)
            val limit = body.byteStream().use {
                it.readFully(destination.array(), destination.arrayOffset(), size)
            }
            destination.limit(limit)
            return destination
        }
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        val channel = tempFileChannel ?: throw IOException("Channel not writable")
        channel.write(source, position)
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        val channel = tempFileChannel ?: throw IOException("Channel not writable")
        channel.truncate(size)
    }

    @Throws(IOException::class)
    override fun onSize(): Long {
        return if (isWrite) {
            tempFileChannel?.size() ?: 0L
        } else {
            initialSize
        }
    }

    @Throws(IOException::class)
    override fun onClose() {
        val channel = tempFileChannel
        val file = tempFile
        if (isWrite && channel != null && file != null) {
            try {
                channel.close()
                if (fileId.isEmpty()) {
                    // Create metadata first
                    fileId = GoogleDriveClient.createFileMetadata(clientEmail, parentId, filename)
                }
                // Upload content to Google Drive
                GoogleDriveClient.uploadFileContent(clientEmail, fileId, file)
            } finally {
                file.delete()
            }
        }
    }
}

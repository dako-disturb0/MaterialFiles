/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.okHttpClient
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.PathListDirectoryStream
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.PathObservableProvider
import me.zhanghai.android.files.provider.common.Searchable
import me.zhanghai.android.files.provider.common.WalkFileTreeSearchable
import me.zhanghai.android.files.provider.common.WatchServicePathObservable
import me.zhanghai.android.files.provider.common.decodedPathByteString
import me.zhanghai.android.files.provider.common.toAccessModes
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.common.toLinkOptions
import me.zhanghai.android.files.provider.common.toOpenOptions
import me.zhanghai.android.files.provider.gdrive.client.Authority
import me.zhanghai.android.files.provider.gdrive.client.GoogleDriveClient
import me.zhanghai.android.files.provider.gdrive.client.GoogleDriveFileByteChannel
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object GoogleDriveFileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
    private val HIDDEN_FILE_NAME_PREFIX = ".".toByteString()

    private val fileSystems = mutableMapOf<Authority, GoogleDriveFileSystem>()
    private val lock = Any()

    private val cacheLocks = ConcurrentHashMap<String, Any>()
    private fun getCacheLock(key: String): Any = cacheLocks.computeIfAbsent(key) { Any() }

    override fun getScheme(): String = "gdrive"

    override fun newFileSystem(uri: URI, env: Map<String, *>) : FileSystem {
        uri.requireSameScheme()
        val authority = uri.gdriveAuthority
        synchronized(lock) {
            if (fileSystems[authority] != null) {
                throw FileSystemAlreadyExistsException(authority.toString())
            }
            return newFileSystemLocked(authority)
        }
    }

    internal fun getOrNewFileSystem(authority: Authority): GoogleDriveFileSystem =
        synchronized(lock) { fileSystems[authority] ?: newFileSystemLocked(authority) }

    private fun newFileSystemLocked(authority: Authority): GoogleDriveFileSystem {
        val fileSystem = GoogleDriveFileSystem(this, authority)
        fileSystems[authority] = fileSystem
        return fileSystem
    }

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val authority = uri.gdriveAuthority
        return synchronized(lock) { fileSystems[authority] }
            ?: throw FileSystemNotFoundException(authority.toString())
    }

    internal fun removeFileSystem(fileSystem: GoogleDriveFileSystem) {
        val authority = fileSystem.authority
        synchronized(lock) { fileSystems.remove(authority) }
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val authority = uri.gdriveAuthority
        val path = uri.decodedPathByteString
            ?: throw IllegalArgumentException("URI must have a path")
        return getOrNewFileSystem(authority).getPath(path)
    }

    private fun URI.requireSameScheme() {
        require(scheme.equals(getScheme(), ignoreCase = true)) {
            "URI scheme $scheme must be ${getScheme()}"
        }
    }

    private val URI.gdriveAuthority: Authority
        get() {
            val email = userInfo ?: host ?: ""
            return Authority(email)
        }

    @Throws(IOException::class)
    fun getKeyInfo(path: GoogleDrivePath): GoogleDriveFileSystem.KeyInfo {
        val fileSystem = path.fileSystem as GoogleDriveFileSystem
        val pathStr = path.toString()
        val cached = fileSystem.getCachedKey(pathStr)
        if (cached != null) {
            return cached
        }

        if (pathStr == "/") {
            val rootId = GoogleDriveClient.authenticator.getRootFolderId(fileSystem.authority)
            val info = GoogleDriveFileSystem.KeyInfo(
                key = rootId,
                isDirectory = true,
                size = 0L,
                lastModified = System.currentTimeMillis(),
                name = ""
            )
            fileSystem.putCachedKey("/", info)
            return info
        }

        val parent = path.parent as GoogleDrivePath?
            ?: throw IOException("Rootless non-root path: $pathStr")
        val parentInfo = getKeyInfo(parent)
        if (!parentInfo.isDirectory) {
            throw NotDirectoryException(parent.toString())
        }

        val content = GoogleDriveClient.getFilesList(fileSystem.authority.clientEmail, parentInfo.key)

        var targetInfo: GoogleDriveFileSystem.KeyInfo? = null
        val parentPathNormalized = if (parent.toString() == "/") "" else parent.toString()

        for (file in content.files) {
            val childPath = "$parentPathNormalized/${file.name}"
            val isDir = file.mimeType == "application/vnd.google-apps.folder"
            val info = GoogleDriveFileSystem.KeyInfo(
                key = file.id,
                isDirectory = isDir,
                size = file.size,
                lastModified = file.lastModified,
                name = file.name
            )
            fileSystem.putCachedKey(childPath, info)
            if (childPath == pathStr) {
                targetInfo = info
            }
        }

        return targetInfo ?: throw NoSuchFileException(pathStr)
    }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        dir as? GoogleDrivePath ?: throw ProviderMismatchException(dir.toString())
        val dirInfo = getKeyInfo(dir)
        if (!dirInfo.isDirectory) {
            throw NotDirectoryException(dir.toString())
        }

        val content = GoogleDriveClient.getFilesList(dir.authority.clientEmail, dirInfo.key)
        val paths = mutableListOf<Path>()
        for (file in content.files) {
            val path = dir.resolve(file.name)
            val isDir = file.mimeType == "application/vnd.google-apps.folder"
            val info = GoogleDriveFileSystem.KeyInfo(
                key = file.id,
                isDirectory = isDir,
                size = file.size,
                lastModified = file.lastModified,
                name = file.name
            )
            (dir.fileSystem as GoogleDriveFileSystem).putCachedKey(path.toString(), info)
            paths.add(path)
        }
        return PathListDirectoryStream(paths, filter)
    }

    @Throws(IOException::class)
    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        dir as? GoogleDrivePath ?: throw ProviderMismatchException(dir.toString())
        val parent = dir.parent as? GoogleDrivePath
            ?: throw IOException("Cannot create directory at root: $dir")
        val parentInfo = getKeyInfo(parent)
        val name = dir.fileName.toString()
        val newKey = GoogleDriveClient.createFolder(dir.authority.clientEmail, parentInfo.key, name)

        val info = GoogleDriveFileSystem.KeyInfo(
            key = newKey,
            isDirectory = true,
            size = 0L,
            lastModified = System.currentTimeMillis(),
            name = name
        )
        (dir.fileSystem as GoogleDriveFileSystem).putCachedKey(dir.toString(), info)
        LocalWatchService.onEntryCreated(dir as ByteStringPath)
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        val info = getKeyInfo(path)
        GoogleDriveClient.deleteFile(path.authority.clientEmail, info.key)
        val fileSystem = path.fileSystem as GoogleDriveFileSystem
        fileSystem.removeCachedKey(path.toString())
        LocalWatchService.onEntryDeleted(path as ByteStringPath)
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        throw UnsupportedOperationException("Copy operation not supported directly by Google Drive API")
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        source as? GoogleDrivePath ?: throw ProviderMismatchException(source.toString())
        target as? GoogleDrivePath ?: throw ProviderMismatchException(target.toString())

        val srcInfo = getKeyInfo(source)
        val targetParent = target.parent as? GoogleDrivePath
            ?: throw IOException("Target cannot be root: $target")
        val targetParentInfo = getKeyInfo(targetParent)

        val srcName = source.fileName.toString()
        val targetName = target.fileName.toString()

        if (source.parent == target.parent) {
            // Rename
            GoogleDriveClient.renameFile(source.authority.clientEmail, srcInfo.key, targetName)
        } else {
            // Move
            val currentParent = source.parent as? GoogleDrivePath
                ?: throw IOException("Source parent cannot be root: $source")
            val currentParentInfo = getKeyInfo(currentParent)
            GoogleDriveClient.moveFile(source.authority.clientEmail, srcInfo.key, currentParentInfo.key, targetParentInfo.key)
            if (srcName != targetName) {
                GoogleDriveClient.renameFile(source.authority.clientEmail, srcInfo.key, targetName)
            }
        }

        val fileSystem = source.fileSystem as GoogleDriveFileSystem
        fileSystem.removeCachedKey(source.toString())

        val newInfo = GoogleDriveFileSystem.KeyInfo(
            key = srcInfo.key,
            isDirectory = srcInfo.isDirectory,
            size = srcInfo.size,
            lastModified = System.currentTimeMillis(),
            name = targetName
        )
        fileSystem.putCachedKey(target.toString(), newInfo)

        LocalWatchService.onEntryDeleted(source as ByteStringPath)
        LocalWatchService.onEntryCreated(target as ByteStringPath)
    }

    @Throws(IOException::class)
    override fun newByteChannel(
        path: Path,
        options: Set<out OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        val openOptions = options.toOpenOptions()

        var info: GoogleDriveFileSystem.KeyInfo? = null
        try {
            info = getKeyInfo(path)
        } catch (e: NoSuchFileException) {
            // Ignore, we might create it
        }

        if (info != null && openOptions.createNew) {
            throw FileAlreadyExistsException(path.toString())
        }
        if (info == null && !openOptions.create && !openOptions.createNew) {
            throw NoSuchFileException(path.toString())
        }

        val isWrite = openOptions.write || openOptions.append
        val parent = path.parent as? GoogleDrivePath
            ?: throw IOException("Cannot write file at root: $path")
        val parentInfo = getKeyInfo(parent)

        if (!isWrite && GoogleDriveClient.authenticator.isViewWithMediaCacheEnabled(path.authority)) {
            val activeInfo = info ?: throw NoSuchFileException(path.toString())
            if (activeInfo.isDirectory) {
                throw IOException("Cannot read directory as channel: $path")
            }

            val cacheDir = application.cacheDir.resolve("gdrive_media_cache").resolve(path.authority.clientEmail)
            cacheDir.mkdirs()
            val cachedFile = cacheDir.resolve("${activeInfo.key}_${activeInfo.lastModified}.bin")

            synchronized(getCacheLock(activeInfo.key)) {
                if (!cachedFile.exists()) {
                    // Clean up old cached versions of this file key
                    cacheDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("${activeInfo.key}_") && file.name.endsWith(".bin")) {
                            file.delete()
                        }
                    }
                    val tempFile = File.createTempFile("gdrive-cache-", null, cacheDir)
                    try {
                        val request = GoogleDriveClient.getDownloadRequest(path.authority.clientEmail, activeInfo.key)
                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP error: ${response.code} ${response.message}")
                            val body = response.body ?: throw IOException("Empty response body")
                            body.byteStream().use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        if (!tempFile.renameTo(cachedFile)) {
                            throw IOException("Failed to rename temp file to $cachedFile")
                        }
                    } catch (e: Exception) {
                        tempFile.delete()
                        throw e
                    }
                }
            }

            return Files.newByteChannel(Paths.get(cachedFile.absolutePath), setOf(StandardOpenOption.READ))
        }

        var targetFileId = info?.key ?: ""
        if (isWrite && targetFileId.isEmpty()) {
            targetFileId = GoogleDriveClient.createFileMetadata(path.authority.clientEmail, parentInfo.key, path.fileName.toString())
            val newInfo = GoogleDriveFileSystem.KeyInfo(
                key = targetFileId,
                isDirectory = false,
                size = 0L,
                lastModified = System.currentTimeMillis(),
                name = path.fileName.toString()
            )
            (path.fileSystem as GoogleDriveFileSystem).putCachedKey(path.toString(), newInfo)
        }

        val channel = GoogleDriveFileByteChannel(
            clientEmail = path.authority.clientEmail,
            fileId = targetFileId,
            parentId = parentInfo.key,
            filename = path.fileName.toString(),
            isWrite = isWrite,
            isAppend = openOptions.append,
            initialSize = info?.size ?: 0L
        )

        if (isWrite) {
            val fileSystem = path.fileSystem as GoogleDriveFileSystem
            fileSystem.removeCachedKey(path.toString())
            LocalWatchService.onEntryCreated(path as ByteStringPath)
        }

        return channel
    }

    override fun getFileStore(path: Path): FileStore {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        val info = getKeyInfo(path)
        val accessModes = modes.toAccessModes()
        if (accessModes.write && isReadOnly(path)) {
            throw IOException("Access denied (write): $path")
        }
    }

    private fun isReadOnly(path: GoogleDrivePath): Boolean = false

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        if (type.isAssignableFrom(GoogleDriveFileAttributeView::class.java)) {
            val noFollowLinks = options.toLinkOptions().noFollowLinks
            @Suppress("UNCHECKED_CAST")
            return GoogleDriveFileAttributeView(path, noFollowLinks) as V
        }
        return null
    }

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        if (type.isAssignableFrom(BasicFileAttributes::class.java)) {
            val view = getFileAttributeView(path, GoogleDriveFileAttributeView::class.java, *options)
                ?: throw UnsupportedOperationException(type.toString())
            @Suppress("UNCHECKED_CAST")
            return view.readAttributes() as A
        }
        throw UnsupportedOperationException(type.toString())
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    override fun isHidden(path: Path): Boolean {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        return path.fileName?.startsWith(HIDDEN_FILE_NAME_PREFIX) ?: false
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        if (path === path2) return true
        if (path !is GoogleDrivePath || path2 !is GoogleDrivePath) return false
        return path == path2
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable {
        path as? GoogleDrivePath ?: throw ProviderMismatchException(path.toString())
        return WatchServicePathObservable(path, intervalMillis)
    }

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        directory as? GoogleDrivePath ?: throw ProviderMismatchException(directory.toString())
        WalkFileTreeSearchable.search(directory, query, intervalMillis, listener)
    }
}

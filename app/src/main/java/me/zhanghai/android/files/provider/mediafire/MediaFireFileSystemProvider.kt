/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mediafire

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
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
import me.zhanghai.android.files.provider.mediafire.client.Authority
import me.zhanghai.android.files.provider.mediafire.client.Client
import me.zhanghai.android.files.provider.mediafire.client.FileByteChannel
import java.io.IOException
import java.net.URI

object MediaFireFileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
    private val HIDDEN_FILE_NAME_PREFIX = ".".toByteString()

    private val fileSystems = mutableMapOf<Authority, MediaFireFileSystem>()
    private val lock = Any()

    override fun getScheme(): String = "mediafire"

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem {
        uri.requireSameScheme()
        val authority = uri.mediaFireAuthority
        synchronized(lock) {
            if (fileSystems[authority] != null) {
                throw FileSystemAlreadyExistsException(authority.toString())
            }
            return newFileSystemLocked(authority)
        }
    }

    internal fun getOrNewFileSystem(authority: Authority): MediaFireFileSystem =
        synchronized(lock) { fileSystems[authority] ?: newFileSystemLocked(authority) }

    private fun newFileSystemLocked(authority: Authority): MediaFireFileSystem {
        val fileSystem = MediaFireFileSystem(this, authority)
        fileSystems[authority] = fileSystem
        return fileSystem
    }

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val authority = uri.mediaFireAuthority
        return synchronized(lock) { fileSystems[authority] }
            ?: throw FileSystemNotFoundException(authority.toString())
    }

    internal fun removeFileSystem(fileSystem: MediaFireFileSystem) {
        val authority = fileSystem.authority
        synchronized(lock) { fileSystems.remove(authority) }
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val authority = uri.mediaFireAuthority
        val path = uri.decodedPathByteString
            ?: throw IllegalArgumentException("URI must have a path")
        return getOrNewFileSystem(authority).getPath(path)
    }

    private fun URI.requireSameScheme() {
        require(scheme.equals(getScheme(), ignoreCase = true)) {
            "URI scheme $scheme must be ${getScheme()}"
        }
    }

    private val URI.mediaFireAuthority: Authority
        get() {
            val email = userInfo ?: host ?: ""
            return Authority(email)
        }

    @Throws(IOException::class)
    fun getKeyInfo(path: MediaFirePath): MediaFireFileSystem.KeyInfo {
        val fileSystem = path.fileSystem as MediaFireFileSystem
        val pathStr = path.toString()
        val cached = fileSystem.getCachedKey(pathStr)
        if (cached != null) {
            return cached
        }

        if (pathStr == "/") {
            val content = Client.getFolderContent(fileSystem.authority, "")
            val info = MediaFireFileSystem.KeyInfo(
                key = content.folderKey,
                isDirectory = true,
                size = 0L,
                lastModified = System.currentTimeMillis(),
                name = ""
            )
            fileSystem.putCachedKey("/", info)
            return info
        }

        val parent = path.parent as MediaFirePath?
            ?: throw IOException("Rootless non-root path: $pathStr")
        val parentInfo = getKeyInfo(parent)
        if (!parentInfo.isDirectory) {
            throw NotDirectoryException(parent.toString())
        }

        val content = Client.getFolderContent(fileSystem.authority, parentInfo.key)

        if (parent.toString() == "/") {
            fileSystem.putCachedKey("/", MediaFireFileSystem.KeyInfo(
                key = content.folderKey,
                isDirectory = true,
                size = 0L,
                lastModified = System.currentTimeMillis(),
                name = ""
            ))
        }

        var targetInfo: MediaFireFileSystem.KeyInfo? = null
        val parentPathNormalized = if (parent.toString() == "/") "" else parent.toString()

        for (folder in content.folders) {
            val childPath = "$parentPathNormalized/${folder.name}"
            val info = MediaFireFileSystem.KeyInfo(
                key = folder.key,
                isDirectory = true,
                size = 0L,
                lastModified = System.currentTimeMillis(),
                name = folder.name
            )
            fileSystem.putCachedKey(childPath, info)
            if (childPath == pathStr) {
                targetInfo = info
            }
        }

        for (file in content.files) {
            val childPath = "$parentPathNormalized/${file.name}"
            val info = MediaFireFileSystem.KeyInfo(
                key = file.key,
                isDirectory = false,
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
        filter: DirectoryStream.Filter<in Path>?
    ): DirectoryStream<Path> {
        dir as? MediaFirePath ?: throw ProviderMismatchException(dir.toString())
        val dirInfo = getKeyInfo(dir)
        if (!dirInfo.isDirectory) {
            throw NotDirectoryException(dir.toString())
        }

        val content = Client.getFolderContent(dir.authority, dirInfo.key)
        val paths = mutableListOf<Path>()
        for (folder in content.folders) {
            val path = dir.resolve(folder.name)
            if (filter == null || filter.accept(path)) {
                paths.add(path)
            }
        }
        for (file in content.files) {
            val path = dir.resolve(file.name)
            if (filter == null || filter.accept(path)) {
                paths.add(path)
            }
        }
        return PathListDirectoryStream(paths)
    }

    @Throws(IOException::class)
    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        dir as? MediaFirePath ?: throw ProviderMismatchException(dir.toString())
        val parent = dir.parent as? MediaFirePath
            ?: throw IOException("Cannot create directory at root: $dir")
        val parentInfo = getKeyInfo(parent)
        val name = dir.fileName.toString()
        val newKey = Client.createFolder(dir.authority, parentInfo.key, name)
        
        val info = MediaFireFileSystem.KeyInfo(
            key = newKey,
            isDirectory = true,
            size = 0L,
            lastModified = System.currentTimeMillis(),
            name = name
        )
        (dir.fileSystem as MediaFireFileSystem).putCachedKey(dir.toString(), info)
        LocalWatchService.onEntryCreated(dir as ByteStringPath<*>)
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        path as? MediaFirePath ?: throw ProviderMismatchException(path.toString())
        val info = getKeyInfo(path)
        if (info.isDirectory) {
            Client.deleteFolder(path.authority, info.key)
        } else {
            Client.deleteFile(path.authority, info.key)
        }
        val fileSystem = path.fileSystem as MediaFireFileSystem
        fileSystem.removeCachedKey(path.toString())
        LocalWatchService.onEntryDeleted(path as ByteStringPath<*>)
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        throw UnsupportedOperationException("Copy operation not supported directly by MediaFire API")
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        source as? MediaFirePath ?: throw ProviderMismatchException(source.toString())
        target as? MediaFirePath ?: throw ProviderMismatchException(target.toString())

        val srcInfo = getKeyInfo(source)
        val targetParent = target.parent as? MediaFirePath
            ?: throw IOException("Target cannot be root: $target")
        val targetParentInfo = getKeyInfo(targetParent)

        val srcName = source.fileName.toString()
        val targetName = target.fileName.toString()

        if (source.parent == target.parent) {
            // Rename
            if (srcInfo.isDirectory) {
                Client.renameFolder(source.authority, srcInfo.key, targetName)
            } else {
                Client.renameFile(source.authority, srcInfo.key, targetName)
            }
        } else {
            // Move
            if (srcInfo.isDirectory) {
                Client.moveFolder(source.authority, srcInfo.key, targetParentInfo.key)
                if (srcName != targetName) {
                    Client.renameFolder(source.authority, srcInfo.key, targetName)
                }
            } else {
                Client.moveFile(source.authority, srcInfo.key, targetParentInfo.key)
                if (srcName != targetName) {
                    Client.renameFile(source.authority, srcInfo.key, targetName)
                }
            }
        }

        val fileSystem = source.fileSystem as MediaFireFileSystem
        fileSystem.removeCachedKey(source.toString())
        
        val newInfo = MediaFireFileSystem.KeyInfo(
            key = srcInfo.key,
            isDirectory = srcInfo.isDirectory,
            size = srcInfo.size,
            lastModified = System.currentTimeMillis(),
            name = targetName
        )
        fileSystem.putCachedKey(target.toString(), newInfo)

        LocalWatchService.onEntryDeleted(source as ByteStringPath<*>)
        LocalWatchService.onEntryCreated(target as ByteStringPath<*>)
    }

    @Throws(IOException::class)
    override fun newByteChannel(
        path: Path,
        options: Set<out OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        path as? MediaFirePath ?: throw ProviderMismatchException(path.toString())
        val openOptions = options.toOpenOptions()

        var info: MediaFireFileSystem.KeyInfo? = null
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
        val parent = path.parent as? MediaFirePath
            ?: throw IOException("Cannot write file at root: $path")
        val parentInfo = getKeyInfo(parent)

        val channel = FileByteChannel(
            authority = path.authority,
            quickKey = info?.key ?: "",
            parentKey = parentInfo.key,
            filename = path.fileName.toString(),
            isWrite = isWrite,
            isAppend = openOptions.append,
            initialSize = info?.size ?: 0L
        )

        if (isWrite) {
            val fileSystem = path.fileSystem as MediaFireFileSystem
            fileSystem.removeCachedKey(path.toString())
            LocalWatchService.onEntryCreated(path as ByteStringPath<*>)
        }

        return channel
    }

    override fun getFileStore(path: Path): FileStore {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path as? MediaFirePath ?: throw ProviderMismatchException(path.toString())
        val info = getKeyInfo(path)
        val accessModes = modes.toAccessModes()
        if (accessModes.write && isReadOnly(path)) {
            throw IOException("Access denied (write): $path")
        }
    }

    private fun isReadOnly(path: MediaFirePath): Boolean = false

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        path as? MediaFirePath ?: throw ProviderMismatchException(path.toString())
        if (type.isAssignableFrom(MediaFireFileAttributeView::class.java)) {
            val noFollowLinks = options.toLinkOptions().noFollowLinks
            @Suppress("UNCHECKED_CAST")
            return MediaFireFileAttributeView(path, noFollowLinks) as V
        }
        return null
    }

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        path as? MediaFirePath ?: throw ProviderMismatchException(path.toString())
        if (type.isAssignableFrom(BasicFileAttributes::class.java)) {
            val view = getFileAttributeView(path, MediaFireFileAttributeView::class.java, *options)
                ?: throw UnsupportedOperationException(type.toString())
            @Suppress("UNCHECKED_CAST")
            return view.readAttributes() as A
        }
        throw UnsupportedOperationException(type.toString())
    }

    override fun isHidden(path: Path): Boolean {
        path as? MediaFirePath ?: throw ProviderMismatchException(path.toString())
        return path.fileName?.startsWith(HIDDEN_FILE_NAME_PREFIX) ?: false
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        if (path === path2) return true
        if (path !is MediaFirePath || path2 !is MediaFirePath) return false
        return path == path2
    }

    override fun observe(path: Path): PathObservable {
        path as? MediaFirePath ?: throw ProviderMismatchException(path.toString())
        return WatchServicePathObservable(path)
    }

    override fun search(directory: Path, query: String, intervalMillis: Long): Searchable.Result {
        directory as? MediaFirePath ?: throw ProviderMismatchException(directory.toString())
        return WalkFileTreeSearchable.search(directory, query, intervalMillis)
    }
}

package me.zhanghai.android.files.provider.ntfs

object NtfsLib {
    init {
        System.loadLibrary("ntfs_wrapper")
    }

    external fun openVolume(fd: Int): Boolean

    external fun listFiles(path: String): Array<String>?

    external fun closeVolume()

    external fun readFile(path: String, offset: Long = 0, size: Long = -1): ByteArray?

    external fun getFileSize(path: String): Long

    external fun isDirectory(path: String): Boolean
}

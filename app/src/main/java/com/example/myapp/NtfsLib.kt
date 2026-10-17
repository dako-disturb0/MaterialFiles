package com.example.myapp

object NtfsLib {
    init {
        System.loadLibrary("ntfs_wrapper")
    }

    external fun openVolume(fd: Int): Boolean
    external fun listFiles(path: String): Array<String>?
    external fun closeVolume()
}

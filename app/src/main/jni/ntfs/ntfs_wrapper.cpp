/*
 * NTFS Driver / JNI Wrapper for Android OTG
 * Based on libntfs-3g
 */

#include <sys/types.h>
#include <sys/stat.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#ifndef __timespec_defined
#define __timespec_defined 1
#endif

#include <jni.h>
#include <android/log.h>

extern "C" {
#include <ntfs-3g/types.h>
#include <ntfs-3g/volume.h>
#include <ntfs-3g/device.h>
#include <ntfs-3g/dir.h>
#include <ntfs-3g/attrib.h>
#include <ntfs-3g/inode.h>
#include <ntfs-3g/unistr.h>
}

#ifdef min
#undef min
#endif
#ifdef max
#undef max
#endif

#include <algorithm>
#include <vector>
#include <string>
#include <cstring>
#include <cstdint>
#include <mutex>

#define TAG "NtfsWrapper"

#pragma pack(push, 1)
struct UsbCbw {
    uint32_t signature; // 0x43425355 ('USBC')
    uint32_t tag;
    uint32_t data_transfer_length;
    uint8_t flags; // 0x80 for input (device to host)
    uint8_t lun;
    uint8_t length; // command length
    uint8_t cdb[16];
};

struct UsbCsw {
    uint32_t signature; // 0x53425355 ('USBS')
    uint32_t tag;
    uint32_t residue;
    uint8_t status;
};
#pragma pack(pop)

struct NtfsFdContext {
    int fd;
    int64_t base_offset;
    bool is_usb_bulk;
    uint8_t ep_in;
    uint8_t ep_out;
    uint32_t tag;
};

static s64 ntfs_usb_scsi_read(NtfsFdContext *ctx, s64 offset, s64 count, void *buf) {
    if (count <= 0) return 0;
    const uint32_t sector_size = 512;
    uint64_t start_lba = static_cast<uint64_t>(offset) / sector_size;
    uint64_t end_lba = static_cast<uint64_t>(offset + count + sector_size - 1) / sector_size;
    uint32_t num_sectors = static_cast<uint32_t>(end_lba - start_lba);
    uint32_t total_bytes = num_sectors * sector_size;

    std::vector<uint8_t> temp_buf(total_bytes);

    const uint32_t max_sectors = 128; // 64 KB chunk
    uint32_t sectors_read = 0;

    while (sectors_read < num_sectors) {
        uint32_t chunk_sectors = std::min(num_sectors - sectors_read, max_sectors);
        uint32_t chunk_bytes = chunk_sectors * sector_size;
        uint64_t cur_lba = start_lba + sectors_read;

        UsbCbw cbw{};
        cbw.signature = 0x43425355;
        cbw.tag = ++ctx->tag;
        cbw.data_transfer_length = chunk_bytes;
        cbw.flags = 0x80; // IN
        cbw.lun = 0;
        cbw.length = 10;
        cbw.cdb[0] = 0x28; // SCSI READ(10)
        cbw.cdb[2] = static_cast<uint8_t>((cur_lba >> 24) & 0xff);
        cbw.cdb[3] = static_cast<uint8_t>((cur_lba >> 16) & 0xff);
        cbw.cdb[4] = static_cast<uint8_t>((cur_lba >> 8) & 0xff);
        cbw.cdb[5] = static_cast<uint8_t>(cur_lba & 0xff);
        cbw.cdb[7] = static_cast<uint8_t>((chunk_sectors >> 8) & 0xff);
        cbw.cdb[8] = static_cast<uint8_t>(chunk_sectors & 0xff);

        usbdevfs_bulktransfer bulk{};
        bulk.ep = ctx->ep_out;
        bulk.len = sizeof(cbw);
        bulk.timeout = 5000;
        bulk.data = &cbw;
        if (ioctl(ctx->fd, USBDEVFS_BULK, &bulk) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "USB CBW bulk transfer failed");
            return -1;
        }

        bulk.ep = ctx->ep_in;
        bulk.len = chunk_bytes;
        bulk.timeout = 5000;
        bulk.data = temp_buf.data() + (sectors_read * sector_size);
        if (ioctl(ctx->fd, USBDEVFS_BULK, &bulk) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "USB data bulk transfer failed");
            return -1;
        }

        UsbCsw csw{};
        bulk.ep = ctx->ep_in;
        bulk.len = sizeof(csw);
        bulk.timeout = 5000;
        bulk.data = &csw;
        if (ioctl(ctx->fd, USBDEVFS_BULK, &bulk) < 0 || csw.status != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "USB CSW failed or non-zero status: %d", csw.status);
            return -1;
        }

        sectors_read += chunk_sectors;
    }

    uint64_t offset_in_first_sector = static_cast<uint64_t>(offset) % sector_size;
    memcpy(buf, temp_buf.data() + offset_in_first_sector, static_cast<size_t>(count));
    return count;
}

static int ntfs_fd_open(struct ntfs_device *dev, int flags) {
    if (NDevOpen(dev)) {
        errno = EBUSY;
        return -1;
    }
    NDevSetOpen(dev);
    if ((flags & O_RDWR) != O_RDWR) {
        NDevSetReadOnly(dev);
    }
    return 0;
}

static int ntfs_fd_close(struct ntfs_device *dev) {
    if (!NDevOpen(dev)) {
        return 0;
    }
    NDevClearOpen(dev);
    return 0;
}

static s64 ntfs_fd_seek(struct ntfs_device *dev, s64 offset, int whence) {
    auto *ctx = static_cast<NtfsFdContext *>(dev->d_private);
    if (!ctx) return -1;
    if (whence == SEEK_SET) {
        return lseek64(ctx->fd, ctx->base_offset + offset, SEEK_SET) - ctx->base_offset;
    } else if (whence == SEEK_CUR) {
        return lseek64(ctx->fd, offset, SEEK_CUR) - ctx->base_offset;
    } else if (whence == SEEK_END) {
        return lseek64(ctx->fd, offset, SEEK_END) - ctx->base_offset;
    }
    return -1;
}

static s64 ntfs_fd_pread(struct ntfs_device *dev, void *buf, s64 count, s64 offset) {
    auto *ctx = static_cast<NtfsFdContext *>(dev->d_private);
    if (!ctx) return -1;
    if (ctx->is_usb_bulk) {
        return ntfs_usb_scsi_read(ctx, ctx->base_offset + offset, count, buf);
    }
    return pread64(ctx->fd, buf, static_cast<size_t>(count), ctx->base_offset + offset);
}

static s64 ntfs_fd_read(struct ntfs_device *dev, void *buf, s64 count) {
    auto *ctx = static_cast<NtfsFdContext *>(dev->d_private);
    if (!ctx) return -1;
    s64 cur = lseek64(ctx->fd, 0, SEEK_CUR);
    if (cur < 0) cur = ctx->base_offset;
    s64 ret = ntfs_fd_pread(dev, buf, count, cur - ctx->base_offset);
    if (ret > 0) {
        lseek64(ctx->fd, cur + ret, SEEK_SET);
    }
    return ret;
}

static s64 ntfs_fd_write(struct ntfs_device *, const void *, s64) {
    errno = EROFS;
    return -1;
}

static s64 ntfs_fd_pwrite(struct ntfs_device *, const void *, s64, s64) {
    errno = EROFS;
    return -1;
}

static int ntfs_fd_sync(struct ntfs_device *dev) {
    auto *ctx = static_cast<NtfsFdContext *>(dev->d_private);
    if (ctx && ctx->fd >= 0 && !ctx->is_usb_bulk) {
        return fsync(ctx->fd);
    }
    return 0;
}

static int ntfs_fd_stat(struct ntfs_device *dev, struct stat *buf) {
    auto *ctx = static_cast<NtfsFdContext *>(dev->d_private);
    if (!ctx) return -1;
    int res = fstat(ctx->fd, buf);
    if (res == 0 && ctx->is_usb_bulk) {
        buf->st_mode = S_IFBLK | 0660;
    }
    return res;
}

static int ntfs_fd_ioctl(struct ntfs_device *dev, unsigned long request, void *argp) {
    auto *ctx = static_cast<NtfsFdContext *>(dev->d_private);
    if (!ctx) return -1;
    return ioctl(ctx->fd, request, argp);
}

static struct ntfs_device_operations g_ntfs_fd_ops = {
    .open = ntfs_fd_open,
    .close = ntfs_fd_close,
    .seek = ntfs_fd_seek,
    .read = ntfs_fd_read,
    .write = ntfs_fd_write,
    .pread = ntfs_fd_pread,
    .pwrite = ntfs_fd_pwrite,
    .sync = ntfs_fd_sync,
    .stat = ntfs_fd_stat,
    .ioctl = ntfs_fd_ioctl,
};

static bool is_ntfs_magic(const uint8_t *sector) {
    return memcmp(sector + 3, "NTFS    ", 8) == 0;
}

static int64_t find_ntfs_partition_offset(NtfsFdContext *ctx) {
    uint8_t sector[512];
    s64 n;
    if (ctx->is_usb_bulk) {
        n = ntfs_usb_scsi_read(ctx, 0, 512, sector);
    } else {
        n = pread64(ctx->fd, sector, 512, 0);
    }
    if (n != 512) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Unable to read sector 0, fallback offset 0");
        return 0;
    }

    if (is_ntfs_magic(sector)) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Sector 0 is NTFS boot sector");
        return 0;
    }

    // Check MBR partition table (0x55AA signature at offset 510)
    if (sector[510] == 0x55 && sector[511] == 0xAA) {
        for (int i = 0; i < 4; ++i) {
            const uint8_t *entry = sector + 446 + (i * 16);
            uint32_t start_lba = entry[8] | (entry[9] << 8) | (entry[10] << 16) | (entry[11] << 24);
            if (start_lba > 0) {
                int64_t cand_offset = static_cast<int64_t>(start_lba) * 512;
                uint8_t cand_sector[512];
                s64 cn = ctx->is_usb_bulk ?
                    ntfs_usb_scsi_read(ctx, cand_offset, 512, cand_sector) :
                    pread64(ctx->fd, cand_sector, 512, cand_offset);
                if (cn == 512 && is_ntfs_magic(cand_sector)) {
                    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Found NTFS partition in MBR entry %d at LBA %u (offset %lld)",
                        i, start_lba, static_cast<long long>(cand_offset));
                    return cand_offset;
                }
            }
        }
    }

    // Check common 1MB alignment (LBA 2048)
    int64_t align_1mb = 2048LL * 512LL;
    uint8_t cand_sector[512];
    s64 cn = ctx->is_usb_bulk ?
        ntfs_usb_scsi_read(ctx, align_1mb, 512, cand_sector) :
        pread64(ctx->fd, cand_sector, 512, align_1mb);
    if (cn == 512 && is_ntfs_magic(cand_sector)) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Found NTFS partition at 1MB (LBA 2048)");
        return align_1mb;
    }

    return 0;
}

static bool init_usb_context(NtfsFdContext *ctx) {
    struct stat st;
    if (fstat(ctx->fd, &st) == 0 && S_ISCHR(st.st_mode)) {
        ctx->is_usb_bulk = true;
        uint8_t desc_buf[2048];
        lseek(ctx->fd, 0, SEEK_SET);
        ssize_t desc_len = read(ctx->fd, desc_buf, sizeof(desc_buf));
        if (desc_len > 0) {
            ssize_t idx = 0;
            int cur_interface = -1;
            bool is_mass_storage = false;
            while (idx + 2 <= desc_len) {
                uint8_t len = desc_buf[idx];
                uint8_t type = desc_buf[idx + 1];
                if (len < 2 || idx + len > desc_len) break;

                if (type == 0x04 && len >= 9) { // INTERFACE descriptor
                    cur_interface = desc_buf[idx + 2];
                    uint8_t if_class = desc_buf[idx + 5];
                    is_mass_storage = (if_class == 8); // USB Mass Storage
                } else if (type == 0x05 && len >= 7 && is_mass_storage) { // ENDPOINT descriptor
                    uint8_t ep_addr = desc_buf[idx + 2];
                    uint8_t ep_attr = desc_buf[idx + 3];
                    if ((ep_attr & 0x03) == 0x02) { // Bulk endpoint
                        if (ep_addr & 0x80) {
                            ctx->ep_in = ep_addr;
                        } else {
                            ctx->ep_out = ep_addr;
                        }
                    }
                }
                idx += len;
            }

            if (cur_interface >= 0) {
                ioctl(ctx->fd, USBDEVFS_CLAIMINTERFACE, &cur_interface);
            }
        }
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "USB BOT initialized: ep_in=0x%02x, ep_out=0x%02x", ctx->ep_in, ctx->ep_out);
        return true;
    }
    return false;
}

static ntfs_volume *g_vol = nullptr;
static NtfsFdContext *g_ctx = nullptr;
static std::mutex g_ntfs_mutex;

static jboolean openVolumeInternal(JNIEnv *, jint fd) {
    std::lock_guard<std::mutex> lock(g_ntfs_mutex);
    if (g_vol) {
        ntfs_umount(g_vol, FALSE);
        g_vol = nullptr;
        if (g_ctx) {
            delete g_ctx;
            g_ctx = nullptr;
        }
    }

    auto *ctx = new NtfsFdContext();
    ctx->fd = fd;
    ctx->base_offset = 0;
    ctx->is_usb_bulk = false;
    ctx->ep_in = 0x81;
    ctx->ep_out = 0x02;
    ctx->tag = 0;

    init_usb_context(ctx);
    ctx->base_offset = find_ntfs_partition_offset(ctx);

    char dev_name[64];
    snprintf(dev_name, sizeof(dev_name), "ntfs_fd_%d", fd);

    struct ntfs_device *dev = ntfs_device_alloc(dev_name, 0, &g_ntfs_fd_ops, ctx);
    if (!dev) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to allocate ntfs_device");
        delete ctx;
        return JNI_FALSE;
    }

    g_vol = ntfs_device_mount(dev, NTFS_MNT_RDONLY);
    if (!g_vol) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Gagal mount NTFS volume: %s", strerror(errno));
        ntfs_device_free(dev);
        delete ctx;
        return JNI_FALSE;
    }

    g_ctx = ctx;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Volume NTFS berhasil dibuka");
    return JNI_TRUE;
}

struct DirectoryContext {
    std::vector<std::string> *files;
};

static int filldir_callback(void *dirent, const ntfschar *name,
        const int name_len, const int, const s64,
        const MFT_REF, const unsigned) {
    auto *ctx = static_cast<DirectoryContext *>(dirent);
    char *utf8_name = nullptr;
    if (ntfs_ucstombs(name, name_len, &utf8_name, 0) > 0 && utf8_name) {
        if (strcmp(utf8_name, ".") != 0 && strcmp(utf8_name, "..") != 0 &&
            strcmp(utf8_name, "$RECYCLE.BIN") != 0 && strcmp(utf8_name, "System Volume Information") != 0) {
            ctx->files->push_back(std::string(utf8_name));
        }
        free(utf8_name);
    }
    return 0;
}

static jobjectArray listFilesInternal(JNIEnv *env, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_ntfs_mutex);
    if (!g_vol) return nullptr;

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    ntfs_inode *dir = ntfs_pathname_to_inode(g_vol, NULL, path);
    env->ReleaseStringUTFChars(jpath, path);

    if (!dir) return nullptr;

    std::vector<std::string> files;
    DirectoryContext dctx{&files};
    s64 pos = 0;
    ntfs_readdir(dir, &pos, &dctx, filldir_callback);
    ntfs_inode_close(dir);

    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(files.size()), strClass, nullptr);
    for (size_t i = 0; i < files.size(); ++i) {
        jstring js = env->NewStringUTF(files[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return result;
}

static void closeVolumeInternal(JNIEnv *) {
    std::lock_guard<std::mutex> lock(g_ntfs_mutex);
    if (g_vol) {
        ntfs_umount(g_vol, FALSE);
        g_vol = nullptr;
    }
    if (g_ctx) {
        delete g_ctx;
        g_ctx = nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Volume NTFS ditutup");
}

static jbyteArray readFileInternal(JNIEnv *env, jstring jpath, jlong offset, jlong size) {
    std::lock_guard<std::mutex> lock(g_ntfs_mutex);
    if (!g_vol) return nullptr;

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    ntfs_inode *ni = ntfs_pathname_to_inode(g_vol, NULL, path);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ni) return nullptr;

    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        ntfs_inode_close(ni);
        return nullptr;
    }

    s64 available = na->data_size - offset;
    if (available < 0) available = 0;
    s64 to_read = (size > 0 && size < available) ? size : available;

    jbyteArray jbuf = env->NewByteArray(static_cast<jsize>(to_read));
    if (to_read > 0) {
        std::vector<uint8_t> buffer(to_read);
        s64 bytes_read = ntfs_attr_pread(na, offset, to_read, buffer.data());
        if (bytes_read > 0) {
            env->SetByteArrayRegion(jbuf, 0, static_cast<jsize>(bytes_read),
                                    reinterpret_cast<const jbyte *>(buffer.data()));
        }
    }

    ntfs_attr_close(na);
    ntfs_inode_close(ni);
    return jbuf;
}

static jlong getFileSizeInternal(JNIEnv *env, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_ntfs_mutex);
    if (!g_vol) return -1;

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    ntfs_inode *ni = ntfs_pathname_to_inode(g_vol, NULL, path);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ni) return -1;

    s64 size = ni->data_size;
    ntfs_inode_close(ni);
    return size;
}

static jboolean isDirectoryInternal(JNIEnv *env, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_ntfs_mutex);
    if (!g_vol) return JNI_FALSE;

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    ntfs_inode *ni = ntfs_pathname_to_inode(g_vol, NULL, path);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ni) return JNI_FALSE;

    jboolean is_dir = (ni->mrec && (le16_to_cpu(ni->mrec->flags) & MFT_RECORD_IS_DIRECTORY)) ? JNI_TRUE : JNI_FALSE;
    ntfs_inode_close(ni);
    return is_dir;
}

extern "C" {

// Package: me.zhanghai.android.files.provider.ntfs
JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_provider_ntfs_NtfsLib_openVolume(JNIEnv *env, jobject, jint fd) {
    return openVolumeInternal(env, fd);
}

JNIEXPORT jobjectArray JNICALL
Java_me_zhanghai_android_files_provider_ntfs_NtfsLib_listFiles(JNIEnv *env, jobject, jstring jpath) {
    return listFilesInternal(env, jpath);
}

JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_provider_ntfs_NtfsLib_closeVolume(JNIEnv *env, jobject) {
    closeVolumeInternal(env);
}

JNIEXPORT jbyteArray JNICALL
Java_me_zhanghai_android_files_provider_ntfs_NtfsLib_readFile(JNIEnv *env, jobject, jstring jpath, jlong offset, jlong size) {
    return readFileInternal(env, jpath, offset, size);
}

JNIEXPORT jlong JNICALL
Java_me_zhanghai_android_files_provider_ntfs_NtfsLib_getFileSize(JNIEnv *env, jobject, jstring jpath) {
    return getFileSizeInternal(env, jpath);
}

JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_provider_ntfs_NtfsLib_isDirectory(JNIEnv *env, jobject, jstring jpath) {
    return isDirectoryInternal(env, jpath);
}

// Compatibility for package com.example.myapp from guide
JNIEXPORT jboolean JNICALL
Java_com_example_myapp_NtfsLib_openVolume(JNIEnv *env, jobject, jint fd) {
    return openVolumeInternal(env, fd);
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_myapp_NtfsLib_listFiles(JNIEnv *env, jobject, jstring jpath) {
    return listFilesInternal(env, jpath);
}

JNIEXPORT void JNICALL
Java_com_example_myapp_NtfsLib_closeVolume(JNIEnv *env, jobject) {
    closeVolumeInternal(env);
}

} // extern "C"

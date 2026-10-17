# Panduan: NTFS OTG Reader Android (NDK + libntfs-3g)

---

## 1. Persiapan Environment

- Android Studio (versi terbaru)
- NDK — download via **SDK Manager → NDK (Side by side)**
- CMake
- OS: Linux/Mac lebih mudah; Windows gunakan **WSL2**

---

## 2. Compile libntfs-3g untuk Android

### Download source

```bash
git clone https://github.com/tuxera/ntfs-3g
cd ntfs-3g
```

### Cross-compile dengan NDK toolchain

```bash
export NDK=/path/to/your/ndk
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64

./configure \
  --host=aarch64-linux-android \
  CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang \
  --disable-fuse \
  --disable-ntfsprogs \
  --prefix=$(pwd)/output

make && make install
```

> **Hasil:** `libntfs-3g.a` dan header di folder `output/lib/` dan `output/include/`

---

## 3. Struktur Proyek Android

```
app/
└── src/main/
    ├── cpp/
    │   ├── CMakeLists.txt
    │   ├── ntfs_wrapper.cpp        ← JNI wrapper
    │   └── libs/
    │       ├── libntfs-3g.a        ← hasil compile
    │       └── include/            ← header ntfs-3g
    ├── java/com/example/myapp/
    │   ├── NtfsLib.kt              ← deklarasi native method
    │   └── UsbHelper.kt            ← handle USB OTG
    └── AndroidManifest.xml
```

---

## 4. CMakeLists.txt

```cmake
cmake_minimum_required(VERSION 3.22)
project(ntfs_reader)

# Library prebuilt ntfs-3g
add_library(ntfs-3g STATIC IMPORTED)
set_target_properties(ntfs-3g PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libs/libntfs-3g.a
)

# Shared library JNI wrapper
add_library(ntfs_wrapper SHARED
    ntfs_wrapper.cpp
)

target_include_directories(ntfs_wrapper PRIVATE
    ${CMAKE_SOURCE_DIR}/libs/include
)

target_link_libraries(ntfs_wrapper
    ntfs-3g
    log
)
```

---

## 5. JNI Wrapper — `ntfs_wrapper.cpp`

```cpp
#include <jni.h>
#include <ntfs-3g/ntfs.h>
#include <android/log.h>
#include <vector>
#include <string>

#define TAG "NtfsWrapper"

// Simpan pointer volume secara global (sederhana)
static ntfs_volume *g_vol = nullptr;

extern "C" {

// Buka volume NTFS dari file descriptor USB
JNIEXPORT jboolean JNICALL
Java_com_example_myapp_NtfsLib_openVolume(JNIEnv *env, jobject, jint fd) {
    g_vol = ntfs_device_mount(fd, NTFS_MNT_RDONLY);
    if (!g_vol) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Gagal mount NTFS volume");
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Volume NTFS berhasil dibuka");
    return JNI_TRUE;
}

// List file dalam direktori
JNIEXPORT jobjectArray JNICALL
Java_com_example_myapp_NtfsLib_listFiles(JNIEnv *env, jobject, jstring jpath) {
    if (!g_vol) return nullptr;

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    ntfs_inode *dir = ntfs_pathname_to_inode(g_vol, NULL, path);
    env->ReleaseStringUTFChars(jpath, path);

    if (!dir) return nullptr;

    // Kumpulkan nama file (implementasi iterasi direktori)
    std::vector<std::string> files;
    // ... (gunakan ntfs_readdir atau manual inode traversal)

    // Konversi ke Java String array
    jobjectArray result = env->NewObjectArray(
        files.size(),
        env->FindClass("java/lang/String"),
        env->NewStringUTF("")
    );
    for (int i = 0; i < (int)files.size(); i++) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(files[i].c_str()));
    }

    ntfs_inode_close(dir);
    return result;
}

// Tutup volume saat selesai
JNIEXPORT void JNICALL
Java_com_example_myapp_NtfsLib_closeVolume(JNIEnv *env, jobject) {
    if (g_vol) {
        ntfs_umount(g_vol, FALSE);
        g_vol = nullptr;
    }
}

} // extern "C"
```

---

## 6. Kotlin — Deklarasi JNI (`NtfsLib.kt`)

```kotlin
object NtfsLib {
    init {
        System.loadLibrary("ntfs_wrapper")
    }

    external fun openVolume(fd: Int): Boolean
    external fun listFiles(path: String): Array<String>?
    external fun closeVolume()
}
```

---

## 7. Kotlin — Handle USB OTG (`UsbHelper.kt`)

```kotlin
import android.hardware.usb.*
import android.content.Context

class UsbHelper(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun getUsbFileDescriptor(device: UsbDevice): Int? {
        val connection = usbManager.openDevice(device) ?: return null
        return connection.fileDescriptor  // FD ini dikirim ke JNI
    }

    fun listUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }
}
```

---

## 8. AndroidManifest.xml — Permission USB

```xml
<manifest ...>
    <uses-feature android:name="android.hardware.usb.host" />

    <application ...>
        <activity ...>
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>
    </application>
</manifest>
```

### `res/xml/device_filter.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Match semua USB Mass Storage device -->
    <usb-device class="8" subclass="6" protocol="80" />
</resources>
```

---

## 9. Contoh Penggunaan di Activity

```kotlin
val usbHelper = UsbHelper(this)
val device = usbHelper.listUsbDevices().firstOrNull() ?: return

val fd = usbHelper.getUsbFileDescriptor(device) ?: return

if (NtfsLib.openVolume(fd)) {
    val files = NtfsLib.listFiles("/")
    files?.forEach { println(it) }
    NtfsLib.closeVolume()
} else {
    println("Gagal buka volume NTFS")
}
```

---

## Catatan Penting

| Hal | Keterangan |
|-----|-----------|
| ABI | Compile untuk `arm64-v8a` (modern) dan `armeabi-v7a` (lama) |
| API Level | Minimal Android 5.0 (API 21) untuk USB Host stabil |
| Permission | Minta permission USB ke user sebelum buka device |
| Read-only | libntfs-3g bisa write juga, tapi mulai dari read dulu untuk keamanan |
| FUSE | Tidak diperlukan sama sekali untuk use case ini |

---

## Referensi

- [ntfs-3g source (Tuxera)](https://github.com/tuxera/ntfs-3g)
- [Android USB Host API](https://developer.android.com/guide/topics/connectivity/usb/host)
- [Android NDK CMake Guide](https://developer.android.com/ndk/guides/cmake)
- [android-ntfs-3g contoh compile](https://github.com/Zapan9966/android-ntfs-3g)

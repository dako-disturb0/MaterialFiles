cmake_minimum_required(VERSION 3.6)

project(MaterialFiles C CXX)

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY})

# Prebuilt static libntfs-3g
add_library(ntfs-3g STATIC IMPORTED)
set_target_properties(ntfs-3g PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/ntfs/libs/${ANDROID_ABI}/libntfs-3g.a)

# Shared library JNI wrapper
add_library(ntfs_wrapper SHARED
        src/main/jni/ntfs/ntfs_wrapper.cpp)

target_include_directories(ntfs_wrapper PRIVATE
        src/main/jni/ntfs/include
        src/main/jni/ntfs/include/ntfs-3g)

target_compile_options(ntfs_wrapper PRIVATE
        -Wall)

target_link_libraries(ntfs_wrapper
        PRIVATE
        ntfs-3g
        ${LOG_LIBRARY})

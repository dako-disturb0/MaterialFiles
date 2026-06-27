/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mediafire.client

import android.os.SystemClock
import me.zhanghai.android.files.app.okHttpClient
import me.zhanghai.android.files.storage.MediaFireServerAuthenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class SessionExpiredException(message: String) : IOException(message)

object Client {
    private const val BASE_URL = "https://www.mediafire.com/api/1.4/"

    private val sessionTokens = ConcurrentHashMap<Authority, String>()

    private fun String.sha1(): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(this.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Throws(IOException::class)
    private fun fetchSessionToken(auth: Authentication): String {
        val signatureStr = auth.email + auth.password + auth.appId + auth.apiKey
        val signature = signatureStr.sha1()

        val url = (BASE_URL + "user/get_session_token.php").toHttpUrl().newBuilder()
            .addQueryParameter("email", auth.email)
            .addQueryParameter("password", auth.password)
            .addQueryParameter("application_id", auth.appId)
            .addQueryParameter("signature", signature)
            .addQueryParameter("token_version", "1")
            .addQueryParameter("response_format", "json")
            .build()

        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP error: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response body")
            val json = JSONObject(body)
            val responseObj = json.getJSONObject("response")
            val result = responseObj.optString("result")
            if (result != "Success") {
                val errorCode = responseObj.optInt("error")
                val msg = responseObj.optString("message")
                throw IOException("Login failed ($errorCode): $msg")
            }
            return responseObj.getString("session_token")
        }
    }

    @Volatile
    lateinit var authenticator: Authenticator

    @Throws(IOException::class)
    fun getSessionToken(authority: Authority): String {
        return sessionTokens.computeIfAbsent(authority) {
            val auth = authenticator.getAuthentication(it)
                ?: throw IOException("No authentication credentials found for $it")
            try {
                fetchSessionToken(auth)
            } catch (e: Exception) {
                throw IOException("Authentication failed: ${e.message}", e)
            }
        }
    }

    @Throws(IOException::class)
    fun <T> executeWithRetry(authority: Authority, block: (sessionToken: String) -> T): T {
        var token = getSessionToken(authority)
        try {
            return block(token)
        } catch (e: SessionExpiredException) {
            sessionTokens.remove(authority)
            token = getSessionToken(authority)
            return block(token)
        }
    }

    @Throws(IOException::class)
    private fun checkResponse(bodyString: String): JSONObject {
        val json = JSONObject(bodyString)
        val responseObj = json.getJSONObject("response")
        val result = responseObj.optString("result")
        if (result != "Success") {
            val errorCode = responseObj.optInt("error")
            val message = responseObj.optString("message")
            if (errorCode == 99 || errorCode == 100 || errorCode == 101 || errorCode == 102 ||
                message.contains("session", ignoreCase = true) || message.contains("token", ignoreCase = true)
            ) {
                throw SessionExpiredException(message)
            }
            throw IOException("MediaFire API error ($errorCode): $message")
        }
        return responseObj
    }

    @Throws(IOException::class)
    fun getFolderContent(authority: Authority, folderKey: String): FolderContent {
        return executeWithRetry(authority) { token ->
            val url = (BASE_URL + "folder/get_content.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("content_type", "all")
                .addQueryParameter("response_format", "json")
                .apply {
                    if (folderKey.isNotEmpty()) {
                        addQueryParameter("folder_key", folderKey)
                    }
                }
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                val responseObj = checkResponse(body)
                val folderContentObj = responseObj.getJSONObject("folder_content")
                
                val foldersList = mutableListOf<MediaFireFolder>()
                val foldersArray = folderContentObj.optJSONArray("folders")
                if (foldersArray != null) {
                    for (i in 0 until foldersArray.length()) {
                        val f = foldersArray.getJSONObject(i)
                        foldersList.add(
                            MediaFireFolder(
                                key = f.getString("folderkey"),
                                name = f.getString("name")
                            )
                        )
                    }
                }

                val filesList = mutableListOf<MediaFireFile>()
                val filesArray = folderContentObj.optJSONArray("files")
                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val f = filesArray.getJSONObject(i)
                        filesList.add(
                            MediaFireFile(
                                key = f.getString("quickkey"),
                                name = f.getString("filename"),
                                size = f.optLong("size", 0),
                                lastModified = parseCreatedDate(f.optString("created"))
                            )
                        )
                    }
                }

                FolderContent(
                    folderKey = folderContentObj.optString("folder_key"),
                    folders = foldersList,
                    files = filesList
                )
            }
        }
    }

    private fun parseCreatedDate(createdStr: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            sdf.parse(createdStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    @Throws(IOException::class)
    fun createFolder(authority: Authority, parentKey: String, folderName: String): String {
        return executeWithRetry(authority) { token ->
            val url = (BASE_URL + "folder/create.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("foldername", folderName)
                .addQueryParameter("parent_key", parentKey)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                val responseObj = checkResponse(body)
                responseObj.getString("folder_key")
            }
        }
    }

    @Throws(IOException::class)
    fun deleteFolder(authority: Authority, folderKey: String) {
        executeWithRetry(authority) { token ->
            val url = (BASE_URL + "folder/delete.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("folder_key", folderKey)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                checkResponse(body)
            }
        }
    }

    @Throws(IOException::class)
    fun deleteFile(authority: Authority, quickKey: String) {
        executeWithRetry(authority) { token ->
            val url = (BASE_URL + "file/delete.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("quick_key", quickKey)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                checkResponse(body)
            }
        }
    }

    @Throws(IOException::class)
    fun moveFolder(authority: Authority, folderKey: String, destinationFolderKey: String) {
        executeWithRetry(authority) { token ->
            val url = (BASE_URL + "folder/move.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("folder_key", folderKey)
                .addQueryParameter("destination_folder_key", destinationFolderKey)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                checkResponse(body)
            }
        }
    }

    @Throws(IOException::class)
    fun moveFile(authority: Authority, quickKey: String, destinationFolderKey: String) {
        executeWithRetry(authority) { token ->
            val url = (BASE_URL + "file/move.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("quick_key", quickKey)
                .addQueryParameter("destination_folder_key", destinationFolderKey)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                checkResponse(body)
            }
        }
    }

    @Throws(IOException::class)
    fun renameFolder(authority: Authority, folderKey: String, newName: String) {
        executeWithRetry(authority) { token ->
            val url = (BASE_URL + "folder/update.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("folder_key", folderKey)
                .addQueryParameter("foldername", newName)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                checkResponse(body)
            }
        }
    }

    @Throws(IOException::class)
    fun renameFile(authority: Authority, quickKey: String, newName: String) {
        executeWithRetry(authority) { token ->
            val url = (BASE_URL + "file/update.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("quick_key", quickKey)
                .addQueryParameter("filename", newName)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                checkResponse(body)
            }
        }
    }

    @Throws(IOException::class)
    fun getDownloadUrl(authority: Authority, quickKey: String): String {
        return executeWithRetry(authority) { token ->
            val url = (BASE_URL + "file/get_info.php").toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("quick_key", quickKey)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                val responseObj = checkResponse(body)
                val fileInfo = responseObj.getJSONObject("file_info")
                val links = fileInfo.optJSONObject("links")
                if (links != null) {
                    val normalDownload = links.optString("normal_download")
                    if (normalDownload.isNotEmpty()) return@executeWithRetry normalDownload
                }
                fileInfo.optString("direct_download").takeIf { it.isNotEmpty() }
                    ?: fileInfo.getString("normal_download")
            }
        }
    }

    @Throws(IOException::class)
    fun uploadFile(authority: Authority, parentKey: String, filename: String, file: File): String {
        return executeWithRetry(authority) { token ->
            val uploadUrl = "https://www.mediafire.com/api/1.4/upload/simple.php"
                .toHttpUrl().newBuilder()
                .addQueryParameter("session_token", token)
                .addQueryParameter("action_on_duplicate", "overwrite")
                .addQueryParameter("response_format", "json")
                .build()

            val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .header("x-filename", filename)
                .header("x-filesize", file.length().toString())
                .header("x-to-folder-key", parentKey)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP error: ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                val responseObj = checkResponse(body)
                val uploadInfo = responseObj.getJSONObject("upload")
                val uploadKey = uploadInfo.getString("key")
                
                pollUpload(authority, uploadKey)
            }
        }
    }

    @Throws(IOException::class)
    private fun pollUpload(authority: Authority, uploadKey: String): String {
        var retries = 0
        while (retries < 60) {
            SystemClock.sleep(1000)
            val url = (BASE_URL + "upload/poll_upload.php").toHttpUrl().newBuilder()
                .addQueryParameter("key", uploadKey)
                .addQueryParameter("response_format", "json")
                .build()

            val request = Request.Builder().url(url).build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            val responseObj = json.getJSONObject("response")
                            val result = responseObj.optString("result")
                            if (result == "Success") {
                                val status = responseObj.optInt("status")
                                val fileCode = responseObj.optInt("fileerror")
                                if (fileCode != 0) {
                                    throw IOException("Upload poll error code: $fileCode")
                                }
                                if (status == 99) {
                                    return responseObj.getString("quickkey")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore transient errors and keep polling
            }
            retries++
        }
        throw IOException("Upload polling timed out")
    }
}

data class MediaFireFolder(
    val key: String,
    val name: String
)

data class MediaFireFile(
    val key: String,
    val name: String,
    val size: Long,
    val lastModified: Long
)

data class FolderContent(
    val folderKey: String,
    val folders: List<MediaFireFolder>,
    val files: List<MediaFireFile>
)

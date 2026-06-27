/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.gdrive.client

import android.util.Base64
import me.zhanghai.android.files.app.okHttpClient
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ServiceAccountConfig(
    val privateKeyPem: String,
    val clientEmail: String,
    val tokenUri: String
) {
    companion object {
        fun fromJson(jsonStr: String): ServiceAccountConfig {
            val json = JSONObject(jsonStr)
            val privateKey = json.getString("private_key")
            val clientEmail = json.getString("client_email")
            val tokenUri = json.optString("token_uri", "https://oauth2.googleapis.com/token")
            return ServiceAccountConfig(privateKey, clientEmail, tokenUri)
        }
    }
}

object JwtSigner {
    fun parsePrivateKey(pem: String): PrivateKey {
        val cleanPem = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val decoded = Base64.decode(cleanPem, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(decoded)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(spec)
    }

    fun signJwt(clientEmail: String, tokenUri: String, privateKey: PrivateKey): String {
        val iat = System.currentTimeMillis() / 1000
        val exp = iat + 3600

        val header = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }.toString()

        val payload = JSONObject().apply {
            put("iss", clientEmail)
            put("scope", "https://www.googleapis.com/auth/drive")
            put("aud", tokenUri)
            put("exp", exp)
            put("iat", iat)
        }.toString()

        val headerBase64 = Base64.encodeToString(header.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payloadBase64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val signingInput = "$headerBase64.$payloadBase64"
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(signingInput.toByteArray(Charsets.US_ASCII))
        val signatureBytes = signature.sign()
        val signatureBase64 = Base64.encodeToString(signatureBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        return "$signingInput.$signatureBase64"
    }
}

data class GoogleDriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long
)

data class FolderContent(
    val files: List<GoogleDriveFile>
)

object GoogleDriveClient {
    lateinit var authenticator: Authenticator

    private val tokenCache = ConcurrentHashMap<String, TokenInfo>()

    data class TokenInfo(
        val accessToken: String,
        val expiryTimeMillis: Long
    )

    private fun getAccessToken(clientEmail: String): String {
        val jsonStr = authenticator.getServiceAccountJson(Authority(clientEmail))
            ?: throw IOException("No service account credentials found for $clientEmail")
        val config = ServiceAccountConfig.fromJson(jsonStr)

        val cached = tokenCache[config.clientEmail]
        if (cached != null && cached.expiryTimeMillis > System.currentTimeMillis() + 300_000) {
            return cached.accessToken
        }

        val privateKey = JwtSigner.parsePrivateKey(config.privateKeyPem)
        val jwt = JwtSigner.signJwt(config.clientEmail, config.tokenUri, privateKey)

        val requestBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()

        val request = Request.Builder()
            .url(config.tokenUri)
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OAuth2 token request failed: ${response.code} ${response.message}")
            }
            val body = response.body?.string() ?: throw IOException("Empty token response")
            val json = JSONObject(body)
            val token = json.getString("access_token")
            val expiresIn = json.optLong("expires_in", 3600)
            val expiryTime = System.currentTimeMillis() + (expiresIn * 1000)
            val tokenInfo = TokenInfo(token, expiryTime)
            tokenCache[config.clientEmail] = tokenInfo
            return token
        }
    }

    private fun buildAuthorizedRequest(clientEmail: String, url: String): Request.Builder {
        val token = getAccessToken(clientEmail)
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
    }

    fun checkConnection(clientEmail: String) {
        getFilesList(clientEmail, "root")
    }

    fun getFilesList(clientEmail: String, parentId: String): FolderContent {
        val query = "'$parentId' in parents and trashed = false"
        val fields = "files(id, name, mimeType, size, modifiedTime)"
        val url = "https://www.googleapis.com/drive/v3/files?q=${okhttp3.HttpUrl.encode(query, 0, query.length, true, true, true, true, null, null)}&fields=${okhttp3.HttpUrl.encode(fields, 0, fields.length, true, true, true, true, null, null)}&pageSize=1000"
        val request = buildAuthorizedRequest(clientEmail, url).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Get files list failed: ${response.code} ${response.message}\n${response.body?.string()}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(body)
            val filesArray = json.getJSONArray("files")
            val filesList = mutableListOf<GoogleDriveFile>()
            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(i)
                val id = fileObj.getString("id")
                val name = fileObj.getString("name")
                val mimeType = fileObj.getString("mimeType")
                val size = fileObj.optLong("size", 0L)
                val modifiedTimeStr = fileObj.optString("modifiedTime", null)
                val lastModified = if (modifiedTimeStr != null) {
                    try {
                        Instant.parse(modifiedTimeStr).toEpochMilli()
                    } catch (ignored: Exception) {
                        0L
                    }
                } else {
                    0L
                }
                filesList.add(GoogleDriveFile(id, name, mimeType, size, lastModified))
            }
            return FolderContent(filesList)
        }
    }

    fun getFileMetadata(clientEmail: String, fileId: String): GoogleDriveFile {
        val fields = "id, name, mimeType, size, modifiedTime"
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=${okhttp3.HttpUrl.encode(fields, 0, fields.length, true, true, true, true, null, null)}"
        val request = buildAuthorizedRequest(clientEmail, url).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Get file metadata failed: ${response.code} ${response.message}\n${response.body?.string()}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            val fileObj = JSONObject(body)
            val id = fileObj.getString("id")
            val name = fileObj.getString("name")
            val mimeType = fileObj.getString("mimeType")
            val size = fileObj.optLong("size", 0L)
            val modifiedTimeStr = fileObj.optString("modifiedTime", null)
            val lastModified = if (modifiedTimeStr != null) {
                try {
                    Instant.parse(modifiedTimeStr).toEpochMilli()
                } catch (ignored: Exception) {
                    0L
                }
            } else {
                0L
            }
            return GoogleDriveFile(id, name, mimeType, size, lastModified)
        }
    }

    fun createFolder(clientEmail: String, parentId: String, name: String): String {
        val url = "https://www.googleapis.com/drive/v3/files"
        val json = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", JSONArray().apply { put(parentId) })
        }
        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = buildAuthorizedRequest(clientEmail, url).post(requestBody).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Create folder failed: ${response.code} ${response.message}\n${response.body?.string()}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            val fileObj = JSONObject(body)
            return fileObj.getString("id")
        }
    }

    fun createFileMetadata(clientEmail: String, parentId: String, name: String): String {
        val url = "https://www.googleapis.com/drive/v3/files"
        val json = JSONObject().apply {
            put("name", name)
            put("parents", JSONArray().apply { put(parentId) })
        }
        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = buildAuthorizedRequest(clientEmail, url).post(requestBody).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Create file metadata failed: ${response.code} ${response.message}\n${response.body?.string()}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            val fileObj = JSONObject(body)
            return fileObj.getString("id")
        }
    }

    fun deleteFile(clientEmail: String, fileId: String) {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId"
        val request = buildAuthorizedRequest(clientEmail, url).delete().build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Delete file failed: ${response.code} ${response.message}\n${response.body?.string()}")
            }
        }
    }

    fun renameFile(clientEmail: String, fileId: String, newName: String) {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId"
        val json = JSONObject().apply {
            put("name", newName)
        }
        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = buildAuthorizedRequest(clientEmail, url).patch(requestBody).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Rename file failed: ${response.code} ${response.message}\n${response.body?.string()}")
            }
        }
    }

    fun moveFile(clientEmail: String, fileId: String, currentParentId: String, targetParentId: String) {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?addParents=$targetParentId&removeParents=$currentParentId"
        val requestBody = "".toRequestBody(null)
        val request = buildAuthorizedRequest(clientEmail, url).patch(requestBody).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Move file failed: ${response.code} ${response.message}\n${response.body?.string()}")
            }
        }
    }

    fun uploadFileContent(clientEmail: String, fileId: String, file: File) {
        val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
        val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
        val request = buildAuthorizedRequest(clientEmail, url).patch(requestBody).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Upload file content failed: ${response.code} ${response.message}\n${response.body?.string()}")
            }
        }
    }

    fun getDownloadRequest(clientEmail: String, fileId: String, rangeHeader: String? = null): Request {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val builder = buildAuthorizedRequest(clientEmail, url)
        if (rangeHeader != null) {
            builder.header("Range", rangeHeader)
        }
        return builder.build()
    }
}

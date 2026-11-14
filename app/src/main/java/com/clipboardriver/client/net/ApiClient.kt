package com.clipboardriver.client.net

import com.clipboardriver.client.model.ContentKind
import com.clipboardriver.client.model.RemoteClipboardItem
import com.clipboardriver.client.model.ServerSettings
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface ServerGateway {
    suspend fun register(serverBaseUrl: String, request: RegisterRequest): RegisterResponse
    suspend fun updateProfile(serverBaseUrl: String, deviceToken: String, request: DeviceProfileRequest): DeviceProfileResponse
    suspend fun uploadText(serverBaseUrl: String, deviceToken: String, request: TextUploadRequest): UploadReceipt
    suspend fun uploadFile(serverBaseUrl: String, deviceToken: String, request: FileUploadRequest): UploadReceipt
    suspend fun downloadBlob(serverBaseUrl: String, deviceToken: String, itemId: Long): DownloadedBlob
    fun normalizeServerBaseUrl(serverBaseUrl: String): String
    fun resolveWebSocketUrl(serverBaseUrl: String): String
    fun openWebSocket(websocketUrl: String, listener: WebSocketListener): WebSocket
}

object ClientProtocol {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeEvent(text: String): ServerEvent? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "hello_ack" -> ServerEvent.HelloAck(
                json.decodeFromJsonElement(HelloAckPayload.serializer(), root.getValue("payload")),
            )
            "clipboard.created" -> {
                val item = json.decodeFromJsonElement(RealtimeItemPayload.serializer(), root.getValue("item"))
                item.toRemoteItem()?.let { ServerEvent.ClipboardCreated(it) }
            }
            else -> null
        }
    }.getOrNull()
}

sealed interface ServerEvent {
    data class HelloAck(val payload: HelloAckPayload) : ServerEvent
    data class ClipboardCreated(val item: RemoteClipboardItem) : ServerEvent
}

class ApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Uploads have no idempotency key. Never transparently replay an uncertain request.
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : ServerGateway {
    override suspend fun register(serverBaseUrl: String, request: RegisterRequest): RegisterResponse =
        ClientProtocol.json.decodeFromString(
            RegisterResponse.serializer(),
            post(serverBaseUrl, "register", ClientProtocol.json.encodeToString(RegisterRequest.serializer(), request)),
        )

    override suspend fun updateProfile(
        serverBaseUrl: String,
        deviceToken: String,
        request: DeviceProfileRequest,
    ): DeviceProfileResponse = ClientProtocol.json.decodeFromString(
        DeviceProfileResponse.serializer(),
        post(serverBaseUrl, "device/profile", ClientProtocol.json.encodeToString(DeviceProfileRequest.serializer(), request), deviceToken),
    )

    override suspend fun uploadText(
        serverBaseUrl: String,
        deviceToken: String,
        request: TextUploadRequest,
    ): UploadReceipt = ClientProtocol.json.decodeFromString(
        UploadReceipt.serializer(),
        post(serverBaseUrl, "items/text", ClientProtocol.json.encodeToString(TextUploadRequest.serializer(), request), deviceToken),
    )

    override suspend fun uploadFile(
        serverBaseUrl: String,
        deviceToken: String,
        request: FileUploadRequest,
    ): UploadReceipt {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("upload_kind", "realtime")
            .addFormDataPart("client_created_at", request.clientCreatedAt)
            .addFormDataPart("mime_type", request.mimeType)
            .addFormDataPart(
                "file",
                request.fileName,
                request.file.asRequestBody(request.mimeType.toMediaTypeOrNull() ?: BINARY_TYPE),
            ).build()
        return ClientProtocol.json.decodeFromString(
            UploadReceipt.serializer(),
            executePost(serverBaseUrl, "items/file", body, deviceToken),
        )
    }

    override suspend fun downloadBlob(serverBaseUrl: String, deviceToken: String, itemId: Long): DownloadedBlob {
        val response = httpClient.newCall(
            Request.Builder().url(buildApiUrl(serverBaseUrl, "items/" + itemId + "/blob"))
                .header("Authorization", "Bearer " + deviceToken).get().build(),
        ).await()
        try {
            if (!response.isSuccessful) throw response.toApiException()
            val body = response.body ?: throw IOException("Empty file response.")
            return DownloadedBlob(
                body.byteStream(),
                body.contentType()?.toString(),
                extractFileName(response.header("Content-Disposition")),
            )
        } catch (failure: Throwable) {
            response.close()
            throw failure
        }
    }

    override fun normalizeServerBaseUrl(serverBaseUrl: String): String =
        parseServerBaseUrl(serverBaseUrl).toString().trimEnd('/')

    override fun resolveWebSocketUrl(serverBaseUrl: String): String =
        buildApiUrl(serverBaseUrl, "ws").let {
            if (it.startsWith("https://")) "wss://" + it.removePrefix("https://")
            else "ws://" + it.removePrefix("http://")
        }

    override fun openWebSocket(websocketUrl: String, listener: WebSocketListener): WebSocket =
        httpClient.newWebSocket(Request.Builder().url(websocketUrl).build(), listener)

    private suspend fun post(serverBaseUrl: String, path: String, body: String, token: String? = null): String =
        executePost(serverBaseUrl, path, body.toRequestBody(JSON_TYPE), token)

    private suspend fun executePost(serverBaseUrl: String, path: String, body: RequestBody, token: String?): String {
        val builder = Request.Builder().url(buildApiUrl(serverBaseUrl, path)).post(body)
        if (token != null) builder.header("Authorization", "Bearer " + token)
        return httpClient.newCall(builder.build()).await().use {
            if (!it.isSuccessful) throw it.toApiException()
            it.body?.string() ?: throw IOException("Empty server response.")
        }
    }

    private fun Response.toApiException(): ApiException {
        val text = body?.string().orEmpty()
        val description = runCatching {
            ClientProtocol.json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return ApiException(code, description?.takeIf { it.isNotBlank() } ?: message.ifBlank { "HTTP " + code })
    }

    private fun buildApiUrl(base: String, path: String): String =
        parseServerBaseUrl(base).newBuilder().addPathSegments("api/v1/client/" + path).build().toString()

    private fun parseServerBaseUrl(value: String): HttpUrl {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "Server URL is required." }
        val candidate = if (trimmed.contains("://")) trimmed else "https://" + trimmed
        val parsed = candidate.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid server URL.")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Use a server URL without embedded credentials." }
        return parsed.newBuilder().query(null).fragment(null)
            .encodedPath(parsed.encodedPath.trimEnd('/').ifBlank { "/" }).build()
    }

    private fun extractFileName(header: String?): String? {
        if (header == null) return null
        val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        if (!encoded.isNullOrBlank()) {
            return runCatching {
                URLDecoder.decode(encoded.trim().trim('"').replace("+", "%2B"), "UTF-8")
            }.getOrNull()
        }
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val BINARY_TYPE = "application/octet-stream".toMediaType()
    }
}

class ApiException(val code: Int, message: String) : IOException(message)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

@Serializable
data class RegisterRequest(
    @SerialName("device_uuid") val deviceUuid: String,
    @SerialName("enrollment_code") val enrollmentCode: String,
    val nickname: String,
    @SerialName("app_name") val appName: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class DeviceProfileRequest(
    @SerialName("app_name") val appName: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class RegisterResponse(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("device_token") val deviceToken: String,
    val settings: SettingsPayload,
)

@Serializable
data class DeviceProfileResponse(
    val ok: Boolean,
    @SerialName("device_id") val deviceId: Long,
    @SerialName("server_at") val serverAt: String,
    val settings: SettingsPayload,
)

@Serializable
data class SettingsPayload(
    @SerialName("file_max_bytes") val fileMaxBytes: Long,
    @SerialName("text_batch_limit") val textBatchLimit: Int,
) {
    fun toSettings(): ServerSettings {
        require(fileMaxBytes >= -1 && textBatchLimit > 0) { "Invalid server settings." }
        return ServerSettings(fileMaxBytes, textBatchLimit)
    }
}

@Serializable
data class TextUploadRequest(
    @SerialName("client_created_at") val clientCreatedAt: String,
    @SerialName("text_content") val textContent: String,
    @SerialName("upload_kind") val uploadKind: String = "realtime",
)

data class FileUploadRequest(val clientCreatedAt: String, val mimeType: String, val fileName: String, val file: File)

class DownloadedBlob(val stream: InputStream, val contentType: String?, val fileName: String?) : Closeable {
    override fun close() = stream.close()
}

@Serializable
data class UploadReceipt(val created: Boolean, val item: UploadedItem)

@Serializable
data class UploadedItem(
    @SerialName("item_id") val itemId: Long,
    @SerialName("content_kind") val contentKind: String,
    @SerialName("upload_kind") val uploadKind: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("received_at") val receivedAt: String,
)

@Serializable
data class HelloMessage(val payload: HelloPayload, val type: String = "hello")

@Serializable
data class HelloPayload(
    @SerialName("device_token") val deviceToken: String,
    @SerialName("device_uuid") val deviceUuid: String,
)

@Serializable
data class HelloAckPayload(
    @SerialName("device_id") val deviceId: Long,
    val settings: SettingsPayload,
    val time: String,
)

@Serializable
data class RealtimeItemPayload(
    @SerialName("item_id") val itemId: Long,
    @SerialName("content_kind") val contentKind: String,
    @SerialName("upload_kind") val uploadKind: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("text_content") val textContent: String? = null,
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("blob_name") val blobName: String? = null,
) {
    fun toRemoteItem(): RemoteClipboardItem? {
        if (itemId <= 0 || uploadKind != "realtime") return null
        val kind = when (contentKind) {
            "text" -> ContentKind.TEXT
            "file" -> ContentKind.FILE
            else -> return null
        }
        if (kind == ContentKind.TEXT && textContent.isNullOrEmpty()) return null
        return RemoteClipboardItem(itemId, kind, receivedAt, textContent, mimeType, blobName)
    }
}
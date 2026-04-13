package com.clipboardriver.client.net

import com.clipboardriver.client.model.AppConfig
import com.clipboardriver.client.model.ContentKind
import com.clipboardriver.client.model.RemoteClipboardItem
import kotlinx.serialization.KSerializer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface ServerGateway {
    suspend fun register(
        serverBaseUrl: String,
        request: RegisterRequest,
    ): RegisterResponse

    suspend fun updateProfile(
        serverBaseUrl: String,
        deviceToken: String,
        request: DeviceProfileRequest,
    ): BasicOkResponse

    suspend fun heartbeat(
        serverBaseUrl: String,
        deviceToken: String,
    ): HeartbeatResponse

    suspend fun uploadText(
        serverBaseUrl: String,
        deviceToken: String,
        request: TextUploadRequest,
    ): TextUploadResponse

    suspend fun uploadTextBatch(
        serverBaseUrl: String,
        deviceToken: String,
        request: TextBatchUploadRequest,
    ): TextBatchUploadResponse

    suspend fun uploadImage(
        serverBaseUrl: String,
        deviceToken: String,
        request: ImageUploadRequest,
    ): TextUploadResponse

    suspend fun downloadBlob(
        serverBaseUrl: String,
        deviceToken: String,
        blobUrl: String,
    ): DownloadedBlob

    fun openWebSocket(
        websocketUrl: String,
        listener: WebSocketListener,
    ): WebSocket

    fun resolveWebSocketUrl(config: AppConfig): String
}

class ApiClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) : ServerGateway {
    override suspend fun register(
        serverBaseUrl: String,
        request: RegisterRequest,
    ): RegisterResponse = postJson(
        url = buildApiUrl(serverBaseUrl, "register"),
        requestBody = request,
        responseSerializer = RegisterResponse.serializer(),
    )

    override suspend fun updateProfile(
        serverBaseUrl: String,
        deviceToken: String,
        request: DeviceProfileRequest,
    ): BasicOkResponse = postJson(
        url = buildApiUrl(serverBaseUrl, "device/profile"),
        requestBody = request,
        responseSerializer = BasicOkResponse.serializer(),
        token = deviceToken,
    )

    override suspend fun heartbeat(
        serverBaseUrl: String,
        deviceToken: String,
    ): HeartbeatResponse = postJson(
        url = buildApiUrl(serverBaseUrl, "heartbeat"),
        requestBody = EmptyBody,
        responseSerializer = HeartbeatResponse.serializer(),
        token = deviceToken,
    )

    override suspend fun uploadText(
        serverBaseUrl: String,
        deviceToken: String,
        request: TextUploadRequest,
    ): TextUploadResponse = postJson(
        url = buildApiUrl(serverBaseUrl, "items/text"),
        requestBody = request,
        responseSerializer = TextUploadResponse.serializer(),
        token = deviceToken,
    )

    override suspend fun uploadTextBatch(
        serverBaseUrl: String,
        deviceToken: String,
        request: TextBatchUploadRequest,
    ): TextBatchUploadResponse = postJson(
        url = buildApiUrl(serverBaseUrl, "items/text/batch"),
        requestBody = request,
        responseSerializer = TextBatchUploadResponse.serializer(),
        token = deviceToken,
    )

    override suspend fun uploadImage(
        serverBaseUrl: String,
        deviceToken: String,
        request: ImageUploadRequest,
    ): TextUploadResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("client_item_id", request.clientItemId)
            .addFormDataPart("upload_kind", request.uploadKind)
            .addFormDataPart("client_created_at", request.clientCreatedAt)
            .addFormDataPart("mime_type", request.mimeType)
            .addFormDataPart(
                name = "file",
                filename = request.fileName,
                body = request.file.asRequestBody(request.mimeType.toMediaType()),
            )
            .build()

        val response = httpClient.newCall(
            Request.Builder()
                .url(buildApiUrl(serverBaseUrl, "items/image"))
                .header("Authorization", "Bearer $deviceToken")
                .post(body)
                .build(),
        ).await()

        response.use { handled ->
            val bodyText = handled.body?.string().orEmpty()
            if (!handled.isSuccessful) {
                throw ApiException(handled.code, bodyText.ifBlank { handled.message })
            }
            return json.decodeFromString(TextUploadResponse.serializer(), bodyText)
        }
    }

    override suspend fun downloadBlob(
        serverBaseUrl: String,
        deviceToken: String,
        blobUrl: String,
    ): DownloadedBlob {
        val resolvedUrl = resolveBlobUrl(serverBaseUrl, blobUrl)
        val response = httpClient.newCall(
            Request.Builder()
                .url(resolvedUrl)
                .header("Authorization", "Bearer $deviceToken")
                .get()
                .build(),
        ).await()

        response.use { handled ->
            val responseBody = handled.body
            val contentType = responseBody?.contentType()?.toString()
            val bytes = responseBody?.bytes() ?: ByteArray(0)
            if (!handled.isSuccessful) {
                throw ApiException(handled.code, handled.message.ifBlank { "Blob download failed." })
            }
            return DownloadedBlob(
                bytes = bytes,
                contentType = contentType,
                fileName = extractFileName(handled.header("Content-Disposition")),
            )
        }
    }

    override fun openWebSocket(
        websocketUrl: String,
        listener: WebSocketListener,
    ): WebSocket {
        val request = Request.Builder()
            .url(websocketUrl)
            .build()
        return httpClient.newWebSocket(request, listener)
    }

    override fun resolveWebSocketUrl(config: AppConfig): String {
        val normalizedBase = config.serverBaseUrl.trim().trimEnd('/')
        val wsBase = when {
            normalizedBase.startsWith("https://") -> normalizedBase.replaceFirst("https://", "wss://")
            normalizedBase.startsWith("http://") -> normalizedBase.replaceFirst("http://", "ws://")
            normalizedBase.startsWith("wss://") || normalizedBase.startsWith("ws://") -> normalizedBase
            else -> "ws://$normalizedBase"
        }
        return "$wsBase/api/v1/client/ws"
    }

    private suspend fun <T> postJson(
        url: String,
        requestBody: Any,
        responseSerializer: KSerializer<T>,
        token: String? = null,
    ): T {
        val requestJson = json.encodeToString(
            serializer = serializerFor(requestBody),
            value = requestBody,
        )
        val builder = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
        token?.let { builder.header("Authorization", "Bearer $it") }
        val response = httpClient.newCall(builder.build()).await()
        response.use { handled ->
            val body = handled.body?.string().orEmpty()
            if (!handled.isSuccessful) {
                throw ApiException(handled.code, body.ifBlank { handled.message })
            }
            return json.decodeFromString(responseSerializer, body)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(body: Any): KSerializer<Any> = when (body) {
        is RegisterRequest -> RegisterRequest.serializer() as KSerializer<Any>
        is DeviceProfileRequest -> DeviceProfileRequest.serializer() as KSerializer<Any>
        is TextUploadRequest -> TextUploadRequest.serializer() as KSerializer<Any>
        is TextBatchUploadRequest -> TextBatchUploadRequest.serializer() as KSerializer<Any>
        is EmptyBody -> EmptyBody.serializer() as KSerializer<Any>
        else -> error("Unsupported request body: ${body::class.qualifiedName}")
    }

    private fun buildApiUrl(serverBaseUrl: String, path: String): String {
        val normalizedBase = serverBaseUrl.trim().trimEnd('/')
        return "$normalizedBase/api/v1/client/$path"
    }

    private fun resolveBlobUrl(serverBaseUrl: String, blobUrl: String): String {
        val normalizedUrl = blobUrl.trim()
        if (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            return normalizedUrl
        }
        val normalizedBase = serverBaseUrl.trim().trimEnd('/')
        return if (normalizedUrl.startsWith("/")) {
            "$normalizedBase$normalizedUrl"
        } else {
            "$normalizedBase/$normalizedUrl"
        }
    }

    private fun extractFileName(contentDisposition: String?): String? {
        val header = contentDisposition ?: return null
        val filenameStar = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
        if (!filenameStar.isNullOrBlank()) {
            return URLDecoder.decode(filenameStar.trim().trim('"'), Charsets.UTF_8.name())
        }

        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class ApiException(
    val code: Int,
    message: String,
) : IOException(message)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation {
        cancel()
    }
}

@Serializable
data class RegisterRequest(
    @SerialName("device_uuid") val deviceUuid: String,
    @SerialName("enrollment_code") val enrollmentCode: String,
    val nickname: String,
    @SerialName("os_name") val osName: String,
    @SerialName("os_version") val osVersion: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String,
    val capabilities: ClientCapabilities,
)

@Serializable
data class DeviceProfileRequest(
    val nickname: String,
    @SerialName("os_name") val osName: String,
    @SerialName("os_version") val osVersion: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String,
    val capabilities: ClientCapabilities,
)

@Serializable
data class ClientCapabilities(
    @SerialName("supports_text") val supportsText: Boolean = true,
    @SerialName("supports_image") val supportsImage: Boolean = true,
    @SerialName("supports_ws") val supportsWs: Boolean = true,
    @SerialName("supports_text_batch") val supportsTextBatch: Boolean = true,
)

@Serializable
data class RegisterResponse(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("device_token") val deviceToken: String,
    @SerialName("ws_url") val wsUrl: String = "",
    val settings: SettingsPayload = SettingsPayload(),
    @SerialName("server_cursor") val serverCursor: Long = 0L,
)

@Serializable
data class HeartbeatResponse(
    val ok: Boolean,
    @SerialName("server_at") val serverAt: String? = null,
    val settings: SettingsPayload = SettingsPayload(),
)

@Serializable
data class SettingsPayload(
    @SerialName("realtime_fanout_enabled") val realtimeFanoutEnabled: Boolean = true,
    @SerialName("retention_days") val retentionDays: Int? = null,
    @SerialName("image_max_bytes") val imageMaxBytes: Long? = null,
    @SerialName("text_batch_limit") val textBatchLimit: Int = 200,
)

@Serializable
data class TextUploadRequest(
    @SerialName("client_item_id") val clientItemId: String,
    @SerialName("upload_kind") val uploadKind: String,
    @SerialName("client_created_at") val clientCreatedAt: String,
    @SerialName("text_content") val textContent: String,
)

@Serializable
data class TextBatchUploadRequest(
    val items: List<TextUploadRequest>,
)

data class ImageUploadRequest(
    val clientItemId: String,
    val uploadKind: String,
    val clientCreatedAt: String,
    val mimeType: String,
    val fileName: String,
    val file: File,
)

data class DownloadedBlob(
    val bytes: ByteArray,
    val contentType: String?,
    val fileName: String?,
)

@Serializable
data class TextUploadResponse(
    val created: Boolean,
    val item: UploadedItem,
)

@Serializable
data class TextBatchUploadResponse(
    val results: List<TextBatchUploadResult>,
)

@Serializable
data class TextBatchUploadResult(
    @SerialName("client_item_id") val clientItemId: String,
    val created: Boolean = false,
    val item: UploadedItem? = null,
    val error: String? = null,
)

@Serializable
data class UploadedItem(
    val id: Long,
    @SerialName("server_cursor") val serverCursor: Long,
    @SerialName("client_item_id") val clientItemId: String,
)

@Serializable
data class BasicOkResponse(
    val ok: Boolean,
)

@Serializable
private object EmptyBody

@Serializable
data class WebSocketEnvelope(
    val type: String,
    val payload: HelloAckPayload? = null,
    val item: RealtimeItemPayload? = null,
)

@Serializable
data class HelloMessage(
    val type: String = "hello",
    val payload: HelloPayload,
)

@Serializable
data class HelloPayload(
    @SerialName("device_token") val deviceToken: String,
    @SerialName("device_uuid") val deviceUuid: String,
    @SerialName("last_cursor") val lastCursor: Long,
)

@Serializable
data class AckMessage(
    val type: String = "ack",
    val payload: AckPayload,
)

@Serializable
data class AckPayload(
    @SerialName("server_cursor") val serverCursor: Long,
)

@Serializable
data class HelloAckPayload(
    @SerialName("device_id") val deviceId: Long,
    val settings: SettingsPayload = SettingsPayload(),
    val time: String? = null,
)

@Serializable
data class RealtimeItemPayload(
    val id: Long,
    @SerialName("server_cursor") val serverCursor: Long,
    @SerialName("client_item_id") val clientItemId: String,
    @SerialName("content_kind") val contentKind: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("text_content") val textContent: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("blob_url") val blobUrl: String? = null,
    @SerialName("blob_name") val blobName: String? = null,
) {
    fun toRemoteClipboardItem(): RemoteClipboardItem? {
        val kind = when (contentKind.lowercase()) {
            "text" -> ContentKind.TEXT
            "image" -> ContentKind.IMAGE
            else -> return null
        }
        if (kind == ContentKind.TEXT && textContent.isNullOrBlank()) return null
        return RemoteClipboardItem(
            serverCursor = serverCursor,
            serverItemId = id,
            clientItemId = clientItemId,
            contentKind = kind,
            receivedAt = receivedAt,
            textContent = textContent,
            mimeType = mimeType,
            blobUrl = blobUrl,
            blobName = blobName,
        )
    }
}

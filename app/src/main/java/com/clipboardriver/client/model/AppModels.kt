package com.clipboardriver.client.model

import java.time.Instant

enum class SessionOwner { MAIN_SCREEN, RECEIVE_PANEL }
enum class PanelMode { CAPTURE, RECEIVE }
enum class ContentKind { TEXT, FILE }

data class ServerSettings(
    val fileMaxBytes: Long? = null,
    val textBatchLimit: Int = 200,
)

data class AppConfig(
    val serverBaseUrl: String = "",
    val deviceUuid: String = "",
    val deviceId: Long? = null,
    val nickname: String = "",
    val syncSendEnabled: Boolean = true,
    val receiveRealtimeEnabled: Boolean = true,
    val textCaptureMinChars: Int = 2,
    val textCaptureMaxChars: Int = 1024,
    val serverSettings: ServerSettings = ServerSettings(),
)

enum class CaptureResultState { UPLOADED, FILTERED, NO_TEXT, ERROR, DISABLED, BUSY }

data class CaptureResult(
    val state: CaptureResultState,
    val message: String,
    val completedAt: String = Instant.now().toString(),
    val itemId: Long? = null,
)

data class ReceiveState(
    val status: String = "",
    val latestPreview: String? = null,
    val latestReceivedAt: String? = null,
    val latestOpenUri: String? = null,
    val latestOpenMimeType: String? = null,
    val latestError: String? = null,
    val terminal: Boolean = false,
)

enum class ConnectionState { DISCONNECTED, CONNECTING, HANDSHAKING, CONNECTED, RETRYING, AUTH_REQUIRED, ERROR }

data class RealtimeStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val attempt: Int = 0,
    val lastError: String? = null,
) {
    val connected: Boolean get() = state == ConnectionState.CONNECTED
    val terminal: Boolean get() = state == ConnectionState.AUTH_REQUIRED || state == ConnectionState.ERROR
}

data class RegistrationForm(
    val serverBaseUrl: String = "",
    val nickname: String = "",
    val enrollmentCode: String = "",
)

data class RemoteClipboardItem(
    val itemId: Long,
    val contentKind: ContentKind,
    val receivedAt: String,
    val textContent: String? = null,
    val mimeType: String = "",
    val blobName: String? = null,
)

fun interface TimeProvider { fun now(): Instant }
object SystemTimeProvider : TimeProvider { override fun now(): Instant = Instant.now() }
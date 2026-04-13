package com.clipboardriver.client.model

import java.time.Instant
import java.util.UUID

enum class LocalHistoryMode {
    FOREVER,
    DAYS,
    DISABLED,
}

enum class SyncState {
    PENDING,
    SYNCED,
    FAILED,
    SKIPPED,
}

enum class SessionOwner(val priority: Int) {
    MAIN_SCREEN(0),
    RECEIVE_PANEL(1),
}

enum class PanelMode {
    CAPTURE,
    RECEIVE,
}

enum class ContentKind {
    TEXT,
    IMAGE,
}

data class ServerSettings(
    val realtimeFanoutEnabled: Boolean = true,
    val textBatchLimit: Int = 200,
    val imageMaxBytes: Long? = null,
)

data class AppConfig(
    val serverBaseUrl: String = "",
    val websocketUrl: String = "",
    val deviceUuid: String = UUID.randomUUID().toString(),
    val nickname: String = "",
    val lastRemoteCursor: Long = 0L,
    val syncSendEnabled: Boolean = true,
    val receiveRealtimeEnabled: Boolean = true,
    val localHistoryMode: LocalHistoryMode = LocalHistoryMode.DAYS,
    val localHistoryRetentionDays: Int = 30,
    val textCaptureMinChars: Int = 2,
    val textCaptureMaxChars: Int = 1024,
    val historySyncWifiOnly: Boolean = false,
    val serverSettings: ServerSettings = ServerSettings(),
) {
    val hasServerBaseUrl: Boolean
        get() = serverBaseUrl.isNotBlank()
}

data class RuntimeState(
    val lastRemoteCursor: Long = 0L,
    val websocketState: String = "",
    val lastError: String? = null,
    val lastSuccessfulHeartbeatAt: String? = null,
    val lastSuccessfulUploadAt: String? = null,
)

data class HistoryItem(
    val clientItemId: String,
    val contentKind: ContentKind = ContentKind.TEXT,
    val textContent: String? = null,
    val imagePath: String? = null,
    val mimeType: String? = null,
    val skipReason: String? = null,
    val contentHash: String,
    val clientCreatedAt: String,
    val syncState: SyncState,
    val serverCursor: Long? = null,
    val serverItemId: Long? = null,
) {
    val previewText: String
        get() = when (contentKind) {
            ContentKind.TEXT -> textContent.orEmpty()
            ContentKind.IMAGE -> imagePath
                ?.substringAfterLast('\\')
                ?.substringAfterLast('/')
                .orEmpty()
        }

    val isPendingUpload: Boolean
        get() = syncState == SyncState.PENDING || syncState == SyncState.FAILED
}

data class CaptureResult(
    val state: CaptureResultState,
    val message: String,
    val record: HistoryItem? = null,
) {
    val shouldAutoClose: Boolean
        get() = state != CaptureResultState.LISTENING
}

enum class CaptureResultState {
    SYNCED,
    PENDING,
    DROPPED,
    SUPPRESSED,
    DUPLICATE,
    FILTERED,
    NO_TEXT,
    ERROR,
    SKIPPED,
    DISABLED,
    LISTENING,
}

data class ReceiveState(
    val status: String = "",
    val latestPreview: String? = null,
    val latestReceivedAt: String? = null,
    val latestOpenUri: String? = null,
    val latestError: String? = null,
    val terminal: Boolean = false,
)

data class RealtimeStatus(
    val owner: SessionOwner? = null,
    val connected: Boolean = false,
    val stateLabel: String = "",
    val attempt: Int = 0,
    val lastError: String? = null,
)

data class RegistrationForm(
    val serverBaseUrl: String = "",
    val nickname: String = "",
    val enrollmentCode: String = "",
)

data class RemoteClipboardItem(
    val serverCursor: Long,
    val serverItemId: Long,
    val clientItemId: String,
    val contentKind: ContentKind,
    val receivedAt: String,
    val textContent: String? = null,
    val mimeType: String? = null,
    val blobUrl: String? = null,
    val blobName: String? = null,
)

interface TimeProvider {
    fun now(): Instant
}

object SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}

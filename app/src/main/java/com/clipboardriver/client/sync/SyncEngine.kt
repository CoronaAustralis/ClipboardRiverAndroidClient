package com.clipboardriver.client.sync

import android.net.Uri
import android.os.Build
import com.clipboardriver.client.AppStrings
import com.clipboardriver.client.BuildConfig
import com.clipboardriver.client.R
import com.clipboardriver.client.model.AppConfig
import com.clipboardriver.client.model.CaptureResult
import com.clipboardriver.client.model.CaptureResultState
import com.clipboardriver.client.model.ContentKind
import com.clipboardriver.client.model.HistoryItem
import com.clipboardriver.client.model.LocalHistoryMode
import com.clipboardriver.client.model.PanelMode
import com.clipboardriver.client.model.ReceiveState
import com.clipboardriver.client.model.RealtimeStatus
import com.clipboardriver.client.model.RemoteClipboardItem
import com.clipboardriver.client.model.RuntimeState
import com.clipboardriver.client.model.SessionOwner
import com.clipboardriver.client.model.SyncState
import com.clipboardriver.client.model.TimeProvider
import com.clipboardriver.client.net.AckMessage
import com.clipboardriver.client.net.AckPayload
import com.clipboardriver.client.net.ApiException
import com.clipboardriver.client.net.ClientCapabilities
import com.clipboardriver.client.net.DeviceProfileRequest
import com.clipboardriver.client.net.HeartbeatResponse
import com.clipboardriver.client.net.HelloMessage
import com.clipboardriver.client.net.HelloPayload
import com.clipboardriver.client.net.ImageUploadRequest
import com.clipboardriver.client.net.RegisterRequest
import com.clipboardriver.client.net.ServerGateway
import com.clipboardriver.client.net.SettingsPayload
import com.clipboardriver.client.net.TextBatchUploadRequest
import com.clipboardriver.client.net.TextUploadRequest
import com.clipboardriver.client.net.WebSocketEnvelope
import com.clipboardriver.client.platform.ImageStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

interface ConfigStore {
    val configFlow: Flow<AppConfig>
    suspend fun snapshot(): AppConfig
    suspend fun initializeDeviceUuidIfNeeded(): AppConfig
    suspend fun updateConfig(transform: (AppConfig) -> AppConfig): AppConfig
    suspend fun applyServerSettings(
        websocketUrl: String? = null,
        serverCursor: Long? = null,
        realtimeFanoutEnabled: Boolean? = null,
        textBatchLimit: Int? = null,
        imageMaxBytes: Long? = null,
    ): AppConfig
}

interface TokenStore {
    val tokenFlow: Flow<String?>
    suspend fun currentToken(): String?
    suspend fun setToken(token: String?)
}

interface HistoryStore {
    fun visibleHistory(limit: Int = 60): Flow<List<HistoryItem>>
    fun pendingCount(): Flow<Int>
    fun runtimeState(): Flow<RuntimeState>
    suspend fun currentRuntime(): RuntimeState
    suspend fun upsertRecord(item: HistoryItem)
    suspend fun updateSyncResult(
        clientItemId: String,
        syncState: SyncState,
        serverCursor: Long?,
        serverItemId: Long?,
        skipReason: String? = null,
    )

    suspend fun pendingRecords(limit: Int = 200): List<HistoryItem>
    suspend fun prune(mode: LocalHistoryMode, retentionDays: Int)
    suspend fun clearHistory()
    suspend fun updateRuntime(transform: (RuntimeState) -> RuntimeState)
    suspend fun resetRuntime()
}

interface ClipboardTextGateway {
    fun readText(): String?
    fun writeText(text: String): Boolean
}

interface WifiStatusProvider {
    fun isOnWifi(): Boolean
}

class SuppressionRegistry(
    private val timeProvider: TimeProvider,
    private val ttlMillis: Long = 10_000L,
) {
    private val entries = LinkedHashMap<String, Long>()

    fun register(hash: String) {
        prune()
        entries[hash] = timeProvider.now().toEpochMilli() + ttlMillis
    }

    fun consumeIfSuppressed(hash: String): Boolean {
        prune()
        val expiresAt = entries[hash] ?: return false
        if (expiresAt < timeProvider.now().toEpochMilli()) {
            entries.remove(hash)
            return false
        }
        entries.remove(hash)
        return true
    }

    private fun prune() {
        val now = timeProvider.now().toEpochMilli()
        entries.entries.removeIf { (_, expiresAt) -> expiresAt < now }
    }
}

class LocalCaptureDeduplicator(
    private val timeProvider: TimeProvider,
    private val duplicateWindowMillis: Long = 1_500L,
) {
    private var lastHash: String? = null
    private var lastCapturedAtMillis: Long = 0L

    fun isDuplicate(hash: String): Boolean {
        val now = timeProvider.now().toEpochMilli()
        val duplicate = lastHash == hash && now - lastCapturedAtMillis <= duplicateWindowMillis
        lastHash = hash
        lastCapturedAtMillis = now
        return duplicate
    }
}

class SyncCoordinator(
    private val configStore: ConfigStore,
    private val tokenStore: TokenStore,
    private val historyStore: HistoryStore,
    private val clipboardGateway: ClipboardTextGateway,
    private val imageStorageManager: ImageStorageManager,
    private val wifiStatusProvider: WifiStatusProvider,
    private val apiClient: ServerGateway,
    private val timeProvider: TimeProvider,
    private val suppressionRegistry: SuppressionRegistry,
    private val deduplicator: LocalCaptureDeduplicator,
    private val appScope: CoroutineScope,
    private val strings: AppStrings,
) {
    suspend fun register(
        serverBaseUrl: String,
        nickname: String,
        enrollmentCode: String,
    ): Result<String> = runCatching {
        require(serverBaseUrl.isNotBlank()) { strings.get(R.string.validation_server_required) }
        require(nickname.isNotBlank()) { strings.get(R.string.validation_nickname_required) }
        require(enrollmentCode.isNotBlank()) { strings.get(R.string.validation_enrollment_required) }

        val config = configStore.initializeDeviceUuidIfNeeded()
        val normalizedBaseUrl = serverBaseUrl.trim().trimEnd('/')
        val normalizedNickname = nickname.trim()
        val request = RegisterRequest(
            deviceUuid = config.deviceUuid,
            enrollmentCode = enrollmentCode.trim(),
            nickname = normalizedNickname,
            osName = "Android",
            osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() },
            platform = "mobile",
            appVersion = BuildConfig.VERSION_NAME,
            capabilities = ClientCapabilities(),
        )
        val response = apiClient.register(normalizedBaseUrl, request)
        tokenStore.setToken(response.deviceToken)
        configStore.updateConfig { current ->
            current.copy(
                serverBaseUrl = normalizedBaseUrl,
                websocketUrl = "",
                nickname = normalizedNickname,
                lastRemoteCursor = response.serverCursor,
                serverSettings = current.serverSettings.copy(
                    realtimeFanoutEnabled = response.settings.realtimeFanoutEnabled,
                    textBatchLimit = response.settings.textBatchLimit,
                    imageMaxBytes = response.settings.imageMaxBytes,
                ),
            )
        }
        historyStore.updateRuntime {
            it.copy(
                lastRemoteCursor = response.serverCursor,
                lastError = null,
            )
        }
        runCatching {
            apiClient.updateProfile(
                serverBaseUrl = normalizedBaseUrl,
                deviceToken = response.deviceToken,
                request = DeviceProfileRequest(
                    nickname = normalizedNickname,
                    osName = "Android",
                    osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() },
                    platform = "mobile",
                    appVersion = BuildConfig.VERSION_NAME,
                    capabilities = ClientCapabilities(),
                ),
            )
        }
        strings.get(R.string.sync_register_success)
    }

    suspend fun signOut() {
        tokenStore.setToken(null)
        configStore.updateConfig { current ->
            current.copy(
                websocketUrl = "",
                lastRemoteCursor = 0L,
            )
        }
        historyStore.resetRuntime()
    }

    suspend fun captureCurrentClipboard(): CaptureResult {
        val text = clipboardGateway.readText()?.trim() ?: return CaptureResult(
            state = CaptureResultState.NO_TEXT,
            message = strings.get(R.string.capture_no_text),
        )

        val config = configStore.snapshot()
        val hash = text.sha256()

        if (suppressionRegistry.consumeIfSuppressed(hash)) {
            return CaptureResult(
                state = CaptureResultState.SUPPRESSED,
                message = strings.get(R.string.capture_suppressed),
            )
        }

        if (deduplicator.isDuplicate(hash)) {
            return CaptureResult(
                state = CaptureResultState.DUPLICATE,
                message = strings.get(R.string.capture_duplicate_text),
            )
        }

        if (text.length < config.textCaptureMinChars) {
            return CaptureResult(
                state = CaptureResultState.FILTERED,
                message = strings.get(R.string.capture_too_short),
            )
        }

        if (text.length > config.textCaptureMaxChars) {
            return CaptureResult(
                state = CaptureResultState.FILTERED,
                message = strings.get(R.string.capture_too_long),
            )
        }

        val shouldStoreHistory = config.localHistoryMode != LocalHistoryMode.DISABLED
        val token = tokenStore.currentToken()
        val isRegistered = !token.isNullOrBlank() && config.serverBaseUrl.isNotBlank()
        val shouldAttemptUpload = config.syncSendEnabled && isRegistered
        val nowIso = timeProvider.now().toString()
        val record = HistoryItem(
            clientItemId = "${config.deviceUuid}-${UUID.randomUUID()}",
            contentKind = ContentKind.TEXT,
            textContent = text,
            contentHash = hash,
            clientCreatedAt = nowIso,
            syncState = SyncState.PENDING,
        )

        var uploadState = SyncState.PENDING
        var uploadMessage = strings.get(R.string.capture_pending_queued)
        var syncedRecord = record

        if (shouldAttemptUpload) {
            runCatching {
                apiClient.uploadText(
                    serverBaseUrl = config.serverBaseUrl,
                    deviceToken = token!!,
                    request = TextUploadRequest(
                        clientItemId = record.clientItemId,
                        uploadKind = "realtime",
                        clientCreatedAt = record.clientCreatedAt,
                        textContent = record.textContent.orEmpty(),
                    ),
                )
            }.onSuccess { response ->
                uploadState = SyncState.SYNCED
                uploadMessage = strings.get(R.string.capture_synced)
                syncedRecord = record.copy(
                    syncState = SyncState.SYNCED,
                    serverCursor = response.item.serverCursor,
                    serverItemId = response.item.id,
                )
                historyStore.updateRuntime {
                    it.copy(
                        lastError = null,
                        lastSuccessfulUploadAt = nowIso,
                    )
                }
            }.onFailure { throwable ->
                uploadState = SyncState.PENDING
                uploadMessage = throwable.userFacingMessage(strings.get(R.string.capture_upload_failed_saved))
                historyStore.updateRuntime { runtime ->
                    runtime.copy(lastError = uploadMessage)
                }
            }
        } else if (!isRegistered && shouldStoreHistory) {
            uploadState = SyncState.PENDING
            uploadMessage = strings.get(R.string.capture_saved_unregistered)
        } else if (!config.syncSendEnabled && shouldStoreHistory) {
            uploadState = SyncState.PENDING
            uploadMessage = strings.get(R.string.capture_saved_sync_disabled)
        } else if (!shouldStoreHistory) {
            return CaptureResult(
                state = CaptureResultState.DROPPED,
                message = if (shouldAttemptUpload) {
                    strings.get(R.string.capture_dropped_after_upload_fail_history_disabled)
                } else {
                    strings.get(R.string.capture_dropped_history_disabled)
                },
            )
        }

        if (!shouldStoreHistory) {
            return if (uploadState == SyncState.SYNCED) {
                CaptureResult(
                    state = CaptureResultState.SYNCED,
                    message = uploadMessage,
                )
            } else {
                CaptureResult(
                    state = CaptureResultState.DROPPED,
                    message = strings.get(R.string.capture_dropped_upload_fail_no_history),
                )
            }
        }

        val finalRecord = syncedRecord.copy(syncState = uploadState)
        historyStore.upsertRecord(finalRecord)
        historyStore.prune(config.localHistoryMode, config.localHistoryRetentionDays)

        if (shouldAttemptUpload && uploadState == SyncState.SYNCED) {
            appScope.launch {
                flushPendingHistory(manual = false)
            }
        }

        return CaptureResult(
            state = when (uploadState) {
                SyncState.SYNCED -> CaptureResultState.SYNCED
                SyncState.PENDING -> CaptureResultState.PENDING
                SyncState.FAILED -> CaptureResultState.ERROR
                SyncState.SKIPPED -> CaptureResultState.SKIPPED
            },
            message = uploadMessage,
            record = finalRecord,
        )
    }

    suspend fun captureCurrentClipboardWithRetry(
        maxAttempts: Int = 5,
        retryDelayMillis: Long = 180L,
    ): CaptureResult {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1." }

        repeat(maxAttempts) { attempt ->
            val result = captureCurrentClipboard()
            if (result.state != CaptureResultState.NO_TEXT || attempt == maxAttempts - 1) {
                return result
            }
            delay(retryDelayMillis)
        }

        return CaptureResult(
            state = CaptureResultState.NO_TEXT,
            message = strings.get(R.string.capture_no_text),
        )
    }

    suspend fun importSharedImage(uri: Uri): CaptureResult {
        val config = configStore.snapshot()
        val importedImage = runCatching { imageStorageManager.importSharedImage(uri) }.getOrElse { throwable ->
            return CaptureResult(
                state = CaptureResultState.ERROR,
                message = throwable.userFacingMessage(strings.get(R.string.shared_image_read_failed)),
            )
        }

        if (deduplicator.isDuplicate(importedImage.contentHash)) {
            imageStorageManager.deleteLocalImage(importedImage.filePath)
            return CaptureResult(
                state = CaptureResultState.DUPLICATE,
                message = strings.get(R.string.capture_duplicate_image),
            )
        }

        val shouldStoreHistory = config.localHistoryMode != LocalHistoryMode.DISABLED
        val token = tokenStore.currentToken()
        val isRegistered = !token.isNullOrBlank() && config.serverBaseUrl.isNotBlank()
        val shouldAttemptUpload = config.syncSendEnabled && isRegistered
        val nowIso = timeProvider.now().toString()
        val record = HistoryItem(
            clientItemId = "${config.deviceUuid}-${UUID.randomUUID()}",
            contentKind = ContentKind.IMAGE,
            imagePath = importedImage.filePath,
            mimeType = importedImage.mimeType,
            skipReason = null,
            contentHash = importedImage.contentHash,
            clientCreatedAt = nowIso,
            syncState = SyncState.PENDING,
        )

        var uploadState = SyncState.PENDING
        var uploadMessage = strings.get(R.string.image_pending_queued)
        var finalRecord = record

        if (shouldAttemptUpload) {
            if (imageExceedsServerUploadLimit(config, importedImage.fileSize)) {
                if (!shouldStoreHistory) {
                    imageStorageManager.deleteLocalImage(importedImage.filePath)
                    return CaptureResult(
                        state = CaptureResultState.DROPPED,
                        message = strings.get(R.string.image_dropped_server_limit_no_history),
                    )
                }
                uploadState = SyncState.SKIPPED
                uploadMessage = strings.get(R.string.image_saved_server_limit_local_only)
                finalRecord = record.copy(
                    syncState = SyncState.SKIPPED,
                    skipReason = IMAGE_SKIP_REASON_SERVER_LIMIT,
                )
            } else {
                runCatching {
                    apiClient.uploadImage(
                        serverBaseUrl = config.serverBaseUrl,
                        deviceToken = token!!,
                        request = ImageUploadRequest(
                            clientItemId = record.clientItemId,
                            uploadKind = "realtime",
                            clientCreatedAt = record.clientCreatedAt,
                            mimeType = importedImage.mimeType,
                            fileName = importedImage.fileName,
                            file = File(importedImage.filePath),
                        ),
                    )
                }.onSuccess { response ->
                    uploadState = SyncState.SYNCED
                    uploadMessage = strings.get(R.string.image_synced)
                    finalRecord = record.copy(
                        syncState = SyncState.SYNCED,
                        skipReason = null,
                        serverCursor = response.item.serverCursor,
                        serverItemId = response.item.id,
                    )
                    historyStore.updateRuntime {
                        it.copy(
                            lastError = null,
                            lastSuccessfulUploadAt = nowIso,
                        )
                    }
                }.onFailure { throwable ->
                    uploadState = SyncState.PENDING
                    uploadMessage = throwable.userFacingMessage(strings.get(R.string.image_upload_failed_saved))
                    historyStore.updateRuntime { runtime ->
                        runtime.copy(lastError = uploadMessage)
                    }
                }
            }
        } else if (!isRegistered && shouldStoreHistory) {
            uploadState = SyncState.PENDING
            uploadMessage = strings.get(R.string.image_saved_unregistered)
        } else if (!config.syncSendEnabled && shouldStoreHistory) {
            uploadState = SyncState.PENDING
            uploadMessage = strings.get(R.string.image_saved_sync_disabled)
        } else if (!shouldStoreHistory) {
            imageStorageManager.deleteLocalImage(importedImage.filePath)
            return CaptureResult(
                state = CaptureResultState.DROPPED,
                message = if (shouldAttemptUpload) {
                    strings.get(R.string.image_dropped_after_upload_fail_history_disabled)
                } else {
                    strings.get(R.string.image_dropped_history_disabled)
                },
            )
        }

        if (!shouldStoreHistory) {
            imageStorageManager.deleteLocalImage(importedImage.filePath)
            return if (uploadState == SyncState.SYNCED) {
                CaptureResult(
                    state = CaptureResultState.SYNCED,
                    message = uploadMessage,
                )
            } else {
                CaptureResult(
                    state = CaptureResultState.DROPPED,
                    message = strings.get(R.string.image_history_disabled_no_pending),
                )
            }
        }

        finalRecord = finalRecord.copy(
            syncState = uploadState,
            skipReason = if (uploadState == SyncState.SKIPPED) {
                finalRecord.skipReason ?: IMAGE_SKIP_REASON_SERVER_LIMIT
            } else {
                null
            },
        )
        historyStore.upsertRecord(finalRecord)
        historyStore.prune(config.localHistoryMode, config.localHistoryRetentionDays)

        if (shouldAttemptUpload && uploadState == SyncState.SYNCED) {
            appScope.launch {
                flushPendingHistory(manual = false)
            }
        }

        return CaptureResult(
            state = when (uploadState) {
                SyncState.SYNCED -> CaptureResultState.SYNCED
                SyncState.PENDING -> CaptureResultState.PENDING
                SyncState.FAILED -> CaptureResultState.ERROR
                SyncState.SKIPPED -> CaptureResultState.SKIPPED
            },
            message = uploadMessage,
            record = finalRecord,
        )
    }

    suspend fun flushPendingHistory(manual: Boolean): Result<String> = runCatching {
        val config = configStore.snapshot()
        val token = tokenStore.currentToken()
        require(!token.isNullOrBlank() && config.serverBaseUrl.isNotBlank()) { strings.get(R.string.not_registered) }
        if (config.historySyncWifiOnly && !wifiStatusProvider.isOnWifi()) {
            throw IllegalStateException(strings.get(R.string.wifi_only_sync_required))
        }

        val pending = historyStore.pendingRecords(limit = max(config.serverSettings.textBatchLimit, 1_000))
        if (pending.isEmpty()) {
            return@runCatching if (manual) {
                strings.get(R.string.no_pending_history_manual)
            } else {
                strings.get(R.string.no_pending_history_auto)
            }
        }

        var uploadedCount = 0
        var skippedCount = 0
        val batchLimit = max(config.serverSettings.textBatchLimit, 1)
        val textChunks = pending
            .filter { it.contentKind == ContentKind.TEXT }
            .chunked(batchLimit)

        textChunks.forEach { chunk ->
            if (chunk.size == 1) {
                val record = chunk.first()
                val response = apiClient.uploadText(
                    serverBaseUrl = config.serverBaseUrl,
                    deviceToken = token!!,
                    request = TextUploadRequest(
                        clientItemId = record.clientItemId,
                        uploadKind = "history",
                        clientCreatedAt = record.clientCreatedAt,
                        textContent = record.textContent.orEmpty(),
                    ),
                )
                historyStore.updateSyncResult(
                    clientItemId = record.clientItemId,
                    syncState = SyncState.SYNCED,
                    serverCursor = response.item.serverCursor,
                    serverItemId = response.item.id,
                    skipReason = null,
                )
                uploadedCount += 1
            } else {
                val response = apiClient.uploadTextBatch(
                    serverBaseUrl = config.serverBaseUrl,
                    deviceToken = token!!,
                    request = TextBatchUploadRequest(
                        items = chunk.map { record ->
                            TextUploadRequest(
                                clientItemId = record.clientItemId,
                                uploadKind = "history",
                                clientCreatedAt = record.clientCreatedAt,
                                textContent = record.textContent.orEmpty(),
                            )
                        },
                    ),
                )
                response.results.forEach { result ->
                    val item = result.item ?: return@forEach
                    historyStore.updateSyncResult(
                        clientItemId = result.clientItemId,
                        syncState = SyncState.SYNCED,
                        serverCursor = item.serverCursor,
                        serverItemId = item.id,
                        skipReason = null,
                    )
                    uploadedCount += 1
                }
            }
        }

        pending
            .filter { it.contentKind == ContentKind.IMAGE }
            .forEach { record ->
                val imagePath = record.imagePath
                if (imagePath.isNullOrBlank() || !File(imagePath).exists()) {
                    historyStore.updateSyncResult(
                        clientItemId = record.clientItemId,
                        syncState = SyncState.SKIPPED,
                        serverCursor = record.serverCursor,
                        serverItemId = record.serverItemId,
                        skipReason = IMAGE_SKIP_REASON_FILE_MISSING,
                    )
                    skippedCount += 1
                    return@forEach
                }

                if (imageExceedsServerUploadLimit(config, File(imagePath).length())) {
                    historyStore.updateSyncResult(
                        clientItemId = record.clientItemId,
                        syncState = SyncState.SKIPPED,
                        serverCursor = record.serverCursor,
                        serverItemId = record.serverItemId,
                        skipReason = IMAGE_SKIP_REASON_SERVER_LIMIT,
                    )
                    skippedCount += 1
                    return@forEach
                }

                val response = apiClient.uploadImage(
                    serverBaseUrl = config.serverBaseUrl,
                    deviceToken = token!!,
                    request = ImageUploadRequest(
                        clientItemId = record.clientItemId,
                        uploadKind = "history",
                        clientCreatedAt = record.clientCreatedAt,
                        mimeType = record.mimeType ?: "image/jpeg",
                        fileName = File(imagePath).name,
                        file = File(imagePath),
                    ),
                )
                historyStore.updateSyncResult(
                    clientItemId = record.clientItemId,
                    syncState = SyncState.SYNCED,
                    serverCursor = response.item.serverCursor,
                    serverItemId = response.item.id,
                    skipReason = null,
                )
                uploadedCount += 1
            }

        historyStore.updateRuntime {
            it.copy(
                lastError = null,
                lastSuccessfulUploadAt = timeProvider.now().toString(),
            )
        }
        buildHistorySyncSummary(uploadedCount, skippedCount)
    }.onFailure { throwable ->
        historyStore.updateRuntime { runtime ->
            runtime.copy(lastError = throwable.userFacingMessage(strings.get(R.string.history_sync_failed)))
        }
    }

    suspend fun sendHeartbeat(): Result<HeartbeatResponse> = runCatching {
        val config = configStore.snapshot()
        val token = tokenStore.currentToken()
        require(!token.isNullOrBlank() && config.serverBaseUrl.isNotBlank()) { strings.get(R.string.not_registered) }
        val response = apiClient.heartbeat(
            serverBaseUrl = config.serverBaseUrl,
            deviceToken = token,
        )
        applySettingsFromPayload(
            websocketUrl = null,
            serverCursor = null,
            payload = response.settings,
        )
        historyStore.updateRuntime {
            it.copy(
                lastError = null,
                lastSuccessfulHeartbeatAt = response.serverAt ?: timeProvider.now().toString(),
            )
        }
        response
    }.onFailure { throwable ->
        historyStore.updateRuntime { runtime ->
            runtime.copy(lastError = throwable.userFacingMessage(strings.get(R.string.heartbeat_failed)))
        }
    }

    suspend fun updateTextSettings(
        syncSendEnabled: Boolean? = null,
        receiveRealtimeEnabled: Boolean? = null,
        localHistoryMode: LocalHistoryMode? = null,
        localHistoryRetentionDays: Int? = null,
        textCaptureMinChars: Int? = null,
        textCaptureMaxChars: Int? = null,
        historySyncWifiOnly: Boolean? = null,
    ): Boolean {
        val current = configStore.snapshot()
        val targetHistoryMode = localHistoryMode ?: current.localHistoryMode
        val targetRetentionDays = localHistoryRetentionDays ?: current.localHistoryRetentionDays

        configStore.updateConfig {
            current.copy(
                syncSendEnabled = syncSendEnabled ?: current.syncSendEnabled,
                receiveRealtimeEnabled = receiveRealtimeEnabled ?: current.receiveRealtimeEnabled,
                localHistoryMode = targetHistoryMode,
                localHistoryRetentionDays = targetRetentionDays,
                textCaptureMinChars = textCaptureMinChars ?: current.textCaptureMinChars,
                textCaptureMaxChars = textCaptureMaxChars ?: current.textCaptureMaxChars,
                historySyncWifiOnly = historySyncWifiOnly ?: current.historySyncWifiOnly,
            )
        }

        val clearedHistory = current.localHistoryMode != LocalHistoryMode.DISABLED &&
            targetHistoryMode == LocalHistoryMode.DISABLED
        if (clearedHistory) {
            historyStore.clearHistory()
        } else if (targetHistoryMode == LocalHistoryMode.DAYS) {
            historyStore.prune(targetHistoryMode, targetRetentionDays)
        }
        return clearedHistory
    }

    private suspend fun applySettingsFromPayload(
        websocketUrl: String?,
        serverCursor: Long?,
        payload: SettingsPayload,
    ) {
        configStore.applyServerSettings(
            websocketUrl = websocketUrl,
            serverCursor = serverCursor,
            realtimeFanoutEnabled = payload.realtimeFanoutEnabled,
            textBatchLimit = payload.textBatchLimit,
            imageMaxBytes = payload.imageMaxBytes,
        )
        historyStore.updateRuntime { runtime ->
            runtime.copy(lastRemoteCursor = max(runtime.lastRemoteCursor, serverCursor ?: runtime.lastRemoteCursor))
        }
    }

    private fun buildHistorySyncSummary(uploadedCount: Int, skippedCount: Int): String {
        val uploadedSummary = strings.quantity(R.plurals.history_synced_count, uploadedCount, uploadedCount)
        if (skippedCount <= 0) return uploadedSummary
        val skippedSummary = strings.quantity(R.plurals.history_skipped_images_count, skippedCount, skippedCount)
        return "$uploadedSummary $skippedSummary"
    }
}

class CaptureCoordinator(
    private val syncCoordinator: SyncCoordinator,
) {
    suspend fun captureOnce(): CaptureResult = syncCoordinator.captureCurrentClipboardWithRetry()
}

class ReceiveCoordinator(
    private val configStore: ConfigStore,
    private val tokenStore: TokenStore,
    private val historyStore: HistoryStore,
    private val clipboardGateway: ClipboardTextGateway,
    private val imageStorageManager: ImageStorageManager,
    private val apiClient: ServerGateway,
    private val suppressionRegistry: SuppressionRegistry,
    private val sessionManager: RealtimeSessionManager,
    private val strings: AppStrings,
) {
    private val mutableState = MutableStateFlow(ReceiveState())
    val state = mutableState.asStateFlow()

    init {
        sessionManager.remoteItems
            .let { flow ->
                sessionManager.scope.launch {
                    flow.collect { item ->
                        handleRemoteItem(item)
                    }
                }
            }
    }

    fun showDisabledMessage() {
        mutableState.value = ReceiveState(
            status = strings.get(R.string.receive_disabled_status),
            latestError = strings.get(R.string.receive_disabled_error),
            terminal = true,
        )
    }

    fun showRegistrationRequiredMessage() {
        mutableState.value = ReceiveState(
            status = strings.get(R.string.receive_registration_required_status),
            latestError = strings.get(R.string.receive_registration_required_error),
            terminal = true,
        )
    }

    fun prepareForSession() {
        mutableState.value = ReceiveState(
            status = strings.get(R.string.receive_waiting_remote),
        )
    }

    private suspend fun handleRemoteItem(item: RemoteClipboardItem) {
        when (item.contentKind) {
            ContentKind.TEXT -> handleRemoteTextItem(item)
            ContentKind.IMAGE -> handleRemoteImageItem(item)
        }
    }

    private suspend fun handleRemoteTextItem(item: RemoteClipboardItem) {
        val text = item.textContent ?: return
        val applySucceeded = clipboardGateway.writeText(text)
        suppressionRegistry.register(text.sha256())
        configStore.updateConfig { current ->
            current.copy(lastRemoteCursor = max(current.lastRemoteCursor, item.serverCursor))
        }
        historyStore.updateRuntime { runtime ->
            runtime.copy(
                lastRemoteCursor = max(runtime.lastRemoteCursor, item.serverCursor),
                lastError = if (applySucceeded) {
                    null
                } else {
                    strings.get(R.string.receive_text_write_failed_runtime)
                },
            )
        }
        sessionManager.ack(item.serverCursor)
        mutableState.value = ReceiveState(
            status = if (applySucceeded) {
                strings.get(R.string.receive_text_success_status)
            } else {
                strings.get(R.string.receive_text_clipboard_failed_status)
            },
            latestPreview = text,
            latestReceivedAt = item.receivedAt,
            latestOpenUri = null,
            latestError = if (applySucceeded) null else strings.get(R.string.receive_clipboard_access_error),
            terminal = true,
        )
    }

    private suspend fun handleRemoteImageItem(item: RemoteClipboardItem) {
        val config = configStore.snapshot()
        val token = tokenStore.currentToken()
        if (token.isNullOrBlank() || config.serverBaseUrl.isBlank()) {
            mutableState.value = ReceiveState(
                status = strings.get(R.string.receive_image_failed_status),
                latestPreview = item.blobName.takeUnless { it.isNullOrBlank() }
                    ?: strings.get(R.string.receive_image_default_name),
                latestReceivedAt = item.receivedAt,
                latestOpenUri = null,
                latestError = strings.get(R.string.receive_image_download_requires_registration),
                terminal = true,
            )
            return
        }

        val blobUrl = item.blobUrl?.takeIf { it.isNotBlank() }
            ?: "/api/v1/client/items/${item.serverItemId}/blob"

        runCatching {
            val downloadedBlob = apiClient.downloadBlob(
                serverBaseUrl = config.serverBaseUrl,
                deviceToken = token,
                blobUrl = blobUrl,
            )
            imageStorageManager.saveRemoteImageToPictures(
                blob = downloadedBlob,
                serverItemId = item.serverItemId,
                mimeTypeHint = item.mimeType,
                blobNameHint = item.blobName,
            )
        }.onSuccess { savedImage ->
            configStore.updateConfig { current ->
                current.copy(lastRemoteCursor = max(current.lastRemoteCursor, item.serverCursor))
            }
            historyStore.updateRuntime { runtime ->
                runtime.copy(
                    lastRemoteCursor = max(runtime.lastRemoteCursor, item.serverCursor),
                    lastError = null,
                )
            }
            sessionManager.ack(item.serverCursor)
            mutableState.value = ReceiveState(
                status = strings.get(R.string.receive_image_saved_status),
                latestPreview = strings.get(R.string.receive_image_saved_preview, savedImage.displayName),
                latestReceivedAt = item.receivedAt,
                latestOpenUri = savedImage.uriString,
                terminal = true,
            )
        }.onFailure { throwable ->
            val message = throwable.userFacingMessage(strings.get(R.string.receive_image_download_failed))
            historyStore.updateRuntime { runtime ->
                runtime.copy(lastError = message)
            }
            mutableState.value = ReceiveState(
                status = strings.get(R.string.receive_image_failed_status),
                latestPreview = item.blobName.takeUnless { it.isNullOrBlank() }
                    ?: strings.get(R.string.receive_image_default_name),
                latestReceivedAt = item.receivedAt,
                latestOpenUri = null,
                latestError = message,
                terminal = true,
            )
        }
    }
}

class RealtimeSessionManager(
    val scope: CoroutineScope,
    private val configStore: ConfigStore,
    private val tokenStore: TokenStore,
    private val historyStore: HistoryStore,
    private val apiClient: ServerGateway,
    private val timeProvider: TimeProvider,
    private val strings: AppStrings,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val mutableStatus = MutableStateFlow(RealtimeStatus())
    private val mutableRemoteItems = MutableSharedFlow<RemoteClipboardItem>(extraBufferCapacity = 16)
    private val requestedOwners = linkedSetOf<SessionOwner>()
    private var activeOwner: SessionOwner? = null
    private var sessionJob: Job? = null
    private var webSocket: WebSocket? = null

    val status = mutableStatus.asStateFlow()
    val remoteItems = mutableRemoteItems.asSharedFlow()

    suspend fun requestSession(owner: SessionOwner) {
        requestedOwners += owner
        activateBestOwner()
    }

    suspend fun releaseSession(owner: SessionOwner) {
        requestedOwners -= owner
        if (requestedOwners.isEmpty()) {
            if (activeOwner != null || sessionJob != null || webSocket != null) {
                stopActiveSession(strings.get(R.string.ws_stopped), publishStoppedState = true)
            }
            return
        }
        activateBestOwner()
    }

    fun ack(serverCursor: Long) {
        val socket = webSocket ?: return
        socket.send(
            json.encodeToString(
                AckMessage.serializer(),
                AckMessage(payload = AckPayload(serverCursor)),
            ),
        )
    }

    private suspend fun connectLoop(owner: SessionOwner) {
        val delays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        var lastFailure: Throwable? = null
        delays.forEachIndexed { index, delayMillis ->
            if (activeOwner != owner) return
            try {
                val config = configStore.snapshot()
                val token = tokenStore.currentToken()
                if (token.isNullOrBlank() || config.serverBaseUrl.isBlank()) {
                    updateStatus(owner, false, strings.get(R.string.ws_registration_required), index, null)
                    return
                }
                if (!config.receiveRealtimeEnabled) {
                    updateStatus(owner, false, strings.get(R.string.ws_receive_disabled), index, null)
                    return
                }

                updateStatus(owner, false, strings.get(R.string.ws_connecting), index + 1, null)
                val heartbeat = apiClient.heartbeat(
                    serverBaseUrl = config.serverBaseUrl,
                    deviceToken = token,
                )
                configStore.applyServerSettings(
                    realtimeFanoutEnabled = heartbeat.settings.realtimeFanoutEnabled,
                    textBatchLimit = heartbeat.settings.textBatchLimit,
                    imageMaxBytes = heartbeat.settings.imageMaxBytes,
                )
                historyStore.updateRuntime { runtime ->
                    runtime.copy(
                        lastError = null,
                        lastSuccessfulHeartbeatAt = heartbeat.serverAt ?: timeProvider.now().toString(),
                    )
                }

                val websocketUrl = apiClient.resolveWebSocketUrl(configStore.snapshot())
                openSocket(
                    owner = owner,
                    websocketUrl = websocketUrl,
                    deviceToken = token,
                    deviceUuid = config.deviceUuid,
                    lastCursor = max(config.lastRemoteCursor, historyStore.currentRuntime().lastRemoteCursor),
                )
                if (activeOwner != owner) return
                throw IOException("WebSocket closed.")
            } catch (_: CancellationException) {
                return
            } catch (throwable: Throwable) {
                if (activeOwner != owner) return
                lastFailure = throwable
                updateStatus(
                    owner,
                    false,
                    strings.get(R.string.ws_connect_failed_retry),
                    index + 1,
                    throwable.userFacingMessage(strings.get(R.string.ws_connect_failed)),
                )
                historyStore.updateRuntime { runtime ->
                    runtime.copy(lastError = throwable.userFacingMessage(strings.get(R.string.ws_connect_failed)))
                }
                if (index == delays.lastIndex) {
                    return@forEachIndexed
                }
                delay(delayMillis)
            }
        }

        updateStatus(
            owner = owner,
            connected = false,
            stateLabel = strings.get(R.string.ws_reconnect_stopped),
            attempt = 5,
            lastError = lastFailure?.userFacingMessage(strings.get(R.string.ws_reconnect_stopped_error)),
        )
    }

    private suspend fun openSocket(
        owner: SessionOwner,
        websocketUrl: String,
        deviceToken: String,
        deviceUuid: String,
        lastCursor: Long,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@RealtimeSessionManager.webSocket = webSocket
                updateStatusAsync(owner, true, strings.get(R.string.ws_handshaking), 0, null)
                webSocket.send(
                    json.encodeToString(
                        HelloMessage.serializer(),
                        HelloMessage(
                            payload = HelloPayload(
                                deviceToken = deviceToken,
                                deviceUuid = deviceUuid,
                                lastCursor = lastCursor,
                            ),
                        ),
                    ),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = runCatching {
                    json.decodeFromString(WebSocketEnvelope.serializer(), text)
                }.getOrNull() ?: return
                when (envelope.type) {
                    "hello_ack" -> {
                        val payload = envelope.payload ?: return
                        updateStatusAsync(owner, true, strings.get(R.string.ws_online), 0, null)
                        scope.launch {
                            configStore.applyServerSettings(
                                realtimeFanoutEnabled = payload.settings.realtimeFanoutEnabled,
                                textBatchLimit = payload.settings.textBatchLimit,
                                imageMaxBytes = payload.settings.imageMaxBytes,
                            )
                            historyStore.updateRuntime { runtime ->
                                runtime.copy(
                                    lastError = null,
                                    websocketState = strings.get(R.string.ws_online),
                                )
                            }
                        }
                    }

                    "clipboard.created" -> {
                        val remoteItem = envelope.item?.toRemoteClipboardItem() ?: return
                        mutableRemoteItems.tryEmit(remoteItem)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@RealtimeSessionManager.webSocket = null
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@RealtimeSessionManager.webSocket = null
                if (continuation.isActive) {
                    continuation.resumeWithException(t)
                }
            }
        }

        val socket = apiClient.openWebSocket(websocketUrl, listener)
        continuation.invokeOnCancellation {
            socket.cancel()
            webSocket = null
        }
    }

    private suspend fun activateBestOwner() {
        val targetOwner = requestedOwners.maxByOrNull { it.priority }
        if (targetOwner == null) {
            if (activeOwner != null || sessionJob != null || webSocket != null) {
                stopActiveSession(strings.get(R.string.ws_stopped), publishStoppedState = true)
            }
            return
        }
        if (activeOwner == targetOwner && sessionJob?.isActive == true) {
            return
        }

        stopActiveSession(strings.get(R.string.ws_switching_session), publishStoppedState = false)
        activeOwner = targetOwner
        sessionJob = scope.launch(Dispatchers.IO) {
            connectLoop(targetOwner)
        }
    }

    private suspend fun stopActiveSession(
        reason: String,
        publishStoppedState: Boolean,
    ) {
        activeOwner = null
        sessionJob?.cancel()
        sessionJob = null
        webSocket?.close(1000, reason)
        webSocket = null
        if (publishStoppedState) {
            updateStatus(
                owner = null,
                connected = false,
                stateLabel = reason,
                attempt = 0,
                lastError = null,
            )
        }
    }

    private suspend fun updateStatus(
        owner: SessionOwner?,
        connected: Boolean,
        stateLabel: String,
        attempt: Int,
        lastError: String?,
    ) {
        mutableStatus.value = RealtimeStatus(
            owner = owner,
            connected = connected,
            stateLabel = stateLabel,
            attempt = attempt,
            lastError = lastError,
        )
        historyStore.updateRuntime { runtime ->
            runtime.copy(
                websocketState = stateLabel,
                lastError = lastError ?: runtime.lastError,
            )
        }
    }

    private fun updateStatusAsync(
        owner: SessionOwner?,
        connected: Boolean,
        stateLabel: String,
        attempt: Int,
        lastError: String?,
    ) {
        scope.launch {
            updateStatus(owner, connected, stateLabel, attempt, lastError)
        }
    }
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun imageExceedsServerUploadLimit(
    config: AppConfig,
    sizeBytes: Long,
): Boolean {
    val limit = config.serverSettings.imageMaxBytes ?: return false
    return limit > 0L && sizeBytes > limit
}

private const val IMAGE_SKIP_REASON_SERVER_LIMIT = "image_server_limit"
private const val IMAGE_SKIP_REASON_FILE_MISSING = "image_file_missing"

private fun Throwable.userFacingMessage(defaultMessage: String): String = when (this) {
    is ApiException -> if (message.isNullOrBlank()) defaultMessage else message!!
    is IllegalArgumentException -> message ?: defaultMessage
    is IllegalStateException -> message ?: defaultMessage
    else -> message ?: defaultMessage
}

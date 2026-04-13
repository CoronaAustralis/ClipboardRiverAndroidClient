package com.clipboardriver.client.sync

import com.clipboardriver.client.model.AppConfig
import com.clipboardriver.client.model.HistoryItem
import com.clipboardriver.client.model.LocalHistoryMode
import com.clipboardriver.client.model.RuntimeState
import com.clipboardriver.client.model.ServerSettings
import com.clipboardriver.client.model.SyncState
import com.clipboardriver.client.model.TimeProvider
import com.clipboardriver.client.net.BasicOkResponse
import com.clipboardriver.client.net.ClientCapabilities
import com.clipboardriver.client.net.DeviceProfileRequest
import com.clipboardriver.client.net.HeartbeatResponse
import com.clipboardriver.client.net.RegisterRequest
import com.clipboardriver.client.net.RegisterResponse
import com.clipboardriver.client.net.ServerGateway
import com.clipboardriver.client.net.SettingsPayload
import com.clipboardriver.client.net.TextBatchUploadRequest
import com.clipboardriver.client.net.TextBatchUploadResponse
import com.clipboardriver.client.net.TextBatchUploadResult
import com.clipboardriver.client.net.TextUploadRequest
import com.clipboardriver.client.net.TextUploadResponse
import com.clipboardriver.client.net.UploadedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SyncCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun capture_whenSendDisabled_keepsPendingHistory() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                syncSendEnabled = false,
                localHistoryMode = LocalHistoryMode.DAYS,
                localHistoryRetentionDays = 30,
                textCaptureMinChars = 2,
                textCaptureMaxChars = 1024,
            ),
        )
        val historyStore = FakeHistoryStore()
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore(null),
            historyStore = historyStore,
            clipboard = FakeClipboard("hello world"),
            wifi = FakeWifiStatusProvider(true),
            gateway = FakeServerGateway(),
        )

        val result = coordinator.captureCurrentClipboard()

        assertEquals("设备尚未注册，文本已保存在本地历史中。", result.message)
        assertEquals(SyncState.PENDING, historyStore.visibleHistory().first().first().syncState)
    }

    @Test
    fun capture_withRetry_readsClipboardAfterForegroundFocusSettles() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                syncSendEnabled = false,
                localHistoryMode = LocalHistoryMode.DAYS,
                localHistoryRetentionDays = 30,
                textCaptureMinChars = 2,
                textCaptureMaxChars = 1024,
            ),
        )
        val historyStore = FakeHistoryStore()
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore(null),
            historyStore = historyStore,
            clipboard = FakeClipboard(listOf(null, "hello world")),
            wifi = FakeWifiStatusProvider(true),
            gateway = FakeServerGateway(),
        )

        val result = coordinator.captureCurrentClipboardWithRetry(maxAttempts = 3, retryDelayMillis = 0)

        assertEquals("设备尚未注册，文本已保存在本地历史中。", result.message)
        assertEquals("hello world", historyStore.visibleHistory().first().first().textContent)
    }

    @Test
    fun capture_whenHistoryDisabledAndUploadFails_dropsRecord() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                serverBaseUrl = "https://example.com",
                syncSendEnabled = true,
                localHistoryMode = LocalHistoryMode.DISABLED,
            ),
        )
        val historyStore = FakeHistoryStore()
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore("device-token"),
            historyStore = historyStore,
            clipboard = FakeClipboard("hello world"),
            wifi = FakeWifiStatusProvider(true),
            gateway = FakeServerGateway(uploadFailure = IOException("boom")),
        )

        val result = coordinator.captureCurrentClipboard()

        assertEquals("本地历史已关闭，实时上传失败后不会保留待补传记录。", result.message)
        assertTrue(historyStore.visibleHistory().first().isEmpty())
    }

    @Test
    fun flushHistory_whenWifiOnlyAndNotOnWifi_blocksUpload() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                serverBaseUrl = "https://example.com",
                syncSendEnabled = true,
                historySyncWifiOnly = true,
                localHistoryMode = LocalHistoryMode.DAYS,
                serverSettings = ServerSettings(textBatchLimit = 50),
            ),
        )
        val historyStore = FakeHistoryStore(
            items = listOf(
                HistoryItem(
                    clientItemId = "item-1",
                    textContent = "hello",
                    contentHash = "hash-1",
                    clientCreatedAt = Instant.parse("2026-04-11T08:00:00Z").toString(),
                    syncState = SyncState.PENDING,
                ),
            ),
        )
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore("device-token"),
            historyStore = historyStore,
            clipboard = FakeClipboard(null),
            wifi = FakeWifiStatusProvider(false),
            gateway = FakeServerGateway(),
        )

        val result = coordinator.flushPendingHistory(manual = true)

        assertTrue(result.isFailure)
        assertEquals(SyncState.PENDING, historyStore.pendingRecords().first().syncState)
    }

    @Test
    fun flushHistory_singlePendingItem_marksRecordSynced() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                serverBaseUrl = "https://example.com",
                syncSendEnabled = true,
                historySyncWifiOnly = false,
                localHistoryMode = LocalHistoryMode.DAYS,
                serverSettings = ServerSettings(textBatchLimit = 50),
            ),
        )
        val historyStore = FakeHistoryStore(
            items = listOf(
                HistoryItem(
                    clientItemId = "item-1",
                    textContent = "hello",
                    contentHash = "hash-1",
                    clientCreatedAt = Instant.parse("2026-04-11T08:00:00Z").toString(),
                    syncState = SyncState.PENDING,
                ),
            ),
        )
        val gateway = FakeServerGateway()
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore("device-token"),
            historyStore = historyStore,
            clipboard = FakeClipboard(null),
            wifi = FakeWifiStatusProvider(true),
            gateway = gateway,
        )

        val result = coordinator.flushPendingHistory(manual = true)

        assertTrue(result.isSuccess)
        assertEquals(SyncState.SYNCED, historyStore.visibleHistory().first().first().syncState)
        assertEquals(1, gateway.uploadedItems.size)
    }

    @Test
    fun updateTextSettings_whenHistoryModeSwitchedToDisabled_clearsHistory() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                localHistoryMode = LocalHistoryMode.DAYS,
                localHistoryRetentionDays = 30,
            ),
        )
        val historyStore = FakeHistoryStore(
            items = listOf(
                HistoryItem(
                    clientItemId = "item-1",
                    textContent = "hello",
                    contentHash = "hash-1",
                    clientCreatedAt = Instant.parse("2026-04-11T08:00:00Z").toString(),
                    syncState = SyncState.PENDING,
                ),
            ),
        )
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore(null),
            historyStore = historyStore,
            clipboard = FakeClipboard(null),
            wifi = FakeWifiStatusProvider(true),
            gateway = FakeServerGateway(),
        )

        val clearedHistory = coordinator.updateTextSettings(localHistoryMode = LocalHistoryMode.DISABLED)

        assertTrue(clearedHistory)
        assertEquals(LocalHistoryMode.DISABLED, configStore.snapshot().localHistoryMode)
        assertTrue(historyStore.visibleHistory().first().isEmpty())
    }

    @Test
    fun updateTextSettings_whenHistoryModeSwitchedToDays_prunesExpiredHistory() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                localHistoryMode = LocalHistoryMode.FOREVER,
                localHistoryRetentionDays = 30,
            ),
        )
        val historyStore = FakeHistoryStore()
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore(null),
            historyStore = historyStore,
            clipboard = FakeClipboard(null),
            wifi = FakeWifiStatusProvider(true),
            gateway = FakeServerGateway(),
        )

        coordinator.updateTextSettings(localHistoryMode = LocalHistoryMode.DAYS)

        assertEquals(LocalHistoryMode.DAYS, configStore.snapshot().localHistoryMode)
        assertEquals(30, historyStore.lastPruneRetentionDays)
    }

    @Test
    fun updateTextSettings_whenRetentionDaysChangedInDaysMode_prunesImmediately() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                localHistoryMode = LocalHistoryMode.DAYS,
                localHistoryRetentionDays = 30,
            ),
        )
        val historyStore = FakeHistoryStore()
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore(null),
            historyStore = historyStore,
            clipboard = FakeClipboard(null),
            wifi = FakeWifiStatusProvider(true),
            gateway = FakeServerGateway(),
        )

        coordinator.updateTextSettings(localHistoryRetentionDays = 7)

        assertEquals(7, configStore.snapshot().localHistoryRetentionDays)
        assertEquals(7, historyStore.lastPruneRetentionDays)
    }

    @Test
    fun updateTextSettings_whenSwitchingToDaysWithSpecificRetention_prunesWithThatRetention() = runBlocking {
        val configStore = FakeConfigStore(
            AppConfig(
                localHistoryMode = LocalHistoryMode.FOREVER,
                localHistoryRetentionDays = 30,
            ),
        )
        val historyStore = FakeHistoryStore()
        val coordinator = createCoordinator(
            configStore = configStore,
            tokenStore = FakeTokenStore(null),
            historyStore = historyStore,
            clipboard = FakeClipboard(null),
            wifi = FakeWifiStatusProvider(true),
            gateway = FakeServerGateway(),
        )

        coordinator.updateTextSettings(
            localHistoryMode = LocalHistoryMode.DAYS,
            localHistoryRetentionDays = 14,
        )

        assertEquals(LocalHistoryMode.DAYS, configStore.snapshot().localHistoryMode)
        assertEquals(14, configStore.snapshot().localHistoryRetentionDays)
        assertEquals(14, historyStore.lastPruneRetentionDays)
    }

    private fun createCoordinator(
        configStore: FakeConfigStore,
        tokenStore: FakeTokenStore,
        historyStore: FakeHistoryStore,
        clipboard: FakeClipboard,
        wifi: FakeWifiStatusProvider,
        gateway: FakeServerGateway,
    ): SyncCoordinator = SyncCoordinator(
        configStore = configStore,
        tokenStore = tokenStore,
        historyStore = historyStore,
        clipboardGateway = clipboard,
        wifiStatusProvider = wifi,
        apiClient = gateway,
        timeProvider = object : TimeProvider {
            override fun now(): Instant = Instant.parse("2026-04-11T08:00:00Z")
        },
        suppressionRegistry = SuppressionRegistry(
            timeProvider = object : TimeProvider {
                override fun now(): Instant = Instant.parse("2026-04-11T08:00:00Z")
            },
        ),
        deduplicator = LocalCaptureDeduplicator(
            timeProvider = object : TimeProvider {
                override fun now(): Instant = Instant.parse("2026-04-11T08:00:00Z")
            },
        ),
        appScope = scope,
    )
}

private class FakeConfigStore(
    initial: AppConfig,
) : ConfigStore {
    private val state = MutableStateFlow(initial)
    override val configFlow: Flow<AppConfig> = state

    override suspend fun snapshot(): AppConfig = state.value

    override suspend fun initializeDeviceUuidIfNeeded(): AppConfig = state.value

    override suspend fun updateConfig(transform: (AppConfig) -> AppConfig): AppConfig {
        state.value = transform(state.value)
        return state.value
    }

    override suspend fun applyServerSettings(
        websocketUrl: String?,
        serverCursor: Long?,
        realtimeFanoutEnabled: Boolean?,
        textBatchLimit: Int?,
        imageMaxBytes: Long?,
    ): AppConfig = updateConfig { current ->
        current.copy(
            websocketUrl = websocketUrl ?: current.websocketUrl,
            lastRemoteCursor = serverCursor ?: current.lastRemoteCursor,
            serverSettings = current.serverSettings.copy(
                realtimeFanoutEnabled = realtimeFanoutEnabled ?: current.serverSettings.realtimeFanoutEnabled,
                textBatchLimit = textBatchLimit ?: current.serverSettings.textBatchLimit,
                imageMaxBytes = imageMaxBytes ?: current.serverSettings.imageMaxBytes,
            ),
        )
    }
}

private class FakeTokenStore(initialToken: String?) : TokenStore {
    private val state = MutableStateFlow(initialToken)
    override val tokenFlow: Flow<String?> = state

    override suspend fun currentToken(): String? = state.value

    override suspend fun setToken(token: String?) {
        state.value = token
    }
}

private class FakeHistoryStore(
    items: List<HistoryItem> = emptyList(),
    runtime: RuntimeState = RuntimeState(),
) : HistoryStore {
    private val historyState = MutableStateFlow(items)
    private val runtimeState = MutableStateFlow(runtime)
    var lastPruneRetentionDays: Int? = null
        private set

    override fun visibleHistory(limit: Int): Flow<List<HistoryItem>> = historyState.map { it.take(limit) }

    override fun pendingCount(): Flow<Int> = historyState.map { list -> list.count { it.syncState != SyncState.SYNCED } }

    override fun runtimeState(): Flow<RuntimeState> = runtimeState

    override suspend fun currentRuntime(): RuntimeState = runtimeState.value

    override suspend fun upsertRecord(item: HistoryItem) {
        historyState.value = historyState.value.filterNot { it.clientItemId == item.clientItemId } + item
    }

    override suspend fun updateSyncResult(
        clientItemId: String,
        syncState: SyncState,
        serverCursor: Long?,
        serverItemId: Long?,
    ) {
        historyState.value = historyState.value.map { item ->
            if (item.clientItemId == clientItemId) {
                item.copy(syncState = syncState, serverCursor = serverCursor, serverItemId = serverItemId)
            } else {
                item
            }
        }
    }

    override suspend fun pendingRecords(limit: Int): List<HistoryItem> =
        historyState.value.filter { it.syncState != SyncState.SYNCED }.take(limit)

    override suspend fun prune(mode: LocalHistoryMode, retentionDays: Int) {
        lastPruneRetentionDays = retentionDays
    }

    override suspend fun clearHistory() {
        historyState.value = emptyList()
    }

    override suspend fun updateRuntime(transform: (RuntimeState) -> RuntimeState) {
        runtimeState.value = transform(runtimeState.value)
    }

    override suspend fun resetRuntime() {
        runtimeState.value = RuntimeState()
    }
}

private class FakeClipboard(
    initialReads: List<String?>,
) : ClipboardTextGateway {
    private val readQueue = ArrayDeque(initialReads)
    private var text: String? = initialReads.lastOrNull()

    constructor(text: String?) : this(listOf(text))

    override fun readText(): String? {
        if (readQueue.isNotEmpty()) {
            text = readQueue.removeFirst()
        }
        return text
    }

    override fun writeText(text: String): Boolean {
        this.text = text
        readQueue.clear()
        return true
    }
}

private class FakeWifiStatusProvider(
    private val onWifi: Boolean,
) : WifiStatusProvider {
    override fun isOnWifi(): Boolean = onWifi
}

private class FakeServerGateway(
    private val uploadFailure: IOException? = null,
) : ServerGateway {
    val uploadedItems = mutableListOf<TextUploadRequest>()

    override suspend fun register(serverBaseUrl: String, request: RegisterRequest): RegisterResponse =
        RegisterResponse(
            deviceId = 1,
            deviceToken = "device-token",
            wsUrl = "wss://example.com/ws",
            settings = SettingsPayload(),
            serverCursor = 0,
        )

    override suspend fun updateProfile(
        serverBaseUrl: String,
        deviceToken: String,
        request: DeviceProfileRequest,
    ): BasicOkResponse = BasicOkResponse(ok = true)

    override suspend fun heartbeat(serverBaseUrl: String, deviceToken: String): HeartbeatResponse =
        HeartbeatResponse(ok = true, serverAt = Instant.parse("2026-04-11T08:00:00Z").toString(), settings = SettingsPayload())

    override suspend fun uploadText(
        serverBaseUrl: String,
        deviceToken: String,
        request: TextUploadRequest,
    ): TextUploadResponse {
        uploadFailure?.let { throw it }
        uploadedItems += request
        return TextUploadResponse(
            created = true,
            item = UploadedItem(
                id = uploadedItems.size.toLong(),
                serverCursor = uploadedItems.size.toLong(),
                clientItemId = request.clientItemId,
            ),
        )
    }

    override suspend fun uploadTextBatch(
        serverBaseUrl: String,
        deviceToken: String,
        request: TextBatchUploadRequest,
    ): TextBatchUploadResponse {
        request.items.forEach { uploadedItems += it }
        return TextBatchUploadResponse(
            results = request.items.mapIndexed { index, item ->
                TextBatchUploadResult(
                    clientItemId = item.clientItemId,
                    created = true,
                    item = UploadedItem(
                        id = (index + 1).toLong(),
                        serverCursor = (index + 1).toLong(),
                        clientItemId = item.clientItemId,
                    ),
                )
            },
        )
    }

    override fun openWebSocket(websocketUrl: String, listener: WebSocketListener): WebSocket = object : WebSocket {
        override fun request(): Request = Request.Builder().url("https://example.com").build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = true

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean = true

        override fun cancel() = Unit
    }

    override fun resolveWebSocketUrl(config: AppConfig): String = config.websocketUrl.ifBlank { "wss://example.com/ws" }
}

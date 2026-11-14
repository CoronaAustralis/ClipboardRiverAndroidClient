package com.clipboardriver.client.sync

import android.net.Uri
import com.clipboardriver.client.BuildConfig
import com.clipboardriver.client.R
import com.clipboardriver.client.StringProvider
import com.clipboardriver.client.model.*
import com.clipboardriver.client.net.*
import com.clipboardriver.client.platform.FileLimitExceeded
import com.clipboardriver.client.platform.FileStorage
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface ConfigStore {
    val configFlow: Flow<AppConfig>
    suspend fun snapshot(): AppConfig
    suspend fun initializeDeviceUuidIfNeeded(): AppConfig
    suspend fun updateConfig(transform: (AppConfig) -> AppConfig): AppConfig
}

interface TokenStore {
    val tokenFlow: Flow<String?>
    suspend fun currentToken(): String?
    suspend fun setToken(token: String?)
}

interface ClipboardTextGateway {
    fun readText(): String?
    fun writeText(text: String): Boolean
}

internal data class Credentials(val baseUrl: String, val token: String)

class SyncCoordinator(
    private val configStore: ConfigStore,
    private val tokenStore: TokenStore,
    private val clipboardGateway: ClipboardTextGateway,
    private val fileStorage: FileStorage,
    private val apiClient: ServerGateway,
    private val sessionManager: RealtimeSessionManager,
    private val timeProvider: TimeProvider,
    private val strings: StringProvider,
) {
    private val credentialMutex = Mutex()
    private val actionMutex = Mutex()
    private val mutableBusy = MutableStateFlow(false)
    private val mutableResult = MutableStateFlow<CaptureResult?>(null)
    val busy = mutableBusy.asStateFlow()
    val lastResult = mutableResult.asStateFlow()

    suspend fun register(serverBaseUrl: String, nickname: String, enrollmentCode: String): Result<Unit> =
        credentialMutex.withLock {
            try {
                require(serverBaseUrl.isNotBlank()) { strings.get(R.string.cr_server_required) }
                require(enrollmentCode.isNotBlank()) { strings.get(R.string.cr_code_required) }
                val config = configStore.initializeDeviceUuidIfNeeded()
                val base = apiClient.normalizeServerBaseUrl(serverBaseUrl)
                val response = apiClient.register(
                    base,
                    RegisterRequest(config.deviceUuid, enrollmentCode.trim(), nickname.trim(), APP_NAME, BuildConfig.VERSION_NAME),
                )
                val settings = response.settings.toSettings()
                require(response.deviceId > 0 && response.deviceToken.isNotBlank()) { strings.get(R.string.cr_invalid_response) }
                // Only replace credentials after the server has accepted registration.
                withContext(NonCancellable) {
                    sessionManager.releaseAll()
                    tokenStore.setToken(null)
                    configStore.updateConfig {
                        it.copy(serverBaseUrl = base, nickname = nickname.trim(), deviceId = response.deviceId, serverSettings = settings)
                    }
                    tokenStore.setToken(response.deviceToken)
                    mutableResult.value = null
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    suspend fun signOut() = credentialMutex.withLock {
        sessionManager.releaseAll()
        tokenStore.setToken(null)
        configStore.updateConfig { it.copy(deviceId = null, serverSettings = ServerSettings()) }
        mutableResult.value = null
    }

    private suspend fun credentials(): Credentials = credentialMutex.withLock {
        val config = configStore.snapshot()
        val token = tokenStore.currentToken()
        check(config.serverBaseUrl.isNotBlank() && !token.isNullOrBlank()) { strings.get(R.string.cr_not_registered) }
        check(!sessionManager.status.value.let { it.state == ConnectionState.AUTH_REQUIRED }) {
            strings.get(R.string.cr_auth_required)
        }
        Credentials(config.serverBaseUrl, token)
    }

    suspend fun refreshProfile(): Result<Unit> = try {
        val credential = credentials()
        profile(credential)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private suspend fun profile(credential: Credentials) {
        try {
            val response = apiClient.updateProfile(
                credential.baseUrl, credential.token, DeviceProfileRequest(APP_NAME, BuildConfig.VERSION_NAME),
            )
            check(response.ok) { strings.get(R.string.cr_invalid_response) }
            val settings = response.settings.toSettings()
            val stillCurrent = tokenStore.currentToken() == credential.token
            configStore.updateConfig {
                if (it.serverBaseUrl == credential.baseUrl && stillCurrent)
                    it.copy(deviceId = response.deviceId, serverSettings = settings)
                else it
            }
        } catch (failure: ApiException) {
            if (failure.code == 401) sessionManager.rejectCredential(credential.token)
            throw failure
        }
    }


    suspend fun captureCurrentClipboard(): CaptureResult = runAction {
        val config = configStore.snapshot()
        if (!config.syncSendEnabled) return@runAction result(CaptureResultState.DISABLED, R.string.cr_send_disabled)
        val credential = credentials()
        val text = clipboardGateway.readText()?.trim()?.takeIf { it.isNotBlank() }
            ?: return@runAction result(CaptureResultState.NO_TEXT, R.string.cr_clipboard_empty)
        if (text.length !in config.textCaptureMinChars..config.textCaptureMaxChars) {
            return@runAction CaptureResult(
                CaptureResultState.FILTERED,
                strings.get(R.string.cr_text_filtered, config.textCaptureMinChars, config.textCaptureMaxChars),
                timeProvider.now().toString(),
            )
        }
        upload(credential) {
            apiClient.uploadText(credential.baseUrl, credential.token, TextUploadRequest(timeProvider.now().toString(), text))
        }
    }

    suspend fun importSharedFile(uri: Uri): CaptureResult = runAction {
        var config = configStore.snapshot()
        if (!config.syncSendEnabled) return@runAction result(CaptureResultState.DISABLED, R.string.cr_send_disabled)
        val credential = credentials()
        if (config.serverSettings.fileMaxBytes == null) {
            profile(credential)
            config = configStore.snapshot()
        }
        val limit = config.serverSettings.fileMaxBytes ?: error(strings.get(R.string.cr_invalid_response))
        if (limit == -1L) return@runAction result(CaptureResultState.DISABLED, R.string.cr_files_disabled)
        val createdAt = timeProvider.now().toString()
        val asset = fileStorage.importSharedFile(uri, limit)
        try {
            upload(credential) {
                apiClient.uploadFile(
                    credential.baseUrl, credential.token,
                    FileUploadRequest(createdAt, asset.mimeType, asset.fileName, asset.file),
                )
            }
        } finally {
            asset.file.delete()
        }
    }

    private suspend fun upload(credential: Credentials, send: suspend () -> UploadReceipt): CaptureResult {
        try {
            val receipt = send()
            check(receipt.created && receipt.item.itemId > 0) { strings.get(R.string.cr_upload_unknown) }
            return CaptureResult(
                CaptureResultState.UPLOADED, strings.get(R.string.cr_uploaded),
                timeProvider.now().toString(), receipt.item.itemId,
            )
        } catch (cancelled: CancellationException) {
            mutableResult.value = result(CaptureResultState.ERROR, R.string.cr_upload_unknown)
            throw cancelled
        } catch (failure: ApiException) {
            if (failure.code == 401) {
                sessionManager.rejectCredential(credential.token)
                return result(CaptureResultState.ERROR, R.string.cr_auth_required)
            }
            return CaptureResult(CaptureResultState.ERROR, failure.message ?: strings.get(R.string.cr_upload_failed))
        } catch (_: Exception) {
            return result(CaptureResultState.ERROR, R.string.cr_upload_unknown)
        }
    }

    private suspend fun runAction(block: suspend () -> CaptureResult): CaptureResult {
        if (!actionMutex.tryLock()) return result(CaptureResultState.BUSY, R.string.cr_busy)
        mutableBusy.value = true
        try {
            val completed = try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FileLimitExceeded) {
                result(CaptureResultState.FILTERED, R.string.cr_file_too_large)
            } catch (failure: Exception) {
                CaptureResult(CaptureResultState.ERROR, failure.message ?: strings.get(R.string.cr_operation_failed))
            }
            mutableResult.value = completed
            return completed
        } finally {
            mutableBusy.value = false
            actionMutex.unlock()
        }
    }

    private fun result(state: CaptureResultState, messageId: Int) =
        CaptureResult(state, strings.get(messageId), timeProvider.now().toString())

    companion object { const val APP_NAME = "Clipboard River Android" }
}

data class RemoteDelivery(
    val item: RemoteClipboardItem,
    val generation: Long,
    val serverBaseUrl: String,
    val deviceToken: String,
)

class ReceiveCoordinator(
    private val clipboardGateway: ClipboardTextGateway,
    private val fileStorage: FileStorage,
    private val apiClient: ServerGateway,
    private val sessionManager: RealtimeSessionManager,
    private val strings: StringProvider,
) {
    private val mutableState = MutableStateFlow(ReceiveState())
    val state = mutableState.asStateFlow()

    init {
        sessionManager.scope.launch {
            // Cancels an in-progress download when its foreground session or credentials end.
            sessionManager.epoch.collectLatest {
                sessionManager.remoteItems.collect { delivery ->
                    if (sessionManager.isCurrent(delivery.generation)) handleRemoteItem(delivery)
                }
            }
        }
    }

    fun clear() { mutableState.value = ReceiveState() }
    fun prepareForSession() { mutableState.value = ReceiveState(status = strings.get(R.string.cr_receive_waiting)) }

    private suspend fun handleRemoteItem(delivery: RemoteDelivery) {
        val item = delivery.item
        if (item.contentKind == ContentKind.TEXT) {
            val text = item.textContent ?: return
            val applied = clipboardGateway.writeText(text)
            mutableState.value = ReceiveState(
                status = strings.get(if (applied) R.string.cr_text_received else R.string.cr_clipboard_write_failed),
                latestPreview = text, latestReceivedAt = item.receivedAt,
                latestError = if (applied) null else strings.get(R.string.cr_clipboard_write_failed),
                terminal = true,
            )
            return
        }
        mutableState.value = ReceiveState(status = strings.get(R.string.cr_downloading), latestPreview = item.blobName, latestReceivedAt = item.receivedAt)
        try {
            val saved = apiClient.downloadBlob(delivery.serverBaseUrl, delivery.deviceToken, item.itemId).use { blob ->
                fileStorage.saveRemoteFile(blob, item.itemId, item.mimeType, item.blobName)
            }
            currentCoroutineContext().ensureActive()
            if (!sessionManager.isCurrent(delivery.generation)) return
            mutableState.value = ReceiveState(
                status = strings.get(R.string.cr_file_received),
                latestPreview = saved.displayName, latestReceivedAt = item.receivedAt,
                latestOpenUri = saved.uriString, latestOpenMimeType = saved.mimeType, terminal = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is ApiException && failure.code == 401) sessionManager.rejectCredential(delivery.deviceToken)
            mutableState.value = ReceiveState(
                status = strings.get(R.string.cr_download_failed), latestPreview = item.blobName,
                latestReceivedAt = item.receivedAt,
                latestError = if (failure is ApiException && failure.code == 401) strings.get(R.string.cr_auth_required)
                    else if (failure is ApiException && failure.code == 404) strings.get(R.string.cr_file_unavailable)
                    else failure.message ?: strings.get(R.string.cr_download_failed),
                terminal = true,
            )
        }
    }
}

class RealtimeSessionManager(
    val scope: CoroutineScope,
    private val configStore: ConfigStore,
    private val tokenStore: TokenStore,
    private val apiClient: ServerGateway,
    private val strings: StringProvider,
) {
    private data class ConnectionConfig(val base: String, val uuid: String, val token: String?, val enabled: Boolean)
    private val mutex = Mutex()
    private val owners = linkedSetOf<SessionOwner>()
    private val mutableStatus = MutableStateFlow(RealtimeStatus())
    private val mutableEpoch = MutableStateFlow(0L)
    private val deliveries = Channel<RemoteDelivery>(Channel.BUFFERED)
    private var config: ConnectionConfig? = null
    private var rejectedToken: String? = null
    private var sessionJob: Job? = null
    private var activeSocket: WebSocket? = null
    val status = mutableStatus.asStateFlow()
    val epoch = mutableEpoch.asStateFlow()
    val remoteItems = deliveries.receiveAsFlow()

    init {
        scope.launch {
            combine(configStore.configFlow, tokenStore.tokenFlow) { cfg, token ->
                ConnectionConfig(cfg.serverBaseUrl, cfg.deviceUuid, token, cfg.receiveRealtimeEnabled)
            }.distinctUntilChanged().collect { updated ->
                mutex.withLock {
                    config = updated
                    if (!updated.enabled) owners.clear()
                    stopLocked()
                    startLocked()
                }
            }
        }
    }

    fun isCurrent(generation: Long): Boolean = epoch.value == generation && owners.isNotEmpty()

    suspend fun requestSession(owner: SessionOwner) = mutex.withLock {
        owners += owner
        startLocked()
    }

    suspend fun releaseSession(owner: SessionOwner) = mutex.withLock {
        owners -= owner
        if (owners.isEmpty()) {
            stopLocked()
            mutableStatus.value = RealtimeStatus()
        }
    }

    suspend fun releaseAll() = mutex.withLock {
        owners.clear()
        stopLocked()
        mutableStatus.value = RealtimeStatus()
    }

    suspend fun reconnect() = mutex.withLock {
        stopLocked()
        startLocked()
    }

    suspend fun rejectCredential(token: String) = mutex.withLock {
        if (tokenStore.currentToken() == token) {
            rejectedToken = token
            stopLocked()
            mutableStatus.value = RealtimeStatus(ConnectionState.AUTH_REQUIRED, lastError = strings.get(R.string.cr_auth_required))
        }
    }

    private fun stopLocked() {
        mutableEpoch.value += 1
        activeSocket?.cancel()
        activeSocket = null
        sessionJob?.cancel()
        sessionJob = null
    }

    private fun startLocked() {
        val cfg = config ?: return
        if (cfg.token != null && cfg.token == rejectedToken) {
            mutableStatus.value = RealtimeStatus(ConnectionState.AUTH_REQUIRED, lastError = strings.get(R.string.cr_auth_required))
            return
        }
        if (owners.isEmpty() || !cfg.enabled || cfg.token.isNullOrBlank() || cfg.base.isBlank() || cfg.uuid.isBlank()) {
            mutableStatus.value = RealtimeStatus()
            return
        }
        if (sessionJob?.isActive == true) return
        val generation = epoch.value
        sessionJob = scope.launch { connectLoop(cfg, generation) }
    }

    private suspend fun connectLoop(cfg: ConnectionConfig, generation: Long) {
        val token = cfg.token ?: return
        val waits = longArrayOf(1_000, 2_000, 4_000, 8_000)
        for (attempt in 1..5) {
            if (epoch.value != generation) return
            mutableStatus.value = RealtimeStatus(ConnectionState.CONNECTING, attempt)
            try {
                if (tokenStore.currentToken() != token || configStore.snapshot().serverBaseUrl != cfg.base) return
                val profile = apiClient.updateProfile(
                    cfg.base, token, DeviceProfileRequest(SyncCoordinator.APP_NAME, BuildConfig.VERSION_NAME),
                )
                check(profile.ok) { strings.get(R.string.cr_invalid_response) }
                val settings = profile.settings.toSettings()
                configStore.updateConfig {
                    if (epoch.value == generation) it.copy(deviceId = profile.deviceId, serverSettings = settings) else it
                }
                openSocket(cfg, generation, attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (epoch.value != generation) return
                if (failure is ApiException && failure.code == 401) {
                    rejectCredential(token)
                    return
                }
                mutableStatus.value = RealtimeStatus(
                    if (attempt == 5) ConnectionState.ERROR else ConnectionState.RETRYING,
                    attempt, failure.message ?: strings.get(R.string.cr_connection_failed),
                )
                if (attempt < 5) delay(waits[attempt - 1])
            }
        }
    }

    private sealed interface SocketEvent {
        data object Open : SocketEvent
        data class Message(val text: String) : SocketEvent
        data class Failure(val cause: Exception) : SocketEvent
    }

    private suspend fun openSocket(cfg: ConnectionConfig, generation: Long, attempt: Int) {
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val socket = apiClient.openWebSocket(apiClient.resolveWebSocketUrl(cfg.base), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(SocketEvent.Open) }
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(SocketEvent.Message(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(SocketEvent.Failure(IOException(strings.get(R.string.cr_connection_closed))))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val cause = if (response?.code == 401) ApiException(401, strings.get(R.string.cr_auth_required))
                    else (t as? Exception ?: IOException(t))
                events.trySend(SocketEvent.Failure(cause))
            }
        })
        activeSocket = socket
        try {
            val ready = withTimeoutOrNull(10_000) {
                while (true) {
                    when (val event = events.receive()) {
                        SocketEvent.Open -> {
                            if (epoch.value != generation) return@withTimeoutOrNull false
                            mutableStatus.value = RealtimeStatus(ConnectionState.HANDSHAKING, attempt)
                            check(socket.send(ClientProtocol.json.encodeToString(
                                HelloMessage.serializer(), HelloMessage(HelloPayload(cfg.token!!, cfg.uuid)),
                            ))) { strings.get(R.string.cr_connection_closed) }
                        }
                        is SocketEvent.Failure -> throw event.cause
                        is SocketEvent.Message -> {
                            val message = ClientProtocol.decodeEvent(event.text)
                            if (message is ServerEvent.HelloAck) {
                                val settings = message.payload.settings.toSettings()
                                configStore.updateConfig {
                                    if (epoch.value == generation) it.copy(deviceId = message.payload.deviceId, serverSettings = settings) else it
                                }
                                if (epoch.value != generation) return@withTimeoutOrNull false
                                mutableStatus.value = RealtimeStatus(ConnectionState.CONNECTED)
                                return@withTimeoutOrNull true
                            }
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (ready != true) throw IOException(strings.get(R.string.cr_handshake_timeout))
            while (epoch.value == generation) {
                when (val event = events.receive()) {
                    is SocketEvent.Failure -> throw event.cause
                    is SocketEvent.Message -> {
                        val message = ClientProtocol.decodeEvent(event.text)
                        if (message is ServerEvent.ClipboardCreated) {
                            deliveries.send(RemoteDelivery(message.item, generation, cfg.base, cfg.token!!))
                        }
                    }
                    SocketEvent.Open -> Unit
                }
            }
        } finally {
            socket.cancel()
            if (activeSocket === socket) activeSocket = null
            events.close()
        }
    }
}
package com.clipboardriver.client.sync

import android.net.Uri
import com.clipboardriver.client.StringProvider
import com.clipboardriver.client.model.*
import com.clipboardriver.client.net.*
import com.clipboardriver.client.platform.*
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

private const val NOW = "2026-11-08T08:00:00Z"
internal val testStrings = StringProvider { id, _ -> id.toString() }

internal class FakeConfig(initial: AppConfig = AppConfig(
    serverBaseUrl = "https://old.invalid", deviceUuid = "stable-uuid", deviceId = 2,
    nickname = "Phone", serverSettings = ServerSettings(5_242_880, 200),
)) : ConfigStore {
    private val state = MutableStateFlow(initial)
    override val configFlow: Flow<AppConfig> = state
    override suspend fun snapshot() = state.value
    override suspend fun initializeDeviceUuidIfNeeded() = state.value
    override suspend fun updateConfig(transform: (AppConfig) -> AppConfig): AppConfig {
        state.value = transform(state.value)
        return state.value
    }
}

internal class FakeTokens(initial: String? = "old-token") : TokenStore {
    private val state = MutableStateFlow(initial)
    override val tokenFlow: Flow<String?> = state
    override suspend fun currentToken() = state.value
    override suspend fun setToken(token: String?) { state.value = token }
}

internal class FakeClipboard(var text: String? = "hello") : ClipboardTextGateway {
    var writes = mutableListOf<String>()
    override fun readText() = text
    override fun writeText(text: String): Boolean { writes += text; return true }
}

internal class UnusedStorage : FileStorage {
    override suspend fun importSharedFile(uri: Uri, maxBytes: Long): ImportedFileAsset = error("Unexpected file import")
    override suspend fun saveRemoteFile(blob: DownloadedBlob, itemId: Long, mimeHint: String, nameHint: String?): SavedRemoteFile =
        error("Unexpected file download")
}

internal class FakeSocket(private val address: String) : WebSocket {
    var cancelled = false
    val sent = mutableListOf<String>()
    override fun request() = Request.Builder().url(address).build()
    override fun queueSize() = 0L
    override fun send(text: String): Boolean { sent += text; return true }
    override fun send(bytes: ByteString) = true
    override fun close(code: Int, reason: String?) = true
    override fun cancel() { cancelled = true }
}

internal class FakeGateway : ServerGateway {
    var registerFailure: Exception? = null
    var uploadFailure: Exception? = null
    var profileFailure: Exception? = null
    var profileCount = 0
    var uploadCount = 0
    var lastRegister: RegisterRequest? = null
    var lastText: TextUploadRequest? = null
    var uploadGate: CompletableDeferred<Unit>? = null
    data class Socket(val socket: FakeSocket, val listener: WebSocketListener) {
        fun open() = listener.onOpen(socket, Response.Builder().request(socket.request())
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun message(text: String) = listener.onMessage(socket, text)
        fun fail() = listener.onFailure(socket, IOException("offline"), null)
    }
    val sockets = mutableListOf<Socket>()
    override suspend fun register(serverBaseUrl: String, request: RegisterRequest): RegisterResponse {
        lastRegister = request
        registerFailure?.let { throw it }
        return RegisterResponse(2, "new-token", SettingsPayload(0, 200))
    }
    override suspend fun updateProfile(serverBaseUrl: String, deviceToken: String, request: DeviceProfileRequest): DeviceProfileResponse {
        profileCount++
        profileFailure?.let { throw it }
        return DeviceProfileResponse(true, 2, NOW, SettingsPayload(5_242_880, 200))
    }
    override suspend fun uploadText(serverBaseUrl: String, deviceToken: String, request: TextUploadRequest): UploadReceipt {
        uploadCount++
        lastText = request
        uploadGate?.await()
        uploadFailure?.let { throw it }
        return UploadReceipt(true, UploadedItem(12, "text", "realtime", "", NOW))
    }
    override suspend fun uploadFile(serverBaseUrl: String, deviceToken: String, request: FileUploadRequest): UploadReceipt =
        error("Unexpected file upload")
    override suspend fun downloadBlob(serverBaseUrl: String, deviceToken: String, itemId: Long): DownloadedBlob =
        error("Unexpected blob request")
    override fun normalizeServerBaseUrl(serverBaseUrl: String) = serverBaseUrl.trimEnd('/')
    override fun resolveWebSocketUrl(serverBaseUrl: String) = serverBaseUrl.replace("https://", "wss://") + "/api/v1/client/ws"
    override fun openWebSocket(websocketUrl: String, listener: WebSocketListener): WebSocket =
        FakeSocket(websocketUrl).also { sockets += Socket(it, listener) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    private class Fixture(scope: CoroutineScope) {
        val config = FakeConfig()
        val tokens = FakeTokens()
        val clipboard = FakeClipboard()
        val api = FakeGateway()
        val sessions = RealtimeSessionManager(scope, config, tokens, api, testStrings)
        val coordinator = SyncCoordinator(config, tokens, clipboard, UnusedStorage(), api, sessions,
            TimeProvider { Instant.parse(NOW) }, testStrings)
    }

    @Test fun failedRegistrationPreservesExistingCredentialAndConfiguration() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        val before = f.config.snapshot()
        f.api.registerFailure = ApiException(400, "enrollment closed")
        assertTrue(f.coordinator.register("https://new.invalid", "New phone", "code").isFailure)
        assertEquals("old-token", f.tokens.currentToken())
        assertEquals(before, f.config.snapshot())
        assertEquals("stable-uuid", f.api.lastRegister!!.deviceUuid)
    }

    @Test fun successfulRegistrationRetainsInstallationIdentityAndRotatesCredential() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        assertTrue(f.coordinator.register("https://new.invalid/", " New phone ", " code ").isSuccess)
        assertEquals("stable-uuid", f.config.snapshot().deviceUuid)
        assertEquals("https://new.invalid", f.config.snapshot().serverBaseUrl)
        assertEquals("New phone", f.config.snapshot().nickname)
        assertEquals("new-token", f.tokens.currentToken())
        assertEquals(0L, f.config.snapshot().serverSettings.fileMaxBytes)
    }

    @Test fun clipboardTextUsesExistingTrimmingAndLengthRules() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        f.clipboard.text = "x"
        assertEquals(CaptureResultState.FILTERED, f.coordinator.captureCurrentClipboard().state)
        f.clipboard.text = "a".repeat(1025)
        assertEquals(CaptureResultState.FILTERED, f.coordinator.captureCurrentClipboard().state)
        assertEquals(0, f.api.uploadCount)
        f.clipboard.text = "  hello\nworld  "
        assertEquals(CaptureResultState.UPLOADED, f.coordinator.captureCurrentClipboard().state)
        assertEquals("hello\nworld", f.api.lastText!!.textContent)
        assertEquals("realtime", f.api.lastText!!.uploadKind)
        assertEquals(NOW, f.api.lastText!!.clientCreatedAt)
    }

    @Test fun emptyDisabledAndUnregisteredActionsNeverUpload() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        f.clipboard.text = "  "
        assertEquals(CaptureResultState.NO_TEXT, f.coordinator.captureCurrentClipboard().state)
        f.config.updateConfig { it.copy(syncSendEnabled = false) }
        assertEquals(CaptureResultState.DISABLED, f.coordinator.captureCurrentClipboard().state)
        f.config.updateConfig { it.copy(syncSendEnabled = true) }
        f.tokens.setToken(null)
        assertEquals(CaptureResultState.ERROR, f.coordinator.captureCurrentClipboard().state)
        assertEquals(0, f.api.uploadCount)
    }

    @Test fun uncertainUploadIsAttemptedOnceAndReportedWithoutRetry() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        f.api.uploadFailure = IOException("response lost")
        val result = f.coordinator.captureCurrentClipboard()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(CaptureResultState.ERROR, result.state)
        assertEquals(com.clipboardriver.client.R.string.cr_upload_unknown.toString(), result.message)
        assertEquals(1, f.api.uploadCount)
        // An explicit later action may send the same content again.
        f.api.uploadFailure = null
        assertEquals(CaptureResultState.UPLOADED, f.coordinator.captureCurrentClipboard().state)
        assertEquals(2, f.api.uploadCount)
    }

    @Test fun repeatedClickWhileSendingDoesNotCreateAnotherUpload() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        f.api.uploadGate = CompletableDeferred()
        val first = async { f.coordinator.captureCurrentClipboard() }
        runCurrent()
        assertTrue(f.coordinator.busy.value)
        assertEquals(CaptureResultState.BUSY, f.coordinator.captureCurrentClipboard().state)
        assertEquals(1, f.api.uploadCount)
        f.api.uploadGate!!.complete(Unit)
        assertEquals(CaptureResultState.UPLOADED, first.await().state)
        assertFalse(f.coordinator.busy.value)
    }

    @Test fun unauthorizedUploadStopsReceptionAndRequiresRegistration() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        f.api.uploadFailure = ApiException(401, "revoked")
        assertEquals(CaptureResultState.ERROR, f.coordinator.captureCurrentClipboard().state)
        assertEquals(ConnectionState.AUTH_REQUIRED, f.sessions.status.value.state)
        f.coordinator.captureCurrentClipboard()
        assertEquals(1, f.api.uploadCount)
    }

    @Test fun signOutReleasesSessionsButKeepsInstallationAndPreferences() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        f.sessions.requestSession(SessionOwner.MAIN_SCREEN)
        runCurrent()
        f.coordinator.signOut()
        runCurrent()
        assertTrue(f.api.sockets.single().socket.cancelled)
        assertNull(f.tokens.currentToken())
        assertEquals("stable-uuid", f.config.snapshot().deviceUuid)
        assertEquals("https://old.invalid", f.config.snapshot().serverBaseUrl)
        assertNull(f.coordinator.lastResult.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSessionManagerTest {
    @Test fun socketIsReadyOnlyAfterHelloAckAndOnlySendsHello() = runTest {
        val gateway = FakeGateway()
        val manager = RealtimeSessionManager(backgroundScope, FakeConfig(), FakeTokens(), gateway, testStrings)
        runCurrent()
        manager.requestSession(SessionOwner.MAIN_SCREEN)
        runCurrent()
        val socket = gateway.sockets.single()
        assertFalse(manager.status.value.connected)
        socket.open()
        runCurrent()
        assertEquals(ConnectionState.HANDSHAKING, manager.status.value.state)
        assertEquals(1, socket.socket.sent.size)
        assertFalse(socket.socket.sent.single().contains("last_cursor"))
        socket.message("""{"type":"hello_ack","payload":{"device_id":2,"settings":{"file_max_bytes":0,"text_batch_limit":200},"time":"2026-11-08T08:00:00Z"}}""")
        runCurrent()
        assertTrue(manager.status.value.connected)
        socket.message("""{"type":"clipboard.sync_changed","payload":{"item_ids":[100,200]}}""")
        runCurrent()
        assertEquals(1, socket.socket.sent.size)
    }

    @Test fun sharedSessionSurvivesUntilLastOwnerReleasesAndIgnoresLateEvents() = runTest {
        val gateway = FakeGateway()
        val manager = RealtimeSessionManager(backgroundScope, FakeConfig(), FakeTokens(), gateway, testStrings)
        runCurrent()
        manager.requestSession(SessionOwner.MAIN_SCREEN)
        manager.requestSession(SessionOwner.RECEIVE_PANEL)
        runCurrent()
        assertEquals(1, gateway.sockets.size)
        val old = gateway.sockets.single()
        manager.releaseSession(SessionOwner.MAIN_SCREEN)
        assertFalse(old.socket.cancelled)
        manager.releaseSession(SessionOwner.RECEIVE_PANEL)
        assertTrue(old.socket.cancelled)
        old.open()
        old.message("""{"type":"hello_ack","payload":{"device_id":2,"settings":{"file_max_bytes":0,"text_batch_limit":200},"time":"2026-11-08T08:00:00Z"}}""")
        old.fail()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, manager.status.value.state)
        assertEquals(1, gateway.sockets.size)
    }

    @Test fun handshakeTimesOutAndClosingCancelsPendingReconnect() = runTest {
        val gateway = FakeGateway()
        val manager = RealtimeSessionManager(backgroundScope, FakeConfig(), FakeTokens(), gateway, testStrings)
        runCurrent()
        manager.requestSession(SessionOwner.RECEIVE_PANEL)
        runCurrent()
        gateway.sockets.single().open()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(ConnectionState.RETRYING, manager.status.value.state)
        assertTrue(gateway.sockets.single().socket.cancelled)
        manager.releaseSession(SessionOwner.RECEIVE_PANEL)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, gateway.sockets.size)
    }

    @Test fun connectionAttemptsAreBoundedAndUnauthorizedProfileIsNeverRetried() = runTest {
        val gateway = FakeGateway().apply { profileFailure = IOException("offline") }
        val manager = RealtimeSessionManager(backgroundScope, FakeConfig(), FakeTokens(), gateway, testStrings)
        runCurrent()
        manager.requestSession(SessionOwner.MAIN_SCREEN)
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(5, gateway.profileCount)
        assertEquals(ConnectionState.ERROR, manager.status.value.state)

        manager.releaseAll()
        gateway.profileFailure = ApiException(401, "revoked")
        manager.requestSession(SessionOwner.MAIN_SCREEN)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(6, gateway.profileCount)
        assertEquals(ConnectionState.AUTH_REQUIRED, manager.status.value.state)
    }

    @Test fun receivingCanBeDisabledForEveryOwnerAndReenabledInTheForeground() = runTest {
        val config = FakeConfig()
        val gateway = FakeGateway()
        val manager = RealtimeSessionManager(backgroundScope, config, FakeTokens(), gateway, testStrings)
        runCurrent()
        manager.requestSession(SessionOwner.MAIN_SCREEN)
        manager.requestSession(SessionOwner.RECEIVE_PANEL)
        runCurrent()
        config.updateConfig { it.copy(receiveRealtimeEnabled = false) }
        runCurrent()
        assertTrue(gateway.sockets.single().socket.cancelled)
        config.updateConfig { it.copy(receiveRealtimeEnabled = true) }
        runCurrent()
        assertEquals(1, gateway.sockets.size)
        manager.requestSession(SessionOwner.MAIN_SCREEN)
        runCurrent()
        assertEquals(2, gateway.sockets.size)
    }
}
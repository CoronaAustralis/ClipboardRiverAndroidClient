package com.clipboardriver.client

import android.content.Context
import com.clipboardriver.client.data.ConfigRepository
import com.clipboardriver.client.data.HistoryRepository
import com.clipboardriver.client.data.SecureTokenStore
import com.clipboardriver.client.model.SystemTimeProvider
import com.clipboardriver.client.net.ApiClient
import com.clipboardriver.client.platform.AndroidClipboardGateway
import com.clipboardriver.client.platform.AndroidWifiStatusProvider
import com.clipboardriver.client.platform.ImageStorageManager
import com.clipboardriver.client.sync.CaptureCoordinator
import com.clipboardriver.client.sync.LocalCaptureDeduplicator
import com.clipboardriver.client.sync.ReceiveCoordinator
import com.clipboardriver.client.sync.RealtimeSessionManager
import com.clipboardriver.client.sync.SuppressionRegistry
import com.clipboardriver.client.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(
    appContext: Context,
    appScope: CoroutineScope,
) {
    val strings = AppStrings(appContext)
    val configRepository = ConfigRepository(appContext)
    val tokenStore = SecureTokenStore(appContext)
    val historyRepository = HistoryRepository(appContext)
    val apiClient = ApiClient()
    val clipboardGateway = AndroidClipboardGateway(appContext)
    val wifiStatusProvider = AndroidWifiStatusProvider(appContext)
    val imageStorageManager = ImageStorageManager(appContext, strings)
    val suppressionRegistry = SuppressionRegistry(SystemTimeProvider)
    val deduplicator = LocalCaptureDeduplicator(SystemTimeProvider)
    val realtimeSessionManager = RealtimeSessionManager(
        scope = appScope,
        configStore = configRepository,
        tokenStore = tokenStore,
        historyStore = historyRepository,
        apiClient = apiClient,
        timeProvider = SystemTimeProvider,
        strings = strings,
    )
    val syncCoordinator = SyncCoordinator(
        configStore = configRepository,
        tokenStore = tokenStore,
        historyStore = historyRepository,
        clipboardGateway = clipboardGateway,
        imageStorageManager = imageStorageManager,
        wifiStatusProvider = wifiStatusProvider,
        apiClient = apiClient,
        timeProvider = SystemTimeProvider,
        suppressionRegistry = suppressionRegistry,
        deduplicator = deduplicator,
        appScope = appScope,
        strings = strings,
    )
    val captureCoordinator = CaptureCoordinator(syncCoordinator)
    val receiveCoordinator = ReceiveCoordinator(
        configStore = configRepository,
        tokenStore = tokenStore,
        historyStore = historyRepository,
        clipboardGateway = clipboardGateway,
        imageStorageManager = imageStorageManager,
        apiClient = apiClient,
        suppressionRegistry = suppressionRegistry,
        sessionManager = realtimeSessionManager,
        strings = strings,
    )
}

class ClipboardRiverApplication : android.app.Application() {
    lateinit var container: AppContainer
        private set
    lateinit var applicationScope: CoroutineScope
        private set

    override fun onCreate() {
        super.onCreate()
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        container = AppContainer(applicationContext, applicationScope)
    }
}

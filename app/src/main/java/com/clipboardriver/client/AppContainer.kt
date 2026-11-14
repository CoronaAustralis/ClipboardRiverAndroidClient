package com.clipboardriver.client

import android.content.Context
import com.clipboardriver.client.data.ConfigRepository
import com.clipboardriver.client.data.SecureTokenStore
import com.clipboardriver.client.model.SystemTimeProvider
import com.clipboardriver.client.net.ApiClient
import com.clipboardriver.client.platform.AndroidClipboardGateway
import com.clipboardriver.client.platform.FileStorageManager
import com.clipboardriver.client.sync.ReceiveCoordinator
import com.clipboardriver.client.sync.RealtimeSessionManager
import com.clipboardriver.client.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(appContext: Context, appScope: CoroutineScope) {
    val strings = AppStrings(appContext)
    val configRepository = ConfigRepository(appContext)
    val tokenStore = SecureTokenStore(appContext)
    val apiClient = ApiClient()
    val clipboardGateway = AndroidClipboardGateway(appContext)
    val fileStorage = FileStorageManager(appContext, strings)
    val realtimeSessionManager = RealtimeSessionManager(appScope, configRepository, tokenStore, apiClient, strings)
    val syncCoordinator = SyncCoordinator(
        configRepository, tokenStore, clipboardGateway, fileStorage, apiClient,
        realtimeSessionManager, SystemTimeProvider, strings,
    )
    val receiveCoordinator = ReceiveCoordinator(clipboardGateway, fileStorage, apiClient, realtimeSessionManager, strings)

    init {
        appScope.launch {
            runCatching { fileStorage.prepare() }
                .onFailure { android.util.Log.w("ClipboardRiver", "Unable to prepare transfer cache", it) }
        }
    }
}

class ClipboardRiverApplication : android.app.Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        container = AppContainer(applicationContext, scope)
    }
}
package com.clipboardriver.client.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipboardriver.client.AppContainer
import com.clipboardriver.client.R
import com.clipboardriver.client.model.AppConfig
import com.clipboardriver.client.model.HistoryItem
import com.clipboardriver.client.model.LocalHistoryMode
import com.clipboardriver.client.model.PanelMode
import com.clipboardriver.client.model.ReceiveState
import com.clipboardriver.client.model.RealtimeStatus
import com.clipboardriver.client.model.RegistrationForm
import com.clipboardriver.client.model.RuntimeState
import com.clipboardriver.client.model.SessionOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val form: RegistrationForm = RegistrationForm(),
    val config: AppConfig = AppConfig(),
    val runtime: RuntimeState = RuntimeState(),
    val realtimeStatus: RealtimeStatus = RealtimeStatus(),
    val receiveState: ReceiveState = ReceiveState(),
    val history: List<HistoryItem> = emptyList(),
    val pendingCount: Int = 0,
    val isRegistered: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val working: Boolean = false,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val formState = MutableStateFlow(RegistrationForm())
    private val workingState = MutableStateFlow(false)
    private val messageState = MutableStateFlow<String?>(null)
    private val errorState = MutableStateFlow<String?>(null)
    private val panelRequests = MutableSharedFlow<PanelMode>(extraBufferCapacity = 4)

    val openPanelRequests = panelRequests.asSharedFlow()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<MainUiState> = combine(
        formState,
        container.configRepository.configFlow,
        container.tokenStore.tokenFlow,
        container.historyRepository.visibleHistory(60),
        container.historyRepository.pendingCount(),
        container.historyRepository.runtimeState(),
        container.realtimeSessionManager.status,
        container.receiveCoordinator.state,
        workingState,
        messageState,
        errorState,
    ) { values ->
        val form = values[0] as RegistrationForm
        val config = values[1] as AppConfig
        val token = values[2] as String?
        val history = values[3] as List<HistoryItem>
        val pendingCount = values[4] as Int
        val runtime = values[5] as RuntimeState
        val realtimeStatus = values[6] as RealtimeStatus
        val receiveState = values[7] as ReceiveState
        val working = values[8] as Boolean
        val message = values[9] as String?
        val error = values[10] as String?
        MainUiState(
            form = form,
            config = config,
            runtime = runtime,
            realtimeStatus = realtimeStatus,
            receiveState = receiveState,
            history = history,
            pendingCount = pendingCount,
            isRegistered = !token.isNullOrBlank() && config.serverBaseUrl.isNotBlank(),
            message = message,
            error = error,
            working = working,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            val config = container.configRepository.initializeDeviceUuidIfNeeded()
            formState.value = RegistrationForm(
                serverBaseUrl = config.serverBaseUrl,
                nickname = config.nickname,
                enrollmentCode = "",
            )
        }
    }

    fun updateServerBaseUrl(value: String) {
        formState.value = formState.value.copy(serverBaseUrl = value)
    }

    fun updateNickname(value: String) {
        formState.value = formState.value.copy(nickname = value)
    }

    fun updateEnrollmentCode(value: String) {
        formState.value = formState.value.copy(enrollmentCode = value)
    }

    fun registerOrReregister() {
        viewModelScope.launch {
            workingState.value = true
            errorState.value = null
            messageState.value = null
            container.syncCoordinator.register(
                serverBaseUrl = formState.value.serverBaseUrl,
                nickname = formState.value.nickname,
                enrollmentCode = formState.value.enrollmentCode,
            ).onSuccess { message ->
                messageState.value = message
                formState.value = formState.value.copy(enrollmentCode = "")
            }.onFailure { throwable ->
                errorState.value = throwable.message ?: container.strings.get(R.string.main_register_failed)
            }
            workingState.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            workingState.value = true
            container.realtimeSessionManager.releaseSession(SessionOwner.MAIN_SCREEN)
            container.syncCoordinator.signOut()
            messageState.value = container.strings.get(R.string.main_sign_out_success)
            errorState.value = null
            workingState.value = false
        }
    }

    fun reconnect() {
        viewModelScope.launch {
            workingState.value = true
            errorState.value = null
            container.syncCoordinator.sendHeartbeat()
                .onSuccess {
                    messageState.value = container.strings.get(R.string.main_heartbeat_success)
                    if (container.configRepository.snapshot().receiveRealtimeEnabled) {
                        container.realtimeSessionManager.requestSession(SessionOwner.MAIN_SCREEN)
                    }
                }
                .onFailure { throwable ->
                    errorState.value = throwable.message ?: container.strings.get(R.string.main_reconnect_failed)
                }
            workingState.value = false
        }
    }

    fun uploadHistory() {
        viewModelScope.launch {
            workingState.value = true
            errorState.value = null
            container.syncCoordinator.flushPendingHistory(manual = true)
                .onSuccess { message ->
                    messageState.value = message
                }
                .onFailure { throwable ->
                    errorState.value = throwable.message ?: container.strings.get(R.string.main_upload_history_failed)
                }
            workingState.value = false
        }
    }

    fun copyHistoryItem(item: HistoryItem) {
        viewModelScope.launch {
            messageState.value = null
            errorState.value = null

            val text = item.textContent?.takeIf { it.isNotBlank() } ?: item.previewText
            if (text.isBlank()) {
                errorState.value = container.strings.get(R.string.history_copy_failed)
                return@launch
            }

            if (container.clipboardGateway.writeText(text)) {
                messageState.value = container.strings.get(R.string.history_text_copied)
            } else {
                errorState.value = container.strings.get(R.string.history_copy_failed)
            }
        }
    }

    fun deleteHistoryItem(item: HistoryItem) {
        viewModelScope.launch {
            messageState.value = null
            errorState.value = null

            runCatching {
                container.historyRepository.deleteRecord(item.clientItemId)
            }.onSuccess {
                messageState.value = container.strings.get(R.string.history_item_deleted)
            }.onFailure { throwable ->
                errorState.value = throwable.message ?: container.strings.get(R.string.history_delete_failed)
            }
        }
    }

    fun showErrorMessage(message: String) {
        messageState.value = null
        errorState.value = message
    }

    fun requestPanel(mode: PanelMode) {
        panelRequests.tryEmit(mode)
    }

    fun importSharedImage(uri: Uri) {
        viewModelScope.launch {
            workingState.value = true
            messageState.value = null
            errorState.value = null

            val result = container.syncCoordinator.importSharedImage(uri)
            if (result.state == com.clipboardriver.client.model.CaptureResultState.ERROR) {
                errorState.value = result.message
            } else {
                messageState.value = result.message
            }

            workingState.value = false
        }
    }

    fun saveSyncSettings(
        syncSendEnabled: Boolean,
        receiveRealtimeEnabled: Boolean,
        localHistoryMode: LocalHistoryMode,
        localHistoryRetentionDays: Int,
        textCaptureMinChars: Int,
        textCaptureMaxChars: Int,
        historySyncWifiOnly: Boolean,
    ) {
        viewModelScope.launch {
            workingState.value = true
            messageState.value = null
            errorState.value = null

            val currentConfig = container.configRepository.snapshot()
            val normalizedRetentionDays = localHistoryRetentionDays.coerceAtLeast(1)
            val normalizedMinChars = textCaptureMinChars.coerceAtLeast(1)
            val normalizedMaxChars = textCaptureMaxChars.coerceAtLeast(normalizedMinChars)

            runCatching {
                container.syncCoordinator.updateTextSettings(
                    syncSendEnabled = syncSendEnabled,
                    receiveRealtimeEnabled = receiveRealtimeEnabled,
                    localHistoryMode = localHistoryMode,
                    localHistoryRetentionDays = normalizedRetentionDays,
                    textCaptureMinChars = normalizedMinChars,
                    textCaptureMaxChars = normalizedMaxChars,
                    historySyncWifiOnly = historySyncWifiOnly,
                )
            }.onSuccess {
                val token = container.tokenStore.currentToken()
                val isRegistered = !token.isNullOrBlank() && currentConfig.serverBaseUrl.isNotBlank()

                when {
                    currentConfig.receiveRealtimeEnabled && !receiveRealtimeEnabled -> {
                        container.realtimeSessionManager.releaseSession(SessionOwner.MAIN_SCREEN)
                    }

                    !currentConfig.receiveRealtimeEnabled && receiveRealtimeEnabled && isRegistered -> {
                        container.realtimeSessionManager.requestSession(SessionOwner.MAIN_SCREEN)
                    }
                }

                messageState.value = when {
                    currentConfig.localHistoryMode != LocalHistoryMode.DISABLED &&
                        localHistoryMode == LocalHistoryMode.DISABLED -> {
                        container.strings.get(R.string.main_settings_saved_history_cleared)
                    }

                    localHistoryMode == LocalHistoryMode.DAYS &&
                        (
                            currentConfig.localHistoryMode != LocalHistoryMode.DAYS ||
                                normalizedRetentionDays != currentConfig.localHistoryRetentionDays
                            ) -> {
                        container.strings.quantity(
                            R.plurals.main_settings_saved_days,
                            normalizedRetentionDays,
                            normalizedRetentionDays,
                        )
                    }

                    currentConfig.localHistoryMode != LocalHistoryMode.FOREVER &&
                        localHistoryMode == LocalHistoryMode.FOREVER -> {
                        container.strings.get(R.string.main_settings_saved_forever)
                    }

                    else -> container.strings.get(R.string.main_settings_saved)
                }
            }.onFailure { throwable ->
                errorState.value = throwable.message ?: container.strings.get(R.string.main_settings_save_failed)
            }

            workingState.value = false
        }
    }

    fun clearBanner() {
        messageState.value = null
        errorState.value = null
    }

    fun onForegroundStarted() {
        viewModelScope.launch {
            val config = container.configRepository.snapshot()
            val token = container.tokenStore.currentToken()
            if (config.receiveRealtimeEnabled && !token.isNullOrBlank() && config.serverBaseUrl.isNotBlank()) {
                container.realtimeSessionManager.requestSession(SessionOwner.MAIN_SCREEN)
            }
        }
    }

    fun onForegroundStopped() {
        viewModelScope.launch {
            container.realtimeSessionManager.releaseSession(SessionOwner.MAIN_SCREEN)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}

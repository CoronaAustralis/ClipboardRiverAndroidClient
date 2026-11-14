package com.clipboardriver.client.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipboardriver.client.AppContainer
import com.clipboardriver.client.R
import com.clipboardriver.client.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val form: RegistrationForm = RegistrationForm(),
    val config: AppConfig = AppConfig(),
    val realtimeStatus: RealtimeStatus = RealtimeStatus(),
    val receiveState: ReceiveState = ReceiveState(),
    val lastResult: CaptureResult? = null,
    val isRegistered: Boolean = false,
    val initializing: Boolean = true,
    val message: String? = null,
    val error: String? = null,
    val working: Boolean = false,
    val transferBusy: Boolean = false,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(MainUiState())
    private var foreground = false

    val uiState = combine(
        local, container.configRepository.configFlow, container.tokenStore.tokenFlow,
        container.realtimeSessionManager.status, container.receiveCoordinator.state,
    ) { current, config, token, status, received ->
        current.copy(
            config = config, realtimeStatus = status, receiveState = received,
            isRegistered = !token.isNullOrBlank() && config.serverBaseUrl.isNotBlank() &&
                status.state != ConnectionState.AUTH_REQUIRED,
        )
    }.combine(container.syncCoordinator.lastResult) { state, result -> state.copy(lastResult = result) }
        .combine(container.syncCoordinator.busy) { state, busy -> state.copy(transferBusy = busy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            try {
                val config = container.configRepository.initializeDeviceUuidIfNeeded()
                local.update { it.copy(
                    initializing = false,
                    form = RegistrationForm(config.serverBaseUrl, config.nickname.ifBlank { Build.MODEL.orEmpty() }),
                ) }
            } catch (failure: Exception) {
                local.update { it.copy(initializing = false, error = failure.message) }
            }
        }
    }

    fun updateForm(form: RegistrationForm) { local.update { it.copy(form = form) } }
    fun clearBanner() { local.update { it.copy(message = null, error = null) } }

    fun register() = perform {
        val form = local.value.form
        container.syncCoordinator.register(form.serverBaseUrl, form.nickname, form.enrollmentCode).getOrThrow()
        container.receiveCoordinator.clear()
        local.update { it.copy(form = it.form.copy(enrollmentCode = "")) }
        if (foreground) container.realtimeSessionManager.requestSession(SessionOwner.MAIN_SCREEN)
        container.strings.get(R.string.cr_registered)
    }

    fun signOut() = perform {
        container.syncCoordinator.signOut()
        container.receiveCoordinator.clear()
        container.strings.get(R.string.cr_signed_out)
    }

    fun reconnect() = perform {
        if (container.configRepository.snapshot().receiveRealtimeEnabled && foreground) {
            container.realtimeSessionManager.requestSession(SessionOwner.MAIN_SCREEN)
            container.realtimeSessionManager.reconnect()
            null
        } else {
            container.syncCoordinator.refreshProfile().getOrThrow()
            container.strings.get(R.string.cr_settings_refreshed)
        }
    }

    fun sendClipboard() {
        if (container.syncCoordinator.busy.value) return
        viewModelScope.launch { container.syncCoordinator.captureCurrentClipboard() }
    }

    fun saveSettings(send: Boolean, receive: Boolean, min: Int, max: Int) = perform {
        require(min >= 1 && max >= min) { container.strings.get(R.string.cr_invalid_length) }
        container.configRepository.updateConfig {
            it.copy(syncSendEnabled = send, receiveRealtimeEnabled = receive,
                textCaptureMinChars = min, textCaptureMaxChars = max)
        }
        if (!receive) container.realtimeSessionManager.releaseAll()
        else if (foreground) container.realtimeSessionManager.requestSession(SessionOwner.MAIN_SCREEN)
        container.strings.get(R.string.cr_settings_saved)
    }

    private fun perform(action: suspend () -> String?) {
        if (local.value.working) return
        local.update { it.copy(working = true, message = null, error = null) }
        viewModelScope.launch {
            try {
                val message = action()
                local.update { it.copy(message = message) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update { it.copy(error = failure.message ?: container.strings.get(R.string.cr_operation_failed)) }
            } finally {
                local.update { it.copy(working = false) }
            }
        }
    }

    fun onForegroundStarted() {
        foreground = true
        viewModelScope.launch { container.realtimeSessionManager.requestSession(SessionOwner.MAIN_SCREEN) }
    }

    fun onForegroundStopped() {
        foreground = false
        viewModelScope.launch { container.realtimeSessionManager.releaseSession(SessionOwner.MAIN_SCREEN) }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}
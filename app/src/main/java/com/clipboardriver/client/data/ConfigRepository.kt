package com.clipboardriver.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.clipboardriver.client.model.AppConfig
import com.clipboardriver.client.model.LocalHistoryMode
import com.clipboardriver.client.model.ServerSettings
import com.clipboardriver.client.sync.ConfigStore
import com.clipboardriver.client.sync.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.appConfigDataStore by preferencesDataStore(name = "clipboard_river_config")

class ConfigRepository(
    private val context: Context,
) : ConfigStore {
    private object Keys {
        val serverBaseUrl = stringPreferencesKey("server_base_url")
        val websocketUrl = stringPreferencesKey("websocket_url")
        val deviceUuid = stringPreferencesKey("device_uuid")
        val nickname = stringPreferencesKey("nickname")
        val lastRemoteCursor = longPreferencesKey("last_remote_cursor")
        val syncSendEnabled = booleanPreferencesKey("sync_send_enabled")
        val receiveRealtimeEnabled = booleanPreferencesKey("receive_realtime_enabled")
        val localHistoryMode = stringPreferencesKey("local_history_mode")
        val localHistoryRetentionDays = intPreferencesKey("local_history_retention_days")
        val textCaptureMinChars = intPreferencesKey("text_capture_min_chars")
        val textCaptureMaxChars = intPreferencesKey("text_capture_max_chars")
        val historySyncWifiOnly = booleanPreferencesKey("history_sync_wifi_only")
        val serverRealtimeFanoutEnabled = booleanPreferencesKey("server_realtime_fanout_enabled")
        val serverTextBatchLimit = intPreferencesKey("server_text_batch_limit")
        val serverImageMaxBytes = longPreferencesKey("server_image_max_bytes")
    }

    override val configFlow: Flow<AppConfig> = context.appConfigDataStore.data.map(::preferencesToConfig)

    override suspend fun snapshot(): AppConfig = configFlow.first()

    override suspend fun initializeDeviceUuidIfNeeded(): AppConfig {
        val current = snapshot()
        if (current.deviceUuid.isNotBlank()) {
            return current
        }
        val deviceUuid = UUID.randomUUID().toString()
        context.appConfigDataStore.edit { prefs ->
            prefs[Keys.deviceUuid] = deviceUuid
        }
        return snapshot()
    }

    override suspend fun updateConfig(transform: (AppConfig) -> AppConfig): AppConfig {
        val current = snapshot()
        val updated = transform(current)
        context.appConfigDataStore.edit { prefs ->
            prefs[Keys.serverBaseUrl] = updated.serverBaseUrl
            prefs[Keys.websocketUrl] = updated.websocketUrl
            prefs[Keys.deviceUuid] = updated.deviceUuid
            prefs[Keys.nickname] = updated.nickname
            prefs[Keys.lastRemoteCursor] = updated.lastRemoteCursor
            prefs[Keys.syncSendEnabled] = updated.syncSendEnabled
            prefs[Keys.receiveRealtimeEnabled] = updated.receiveRealtimeEnabled
            prefs[Keys.localHistoryMode] = updated.localHistoryMode.name
            prefs[Keys.localHistoryRetentionDays] = updated.localHistoryRetentionDays
            prefs[Keys.textCaptureMinChars] = updated.textCaptureMinChars
            prefs[Keys.textCaptureMaxChars] = updated.textCaptureMaxChars
            prefs[Keys.historySyncWifiOnly] = updated.historySyncWifiOnly
            prefs[Keys.serverRealtimeFanoutEnabled] = updated.serverSettings.realtimeFanoutEnabled
            prefs[Keys.serverTextBatchLimit] = updated.serverSettings.textBatchLimit
            updated.serverSettings.imageMaxBytes?.let { prefs[Keys.serverImageMaxBytes] = it } ?: prefs.remove(Keys.serverImageMaxBytes)
        }
        return updated
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

    private fun preferencesToConfig(prefs: Preferences): AppConfig {
        val historyMode = prefs[Keys.localHistoryMode]
            ?.let { runCatching { LocalHistoryMode.valueOf(it) }.getOrDefault(LocalHistoryMode.DAYS) }
            ?: LocalHistoryMode.DAYS

        return AppConfig(
            serverBaseUrl = prefs[Keys.serverBaseUrl].orEmpty(),
            websocketUrl = prefs[Keys.websocketUrl].orEmpty(),
            deviceUuid = prefs[Keys.deviceUuid] ?: "",
            nickname = prefs[Keys.nickname].orEmpty(),
            lastRemoteCursor = prefs[Keys.lastRemoteCursor] ?: 0L,
            syncSendEnabled = prefs[Keys.syncSendEnabled] ?: true,
            receiveRealtimeEnabled = prefs[Keys.receiveRealtimeEnabled] ?: true,
            localHistoryMode = historyMode,
            localHistoryRetentionDays = prefs[Keys.localHistoryRetentionDays] ?: 30,
            textCaptureMinChars = prefs[Keys.textCaptureMinChars] ?: 2,
            textCaptureMaxChars = prefs[Keys.textCaptureMaxChars] ?: 1024,
            historySyncWifiOnly = prefs[Keys.historySyncWifiOnly] ?: false,
            serverSettings = ServerSettings(
                realtimeFanoutEnabled = prefs[Keys.serverRealtimeFanoutEnabled] ?: true,
                textBatchLimit = prefs[Keys.serverTextBatchLimit] ?: 200,
                imageMaxBytes = prefs[Keys.serverImageMaxBytes],
            ),
        )
    }
}

class SecureTokenStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences
    private val mutableToken = MutableStateFlow<String?>(null)

    init {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        prefs = EncryptedSharedPreferences.create(
            "clipboard_river_tokens",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        mutableToken.value = prefs.getString(KEY_DEVICE_TOKEN, null)
    }

    override val tokenFlow: Flow<String?> = mutableToken

    override suspend fun currentToken(): String? = mutableToken.value

    override suspend fun setToken(token: String?) {
        if (token.isNullOrBlank()) {
            prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
            mutableToken.value = null
        } else {
            prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
            mutableToken.value = token
        }
    }

    private companion object {
        const val KEY_DEVICE_TOKEN = "device_token"
    }
}

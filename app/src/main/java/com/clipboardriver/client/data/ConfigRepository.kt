package com.clipboardriver.client.data

import android.content.Context
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
import com.clipboardriver.client.model.ServerSettings
import com.clipboardriver.client.sync.ConfigStore
import com.clipboardriver.client.sync.TokenStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.appConfigDataStore by preferencesDataStore(name = "clipboard_river_config")

class ConfigRepository(private val context: Context) : ConfigStore {
    private object Keys {
        val serverBaseUrl = stringPreferencesKey("server_base_url")
        val deviceUuid = stringPreferencesKey("device_uuid")
        val deviceId = longPreferencesKey("device_id")
        val nickname = stringPreferencesKey("nickname")
        val syncSendEnabled = booleanPreferencesKey("sync_send_enabled")
        val receiveRealtimeEnabled = booleanPreferencesKey("receive_realtime_enabled")
        val textCaptureMinChars = intPreferencesKey("text_capture_min_chars")
        val textCaptureMaxChars = intPreferencesKey("text_capture_max_chars")
        val serverTextBatchLimit = intPreferencesKey("server_text_batch_limit")
        val serverFileMaxBytes = longPreferencesKey("server_file_max_bytes")
    }

    override val configFlow: Flow<AppConfig> = context.appConfigDataStore.data.map(::toConfig)
    override suspend fun snapshot(): AppConfig = configFlow.first()

    override suspend fun initializeDeviceUuidIfNeeded(): AppConfig = updateConfig {
        if (it.deviceUuid.isBlank()) it.copy(deviceUuid = UUID.randomUUID().toString()) else it
    }

    override suspend fun updateConfig(transform: (AppConfig) -> AppConfig): AppConfig {
        // Transform within the transaction so concurrent updates cannot overwrite one another.
        val saved = context.appConfigDataStore.edit { prefs ->
            val updated = transform(toConfig(prefs))
            prefs[Keys.serverBaseUrl] = updated.serverBaseUrl
            prefs[Keys.deviceUuid] = updated.deviceUuid
            updated.deviceId?.let { prefs[Keys.deviceId] = it } ?: prefs.remove(Keys.deviceId)
            prefs[Keys.nickname] = updated.nickname
            prefs[Keys.syncSendEnabled] = updated.syncSendEnabled
            prefs[Keys.receiveRealtimeEnabled] = updated.receiveRealtimeEnabled
            prefs[Keys.textCaptureMinChars] = updated.textCaptureMinChars
            prefs[Keys.textCaptureMaxChars] = updated.textCaptureMaxChars
            prefs[Keys.serverTextBatchLimit] = updated.serverSettings.textBatchLimit
            updated.serverSettings.fileMaxBytes?.let { prefs[Keys.serverFileMaxBytes] = it }
                ?: prefs.remove(Keys.serverFileMaxBytes)
        }
        return toConfig(saved)
    }

    private fun toConfig(prefs: Preferences) = AppConfig(
        serverBaseUrl = prefs[Keys.serverBaseUrl].orEmpty(),
        deviceUuid = prefs[Keys.deviceUuid].orEmpty(),
        deviceId = prefs[Keys.deviceId],
        nickname = prefs[Keys.nickname].orEmpty(),
        syncSendEnabled = prefs[Keys.syncSendEnabled] ?: true,
        receiveRealtimeEnabled = prefs[Keys.receiveRealtimeEnabled] ?: true,
        textCaptureMinChars = prefs[Keys.textCaptureMinChars] ?: 2,
        textCaptureMaxChars = prefs[Keys.textCaptureMaxChars] ?: 1024,
        serverSettings = ServerSettings(
            fileMaxBytes = prefs[Keys.serverFileMaxBytes],
            textBatchLimit = prefs[Keys.serverTextBatchLimit] ?: 200,
        ),
    )
}

class SecureTokenStore(context: Context) : TokenStore {
    private val prefs = EncryptedSharedPreferences.create(
        "clipboard_river_tokens",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val state = MutableStateFlow(prefs.getString("device_token", null))
    override val tokenFlow: Flow<String?> = state
    override suspend fun currentToken(): String? = state.value

    override suspend fun setToken(token: String?) {
        withContext(Dispatchers.IO) {
            check(prefs.edit().apply {
                if (token.isNullOrBlank()) remove("device_token") else putString("device_token", token)
            }.commit()) { "Unable to save device credential." }
            state.value = token?.takeIf { it.isNotBlank() }
        }
    }
}
package com.clipboardriver.client.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.clipboardriver.client.R
import com.clipboardriver.client.sync.ClipboardTextGateway
import com.clipboardriver.client.sync.WifiStatusProvider

class AndroidClipboardGateway(
    private val context: Context,
) : ClipboardTextGateway {
    private val clipboardManager: ClipboardManager?
        get() = context.getSystemService()

    override fun readText(): String? {
        val manager = clipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
        return text?.takeIf { it.isNotBlank() }
    }

    override fun writeText(text: String): Boolean {
        val manager = clipboardManager ?: return false
        return runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        }.isSuccess
    }
}

class AndroidWifiStatusProvider(
    private val context: Context,
) : WifiStatusProvider {
    override fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

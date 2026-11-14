package com.clipboardriver.client.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import com.clipboardriver.client.R
import com.clipboardriver.client.sync.ClipboardTextGateway

class AndroidClipboardGateway(private val context: Context) : ClipboardTextGateway {
    private val clipboardManager: ClipboardManager? get() = context.getSystemService()

    override fun readText(): String? {
        val clip = clipboardManager?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    override fun writeText(text: String): Boolean = runCatching {
        val manager = clipboardManager ?: return false
        manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        true
    }.getOrDefault(false)
}
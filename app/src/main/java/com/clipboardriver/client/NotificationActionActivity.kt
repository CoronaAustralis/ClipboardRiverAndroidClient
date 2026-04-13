package com.clipboardriver.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.clipboardriver.client.model.PanelMode

class NotificationActionActivity : ComponentActivity() {
    private val panelMode: PanelMode by lazy {
        intent.getStringExtra(EXTRA_PANEL_MODE)
            ?.let { runCatching { PanelMode.valueOf(it) }.getOrDefault(PanelMode.CAPTURE) }
            ?: PanelMode.CAPTURE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OverlayWindowSupport.canDraw(this)) {
            ActionNotificationService.openPanel(
                context = this,
                mode = panelMode,
                delayMillis = NOTIFICATION_TRANSITION_DELAY_MS,
            )
        } else {
            OverlayWindowSupport.openPermissionSettings(this)
        }
        finish()
    }

    companion object {
        private const val EXTRA_PANEL_MODE = "panel_mode"
        private const val NOTIFICATION_TRANSITION_DELAY_MS = 500L

        fun intent(context: android.content.Context, mode: PanelMode) =
            android.content.Intent(context, NotificationActionActivity::class.java).apply {
                putExtra(EXTRA_PANEL_MODE, mode.name)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
    }
}

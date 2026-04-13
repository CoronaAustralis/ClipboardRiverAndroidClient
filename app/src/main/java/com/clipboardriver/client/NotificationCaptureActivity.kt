package com.clipboardriver.client

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.clipboardriver.client.model.PanelMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotificationCaptureActivity : ComponentActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTransparentWindow()
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        if (OverlayWindowSupport.canDraw(this)) {
            ActionNotificationService.openPanel(
                context = this,
                mode = PanelMode.CAPTURE,
                delayMillis = 0L,
            )
        } else {
            OverlayWindowSupport.openPermissionSettings(this)
            finishAndRemoveTask()
            overridePendingTransition(0, 0)
            return
        }
        lifecycleScope.launch {
            delay(BRIDGE_HOLD_MILLIS)
            finishAndRemoveTask()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val BRIDGE_HOLD_MILLIS = 1_200L

        fun intent(context: android.content.Context) =
            android.content.Intent(context, NotificationCaptureActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
    }

    private fun configureTransparentWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val params = window.attributes
        params.alpha = 0f
        params.dimAmount = 0f
        window.attributes = params
    }
}

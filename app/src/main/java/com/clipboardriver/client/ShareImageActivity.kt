package com.clipboardriver.client

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ShareImageActivity : ComponentActivity() {
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

        val imageUri = intent.extractSharedImageUri()
        if (imageUri == null) {
            Toast.makeText(this, getString(R.string.toast_no_shared_image), Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
            overridePendingTransition(0, 0)
            return
        }

        if (OverlayWindowSupport.canDraw(this)) {
            ActionNotificationService.openSharedImage(
                context = this,
                uri = imageUri,
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

    companion object {
        private const val BRIDGE_HOLD_MILLIS = 4_000L
    }
}

private fun Intent.extractSharedImageUri(): Uri? {
    val extraUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
    return extraUri ?: clipData?.getItemAt(0)?.uri
}

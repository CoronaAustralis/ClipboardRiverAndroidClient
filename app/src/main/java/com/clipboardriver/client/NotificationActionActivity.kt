package com.clipboardriver.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.clipboardriver.client.model.PanelMode

class NotificationActionActivity : ComponentActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        val mode = runCatching { PanelMode.valueOf(intent.getStringExtra("panel_mode").orEmpty()) }
            .getOrDefault(PanelMode.CAPTURE)
        if (!ActionNotificationService.openPanel(this, mode)) {
            Toast.makeText(this, R.string.cr_overlay_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }

    companion object {
        fun intent(context: Context, mode: PanelMode) =
            Intent(context, NotificationActionActivity::class.java).putExtra("panel_mode", mode.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
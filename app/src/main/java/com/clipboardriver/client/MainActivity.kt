package com.clipboardriver.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipboardriver.client.model.PanelMode
import com.clipboardriver.client.ui.ClipboardRiverAppTheme
import com.clipboardriver.client.ui.MainScreen
import com.clipboardriver.client.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory((application as ClipboardRiverApplication).container)
    }
    private val overlayGranted = mutableStateOf(false)
    private val notificationsGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        ActionNotificationService.start(this)
        setContent {
            val ui = viewModel.uiState.collectAsStateWithLifecycle().value
            val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                refreshPermissions()
                ActionNotificationService.start(this)
            }
            ClipboardRiverAppTheme {
                MainScreen(
                    uiState = ui,
                    overlayPermissionGranted = overlayGranted.value,
                    notificationPermissionGranted = notificationsGranted.value,
                    onFormChanged = viewModel::updateForm,
                    onRegister = viewModel::register,
                    onSendClipboard = viewModel::sendClipboard,
                    onReceive = {
                        if (!ActionNotificationService.openPanel(this, PanelMode.RECEIVE)) {
                            Toast.makeText(this, R.string.cr_overlay_required, Toast.LENGTH_LONG).show()
                        }
                    },
                    onReconnect = viewModel::reconnect,
                    onSignOut = {
                        ActionNotificationService.stop(this)
                        viewModel.signOut()
                    },
                    onSaveSettings = viewModel::saveSettings,
                    onOverlayPermission = { OverlayWindowSupport.openPermissionSettings(this) },
                    onNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                this, Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }
                    },
                    onOpenFile = { uri, mime -> openFile(uri, mime) },
                    onClearBanner = viewModel::clearBanner,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForegroundStarted()
    }

    override fun onStop() {
        viewModel.onForegroundStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        ActionNotificationService.start(this)
    }

    private fun refreshPermissions() {
        overlayGranted.value = OverlayWindowSupport.canDraw(this)
        notificationsGranted.value = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun openFile(uri: String, mime: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, R.string.cr_open_failed, Toast.LENGTH_LONG).show() }
    }
}
package com.clipboardriver.client

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.clipboardriver.client.model.HistoryItem
import com.clipboardriver.client.model.PanelMode
import com.clipboardriver.client.ui.ClipboardRiverAppTheme
import com.clipboardriver.client.ui.MainScreen
import com.clipboardriver.client.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory((application as ClipboardRiverApplication).container)
    }
    private val overlayPermissionGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        overlayPermissionGranted.value = OverlayWindowSupport.canDraw(this)

        lifecycleScope.launch {
            viewModel.openPanelRequests.collectLatest { mode ->
                val launched = ActionNotificationService.openPanel(this@MainActivity, mode)
                if (!launched) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_overlay_permission_required), Toast.LENGTH_LONG).show()
                }
            }
        }

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = {},
            )

            LaunchedEffect(Unit) {
                ActionNotificationService.start(this@MainActivity)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            ClipboardRiverAppTheme {
                MainScreen(
                    uiState = uiState,
                    overlayPermissionGranted = overlayPermissionGranted.value,
                    onServerBaseUrlChanged = viewModel::updateServerBaseUrl,
                    onNicknameChanged = viewModel::updateNickname,
                    onEnrollmentCodeChanged = viewModel::updateEnrollmentCode,
                    onRegister = viewModel::registerOrReregister,
                    onReconnect = viewModel::reconnect,
                    onUploadHistory = viewModel::uploadHistory,
                    onSignOut = viewModel::signOut,
                    onOpenPanel = viewModel::requestPanel,
                    onCopyHistoryItem = viewModel::copyHistoryItem,
                    onDeleteHistoryItem = viewModel::deleteHistoryItem,
                    onOpenHistoryImage = ::openHistoryImage,
                    onSaveSettings = viewModel::saveSyncSettings,
                    onClearBanner = viewModel::clearBanner,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForegroundStarted()
    }

    override fun onResume() {
        super.onResume()
        overlayPermissionGranted.value = OverlayWindowSupport.canDraw(this)
    }

    override fun onStop() {
        viewModel.onForegroundStopped()
        super.onStop()
    }

    private fun openHistoryImage(item: HistoryItem) {
        val imagePath = item.imagePath
        if (imagePath.isNullOrBlank()) {
            viewModel.showErrorMessage(getString(R.string.history_image_open_missing))
            return
        }

        val file = File(imagePath)
        if (!file.exists()) {
            viewModel.showErrorMessage(getString(R.string.history_image_open_missing))
            return
        }

        val imageUri = runCatching {
            FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
        }.getOrElse {
            viewModel.showErrorMessage(getString(R.string.toast_open_image_failed))
            return
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(imageUri, item.mimeType ?: "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching {
            startActivity(viewIntent)
        }.onFailure { throwable ->
            val errorMessage = when (throwable) {
                is ActivityNotFoundException -> getString(R.string.toast_no_image_viewer)
                else -> getString(R.string.toast_open_image_failed)
            }
            viewModel.showErrorMessage(errorMessage)
        }
    }
}

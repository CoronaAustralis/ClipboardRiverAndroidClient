package com.clipboardriver.client

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clipboardriver.client.model.CaptureResult
import com.clipboardriver.client.model.CaptureResultState
import com.clipboardriver.client.sync.SyncCoordinator
import com.clipboardriver.client.ui.ClipboardRiverAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShareFileActivity : ComponentActivity() {
    private val model: ShareFileViewModel by viewModels {
        val coordinator = (application as ClipboardRiverApplication).container.syncCoordinator
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShareFileViewModel(coordinator) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = if (intent.action == Intent.ACTION_SEND) {
            val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else null
        if (uri != null) model.send(uri)
        setContent {
            val result = model.result.collectAsStateWithLifecycle().value
            LaunchedEffect(result) {
                if (result != null) {
                    delay(if (result.state == CaptureResultState.UPLOADED) 2_000 else 3_500)
                    finish()
                }
            }
            ClipboardRiverAppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        OutlinedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text(stringResource(R.string.cr_share_file), style = MaterialTheme.typography.headlineSmall)
                                if (uri != null && result == null) CircularProgressIndicator()
                                Text(result?.message ?: stringResource(
                                    if (uri == null) R.string.cr_no_shared_file else R.string.cr_sending,
                                ))
                                Text(stringResource(R.string.cr_no_queue), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { finish() }) { Text(stringResource(R.string.cr_close)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

class ShareFileViewModel(private val coordinator: SyncCoordinator) : ViewModel() {
    private var started = false
    private val mutableResult = MutableStateFlow<CaptureResult?>(null)
    val result = mutableResult.asStateFlow()

    fun send(uri: Uri) {
        if (started) return
        started = true
        viewModelScope.launch { mutableResult.value = coordinator.importSharedFile(uri) }
    }
}
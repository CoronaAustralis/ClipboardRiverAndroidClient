package com.clipboardriver.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipboardriver.client.BuildConfig
import com.clipboardriver.client.R
import com.clipboardriver.client.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    overlayPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    onFormChanged: (RegistrationForm) -> Unit,
    onRegister: () -> Unit,
    onSendClipboard: () -> Unit,
    onReceive: () -> Unit,
    onReconnect: () -> Unit,
    onSignOut: () -> Unit,
    onSaveSettings: (Boolean, Boolean, Int, Int) -> Unit,
    onOverlayPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onOpenFile: (String, String) -> Unit,
    onClearBanner: () -> Unit,
) {
    var settings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(uiState.message, uiState.error) {
        val message = uiState.error ?: uiState.message
        if (message != null) {
            snackbar.showSnackbar(message, withDismissAction = true)
            onClearBanner()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (settings) R.string.cr_settings else R.string.app_name),
                        fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    if (settings) IconButton(onClick = { settings = false }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cr_back))
                    }
                },
                actions = {
                    if (!settings) IconButton(onClick = { settings = true }) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.cr_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (uiState.initializing) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (settings) {
                        item { DeviceSettings(uiState, onFormChanged, onRegister, onSignOut) }
                        item { TransferSettings(uiState, onSaveSettings) }
                        item {
                            SectionCard {
                                SectionHeading(Icons.Outlined.Shield, stringResource(R.string.cr_permissions))
                                PermissionRow(R.string.cr_notifications, notificationPermissionGranted, onNotificationPermission)
                                HorizontalDivider()
                                PermissionRow(R.string.cr_overlay, overlayPermissionGranted, onOverlayPermission)
                            }
                        }
                        item {
                            SectionCard {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.cr_version, BuildConfig.VERSION_NAME),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.cr_privacy_note), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else if (!uiState.isRegistered) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Icon(Icons.Outlined.ContentPaste, null, Modifier.padding(20.dp).size(36.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Text(stringResource(R.string.cr_welcome), style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.cr_welcome_description),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (uiState.realtimeStatus.state == ConnectionState.AUTH_REQUIRED) item {
                            Text(stringResource(R.string.cr_auth_required), color = MaterialTheme.colorScheme.error)
                        }
                        item { RegistrationCard(uiState, onFormChanged, onRegister) }
                    } else {
                        item { ConnectionCard(uiState, onReconnect) }
                        item {
                            SectionCard {
                                SectionHeading(Icons.Outlined.SwapHoriz, stringResource(R.string.cr_quick_actions))
                                Button(
                                    onClick = onSendClipboard,
                                    enabled = !uiState.working && !uiState.transferBusy && uiState.config.syncSendEnabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    if (uiState.transferBusy) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else Icon(Icons.Outlined.ContentPaste, null)
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(if (uiState.transferBusy) R.string.cr_sending else R.string.cr_send_clipboard))
                                }
                                OutlinedButton(
                                    onClick = onReceive,
                                    enabled = uiState.config.receiveRealtimeEnabled && !uiState.working,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.CallReceived, null)
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.cr_receive))
                                }
                                Text(stringResource(R.string.cr_share_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!overlayPermissionGranted) TextButton(onClick = onOverlayPermission) {
                                    Text(stringResource(R.string.cr_grant_overlay))
                                }
                            }
                        }
                        item { LatestOperation(uiState, onOpenFile) }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.cr_foreground_note), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (settings) {
        androidx.activity.compose.BackHandler { settings = false }
    }
}

@Composable
private fun ConnectionCard(ui: MainUiState, onReconnect: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (ui.realtimeStatus.connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudQueue,
                    null, Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(connectionLabel(ui.realtimeStatus, ui.config.receiveRealtimeEnabled),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.cr_live_description), style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.config.nickname.ifBlank { stringResource(R.string.cr_this_device) },
                    style = MaterialTheme.typography.titleMedium)
                Text(ui.config.serverBaseUrl, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                ui.config.deviceId?.let { Text(stringResource(R.string.cr_device_id, it),
                    style = MaterialTheme.typography.labelMedium) }
            }
            ui.realtimeStatus.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onReconnect, enabled = !ui.working && !ui.transferBusy,
                contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(R.string.cr_reconnect))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RegistrationCard(ui: MainUiState, onFormChanged: (RegistrationForm) -> Unit, onRegister: () -> Unit) {
    val form = ui.form
    SectionCard {
        SectionHeading(Icons.Outlined.Link, stringResource(R.string.cr_connect_device))
        OutlinedTextField(
            value = form.serverBaseUrl, onValueChange = { onFormChanged(form.copy(serverBaseUrl = it)) },
            modifier = Modifier.fillMaxWidth(), enabled = !ui.working, singleLine = true,
            label = { Text(stringResource(R.string.cr_server)) }, placeholder = { Text("https://…") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = form.nickname, onValueChange = { onFormChanged(form.copy(nickname = it)) },
            modifier = Modifier.fillMaxWidth(), enabled = !ui.working, singleLine = true,
            label = { Text(stringResource(R.string.cr_nickname)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = form.enrollmentCode, onValueChange = { onFormChanged(form.copy(enrollmentCode = it)) },
            modifier = Modifier.fillMaxWidth(), enabled = !ui.working, singleLine = true,
            label = { Text(stringResource(R.string.cr_enrollment_code)) },
            supportingText = { Text(stringResource(R.string.cr_enrollment_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        )
        Button(
            onClick = onRegister,
            enabled = !ui.working && !ui.transferBusy && form.serverBaseUrl.isNotBlank() && form.enrollmentCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp),
        ) {
            if (ui.working) {
                CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(stringResource(if (ui.isRegistered) R.string.cr_reregister else R.string.cr_connect))
        }
    }
}

@Composable
private fun DeviceSettings(ui: MainUiState, onFormChanged: (RegistrationForm) -> Unit,
    onRegister: () -> Unit, onSignOut: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(ui.form.enrollmentCode, ui.working) {
        if (!ui.working && ui.form.enrollmentCode.isEmpty()) editing = false
    }
    if (!ui.isRegistered || editing) {
        RegistrationCard(ui, onFormChanged, onRegister)
    } else SectionCard {
        SectionHeading(Icons.Outlined.Smartphone, stringResource(R.string.cr_device))
        Text(ui.config.nickname.ifBlank { stringResource(R.string.cr_this_device) }, fontWeight = FontWeight.Medium)
        Text(ui.config.serverBaseUrl, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.config.deviceId?.let { Text(stringResource(R.string.cr_device_id, it), style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { editing = true }, enabled = !ui.working && !ui.transferBusy) {
            Text(stringResource(R.string.cr_reregister))
        }
        TextButton(onClick = { confirmSignOut = true }, enabled = !ui.working && !ui.transferBusy) {
            Text(stringResource(R.string.cr_sign_out), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(R.string.cr_sign_out)) },
        text = { Text(stringResource(R.string.cr_sign_out_hint)) },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) {
            Text(stringResource(R.string.cr_sign_out))
        } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cr_cancel)) } },
    )
}

@Composable
private fun TransferSettings(ui: MainUiState, onSave: (Boolean, Boolean, Int, Int) -> Unit) {
    val cfg = ui.config
    var send by remember(cfg.syncSendEnabled) { mutableStateOf(cfg.syncSendEnabled) }
    var receive by remember(cfg.receiveRealtimeEnabled) { mutableStateOf(cfg.receiveRealtimeEnabled) }
    var min by remember(cfg.textCaptureMinChars) { mutableStateOf(cfg.textCaptureMinChars.toString()) }
    var max by remember(cfg.textCaptureMaxChars) { mutableStateOf(cfg.textCaptureMaxChars.toString()) }
    val minValue = min.toIntOrNull()
    val maxValue = max.toIntOrNull()
    val valid = minValue != null && maxValue != null && minValue >= 1 && maxValue >= minValue
    val changed = send != cfg.syncSendEnabled || receive != cfg.receiveRealtimeEnabled ||
        minValue != cfg.textCaptureMinChars || maxValue != cfg.textCaptureMaxChars
    SectionCard {
        SectionHeading(Icons.Outlined.Tune, stringResource(R.string.cr_transfer_settings))
        SwitchRow(stringResource(R.string.cr_send_enabled), send, !ui.working) { send = it }
        SwitchRow(stringResource(R.string.cr_receive_enabled), receive, !ui.working) { receive = it }
        HorizontalDivider()
        Text(stringResource(R.string.cr_text_length), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(min, { min = it }, Modifier.weight(1f), singleLine = true,
                enabled = !ui.working, isError = minValue == null || minValue < 1,
                label = { Text(stringResource(R.string.cr_min_chars)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(max, { max = it }, Modifier.weight(1f), singleLine = true,
                enabled = !ui.working, isError = !valid,
                label = { Text(stringResource(R.string.cr_max_chars)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        if (!valid) Text(stringResource(R.string.cr_invalid_length),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.cr_file_limit, fileLimitLabel(cfg.serverSettings.fileMaxBytes)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onSave(send, receive, minValue!!, maxValue!!) },
            enabled = valid && changed && !ui.working && !ui.transferBusy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cr_save))
        }
    }
}

@Composable
private fun LatestOperation(ui: MainUiState, onOpen: (String, String) -> Unit) {
    val received = ui.receiveState
    val sent = ui.lastResult
    val showReceived = received.terminal && instant(received.latestReceivedAt) >= instant(sent?.completedAt)
    val hasOperation = sent != null || received.terminal
    SectionCard {
        SectionHeading(Icons.Outlined.CheckCircle, stringResource(R.string.cr_latest_operation))
        if (!hasOperation) {
            Text(stringResource(R.string.cr_no_operation), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (showReceived) {
            Text(received.status, fontWeight = FontWeight.Medium)
            received.latestPreview?.let {
                Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            received.latestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(displayTime(received.latestReceivedAt), style = MaterialTheme.typography.labelSmall)
            received.latestOpenUri?.let { uri ->
                TextButton(onClick = { onOpen(uri, received.latestOpenMimeType ?: "*/*") }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cr_open_file))
                }
            }
        } else if (sent != null) {
            Text(sent.message, color = if (sent.state == CaptureResultState.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface)
            Text(displayTime(sent.completedAt), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun SectionHeading(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Switch, onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun PermissionRow(label: Int, granted: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label))
            Text(stringResource(if (granted) R.string.cr_permission_granted else R.string.cr_permission_missing),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick) { Text(stringResource(R.string.cr_manage)) }
    }
}

@Composable
fun connectionLabel(status: RealtimeStatus, receiveEnabled: Boolean = true): String =
    stringResource(if (!receiveEnabled) R.string.cr_receive_paused else when (status.state) {
        ConnectionState.DISCONNECTED -> R.string.cr_disconnected
        ConnectionState.CONNECTING -> R.string.cr_connecting
        ConnectionState.HANDSHAKING -> R.string.cr_handshaking
        ConnectionState.CONNECTED -> R.string.cr_connected
        ConnectionState.RETRYING -> R.string.cr_retrying
        ConnectionState.AUTH_REQUIRED -> R.string.cr_auth_required
        ConnectionState.ERROR -> R.string.cr_connection_failed
    })

@Composable
private fun fileLimitLabel(limit: Long?): String = when {
    limit == null -> stringResource(R.string.cr_limit_unknown)
    limit == -1L -> stringResource(R.string.cr_files_disabled)
    limit == 0L -> stringResource(R.string.cr_unlimited)
    else -> android.text.format.Formatter.formatShortFileSize(LocalContext.current, limit)
}

private fun instant(value: String?): Instant = runCatching { Instant.parse(value) }.getOrDefault(Instant.MIN)
private fun displayTime(value: String?): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("")
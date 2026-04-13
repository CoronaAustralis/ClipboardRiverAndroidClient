package com.clipboardriver.client.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clipboardriver.client.BuildConfig
import com.clipboardriver.client.R
import com.clipboardriver.client.model.ContentKind
import com.clipboardriver.client.model.HistoryItem
import com.clipboardriver.client.model.LocalHistoryMode
import com.clipboardriver.client.model.PanelMode
import com.clipboardriver.client.model.SyncState
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    overlayPermissionGranted: Boolean,
    onServerBaseUrlChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onEnrollmentCodeChanged: (String) -> Unit,
    onRegister: () -> Unit,
    onReconnect: () -> Unit,
    onUploadHistory: () -> Unit,
    onSignOut: () -> Unit,
    onOpenPanel: (PanelMode) -> Unit,
    onCopyHistoryItem: (HistoryItem) -> Unit,
    onDeleteHistoryItem: (HistoryItem) -> Unit,
    onOpenHistoryImage: (HistoryItem) -> Unit,
    onSaveSettings: (Boolean, Boolean, LocalHistoryMode, Int, Int, Int, Boolean) -> Unit,
    onClearBanner: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.OVERVIEW) }
    val bannerText = uiState.error ?: uiState.message
    val isErrorBanner = uiState.error != null

    LaunchedEffect(uiState.message, uiState.error) {
        if (bannerText != null) {
            delay(3_000L)
            onClearBanner()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                    scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                ),
                title = {
                    Text(stringResource(R.string.app_name))
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                tonalElevation = 10.dp,
            ) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF6FBFD),
                            Color(0xFFF0F7F7),
                            Color(0xFFFBFCF9),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
                .clearFocusOnTapOutside()
                .padding(padding),
        ) {
            when (selectedTab) {
                MainTab.OVERVIEW -> OverviewPage(
                    uiState = uiState,
                    overlayPermissionGranted = overlayPermissionGranted,
                    onOpenCapture = { onOpenPanel(PanelMode.CAPTURE) },
                    onOpenReceive = { onOpenPanel(PanelMode.RECEIVE) },
                    onReconnect = onReconnect,
                )
                MainTab.HISTORY -> HistoryPage(
                    uiState = uiState,
                    onUploadHistory = onUploadHistory,
                    onCopyHistoryItem = onCopyHistoryItem,
                    onDeleteHistoryItem = onDeleteHistoryItem,
                    onOpenHistoryImage = onOpenHistoryImage,
                )
                MainTab.SETTINGS -> DevicePage(
                    uiState = uiState,
                    onServerBaseUrlChanged = onServerBaseUrlChanged,
                    onNicknameChanged = onNicknameChanged,
                    onEnrollmentCodeChanged = onEnrollmentCodeChanged,
                    onRegister = onRegister,
                    onSignOut = onSignOut,
                    onSaveSettings = onSaveSettings,
                )
            }

            bannerText?.let { text ->
                FloatingBanner(
                    text = text,
                    isError = isErrorBanner,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun OverviewPage(
    uiState: MainUiState,
    overlayPermissionGranted: Boolean,
    onOpenCapture: () -> Unit,
    onOpenReceive: () -> Unit,
    onReconnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroSection(
                isRegistered = uiState.isRegistered,
                overlayPermissionGranted = overlayPermissionGranted,
                wsState = uiState.realtimeStatus.stateLabel,
                wsConnected = uiState.realtimeStatus.connected,
                pendingCount = uiState.pendingCount,
                onOpenCapture = onOpenCapture,
                onOpenReceive = onOpenReceive,
            )
        }
        item { StatusCard(uiState = uiState, onReconnect = onReconnect) }
    }
}

@Composable
private fun HistoryPage(
    uiState: MainUiState,
    onUploadHistory: () -> Unit,
    onCopyHistoryItem: (HistoryItem) -> Unit,
    onDeleteHistoryItem: (HistoryItem) -> Unit,
    onOpenHistoryImage: (HistoryItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HistorySummaryCard(uiState = uiState, onUploadHistory = onUploadHistory) }
        item {
            HistoryCard(
                history = uiState.history,
                onCopyHistoryItem = onCopyHistoryItem,
                onDeleteHistoryItem = onDeleteHistoryItem,
                onOpenHistoryImage = onOpenHistoryImage,
            )
        }
    }
}

@Composable
private fun DevicePage(
    uiState: MainUiState,
    onServerBaseUrlChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onEnrollmentCodeChanged: (String) -> Unit,
    onRegister: () -> Unit,
    onSignOut: () -> Unit,
    onSaveSettings: (Boolean, Boolean, LocalHistoryMode, Int, Int, Int, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            RegistrationCard(
                uiState = uiState,
                onServerBaseUrlChanged = onServerBaseUrlChanged,
                onNicknameChanged = onNicknameChanged,
                onEnrollmentCodeChanged = onEnrollmentCodeChanged,
                onRegister = onRegister,
                onSignOut = onSignOut,
            )
        }
        item {
            SettingsCard(
                uiState = uiState,
                onSaveSettings = onSaveSettings,
            )
        }
        item { AppInfoCard() }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun HeroSection(
    isRegistered: Boolean,
    overlayPermissionGranted: Boolean,
    wsState: String,
    wsConnected: Boolean,
    pendingCount: Int,
    onOpenCapture: () -> Unit,
    onOpenReceive: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFF5FBFA),
                                Color(0xFFEDF5F6),
                                Color(0xFFF7F2EA),
                            ),
                        ),
                    )
                    drawCircle(
                        color = Color(0xFF9EDFD4).copy(alpha = 0.46f),
                        radius = size.width * 0.33f,
                        center = Offset(x = size.width * 0.94f, y = -size.height * 0.02f),
                    )
                    drawCircle(
                        color = Color(0xFFF3D8A6).copy(alpha = 0.38f),
                        radius = size.width * 0.27f,
                        center = Offset(x = -size.width * 0.06f, y = size.height * 1.04f),
                    )
                }
                .padding(22.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val wsLabel = wsState.ifBlank { stringResource(R.string.status_not_connected) }

                Text(
                    text = stringResource(R.string.main_tagline),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OverviewBadge(
                            label = if (isRegistered) {
                                stringResource(R.string.badge_device_registered)
                            } else {
                                stringResource(R.string.badge_device_not_registered)
                            },
                            icon = if (isRegistered) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                            modifier = Modifier.weight(1f),
                        )
                        OverviewBadge(
                            label = stringResource(R.string.badge_pending_count, pendingCount),
                            icon = Icons.Outlined.History,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OverviewBadge(
                        label = if (overlayPermissionGranted) {
                            stringResource(R.string.badge_overlay_permission_granted)
                        } else {
                            stringResource(R.string.badge_overlay_permission_denied)
                        },
                        icon = Icons.Outlined.Link,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.section_realtime_status),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = wsLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        ) {
                            Text(
                                text = if (wsConnected) {
                                    stringResource(R.string.status_online_short)
                                } else {
                                    stringResource(R.string.status_idle_short)
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onOpenCapture,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.action_capture))
                    }
                    OutlinedButton(
                        onClick = onOpenReceive,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.action_receive))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewBadge(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FloatingBanner(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RegistrationCard(
    uiState: MainUiState,
    onServerBaseUrlChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onEnrollmentCodeChanged: (String) -> Unit,
    onRegister: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_registration_device))
            OutlinedTextField(
                value = uiState.form.serverBaseUrl,
                onValueChange = onServerBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_server_address)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.form.nickname,
                onValueChange = onNicknameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_device_nickname)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.form.enrollmentCode,
                onValueChange = onEnrollmentCodeChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_enrollment_code)) },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRegister, enabled = !uiState.working) {
                    Icon(Icons.Outlined.Link, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (uiState.isRegistered) stringResource(R.string.button_reregister) else stringResource(R.string.button_register))
                }
                OutlinedButton(onClick = onSignOut, enabled = uiState.isRegistered && !uiState.working) {
                    Text(stringResource(R.string.button_sign_out))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    uiState: MainUiState,
    onReconnect: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_connection_overview))
            StatusLine(
                stringResource(R.string.label_websocket),
                uiState.realtimeStatus.stateLabel.ifBlank { stringResource(R.string.status_not_connected) },
            )
            StatusLine(stringResource(R.string.label_pending_count), uiState.pendingCount.toString())
            StatusLine(
                stringResource(R.string.label_last_heartbeat),
                uiState.runtime.lastSuccessfulHeartbeatAt?.toLocalDisplayTime() ?: stringResource(R.string.value_none),
            )
            StatusLine(
                stringResource(R.string.label_last_upload),
                uiState.runtime.lastSuccessfulUploadAt?.toLocalDisplayTime() ?: stringResource(R.string.value_none),
            )
            uiState.receiveState.latestPreview?.let {
                StatusLine(stringResource(R.string.label_last_received), it, singleLine = false, maxLines = 2)
            }
            Button(onClick = onReconnect, enabled = uiState.isRegistered && !uiState.working) {
                Icon(Icons.Outlined.Sync, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.button_reconnect))
            }
        }
    }
}

@Composable
private fun HistorySummaryCard(
    uiState: MainUiState,
    onUploadHistory: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_history_overview))
            StatusLine(stringResource(R.string.label_pending_history), uiState.pendingCount.toString())
            StatusLine(
                stringResource(R.string.label_retention_policy),
                localHistoryModeLabel(uiState.config.localHistoryMode, uiState.config.localHistoryRetentionDays),
            )
            StatusLine(
                stringResource(R.string.label_history_sync_network),
                if (uiState.config.historySyncWifiOnly) {
                    stringResource(R.string.value_wifi_only)
                } else {
                    stringResource(R.string.value_any_network)
                },
            )
            OutlinedButton(onClick = onUploadHistory, enabled = uiState.isRegistered && !uiState.working) {
                Text(stringResource(R.string.button_upload_history))
            }
        }
    }
}

@Composable
private fun SettingsCard(
    uiState: MainUiState,
    onSaveSettings: (Boolean, Boolean, LocalHistoryMode, Int, Int, Int, Boolean) -> Unit,
) {
    val savedSettings = SettingsCardSavedState(
        syncSendEnabled = uiState.config.syncSendEnabled,
        receiveRealtimeEnabled = uiState.config.receiveRealtimeEnabled,
        localHistoryMode = uiState.config.localHistoryMode,
        localHistoryRetentionDays = uiState.config.localHistoryRetentionDays,
        textCaptureMinChars = uiState.config.textCaptureMinChars,
        textCaptureMaxChars = uiState.config.textCaptureMaxChars,
        historySyncWifiOnly = uiState.config.historySyncWifiOnly,
    )
    var syncSendEnabled by remember(savedSettings) { mutableStateOf(savedSettings.syncSendEnabled) }
    var receiveRealtimeEnabled by remember(savedSettings) { mutableStateOf(savedSettings.receiveRealtimeEnabled) }
    var localHistoryMode by remember(savedSettings) { mutableStateOf(savedSettings.localHistoryMode) }
    var retentionDraft by remember(savedSettings) { mutableStateOf(savedSettings.localHistoryRetentionDays.toString()) }
    var minCharsDraft by remember(savedSettings) { mutableStateOf(savedSettings.textCaptureMinChars.toString()) }
    var maxCharsDraft by remember(savedSettings) { mutableStateOf(savedSettings.textCaptureMaxChars.toString()) }
    var historySyncWifiOnly by remember(savedSettings) { mutableStateOf(savedSettings.historySyncWifiOnly) }

    val parsedRetentionDays = retentionDraft.toIntOrNull()?.coerceAtLeast(1)
    val parsedMinChars = minCharsDraft.toIntOrNull()?.coerceAtLeast(1)
    val parsedMaxChars = maxCharsDraft.toIntOrNull()?.coerceAtLeast(1)
    val effectiveRetentionDays = parsedRetentionDays ?: savedSettings.localHistoryRetentionDays
    val hasValidRetention = localHistoryMode != LocalHistoryMode.DAYS || parsedRetentionDays != null
    val hasValidNumbers = hasValidRetention && parsedMinChars != null && parsedMaxChars != null
    val hasRangeError = hasValidNumbers && parsedMaxChars!! < parsedMinChars!!
    val hasChanges = syncSendEnabled != savedSettings.syncSendEnabled ||
        receiveRealtimeEnabled != savedSettings.receiveRealtimeEnabled ||
        localHistoryMode != savedSettings.localHistoryMode ||
        effectiveRetentionDays != savedSettings.localHistoryRetentionDays ||
        parsedMinChars != savedSettings.textCaptureMinChars ||
        parsedMaxChars != savedSettings.textCaptureMaxChars ||
        historySyncWifiOnly != savedSettings.historySyncWifiOnly

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_sync_settings))
            SwitchRow(stringResource(R.string.setting_send_sync), syncSendEnabled) {
                syncSendEnabled = it
            }
            SwitchRow(stringResource(R.string.setting_receive_realtime), receiveRealtimeEnabled) {
                receiveRealtimeEnabled = it
            }
            SwitchRow(stringResource(R.string.setting_history_sync_wifi_only), historySyncWifiOnly) {
                historySyncWifiOnly = it
            }
            HorizontalDivider()
            StatusLine(
                stringResource(R.string.label_server_image_limit),
                imageUploadLimitLabel(uiState.config.serverSettings.imageMaxBytes),
            )
            StatusLine(stringResource(R.string.label_text_batch_limit), uiState.config.serverSettings.textBatchLimit.toString())
            Text(stringResource(R.string.label_local_history_mode), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalHistoryMode.entries.forEach { mode ->
                    FilterChip(
                        selected = localHistoryMode == mode,
                        onClick = {
                            localHistoryMode = mode
                            if (mode == LocalHistoryMode.DAYS && retentionDraft.isBlank()) {
                                retentionDraft = savedSettings.localHistoryRetentionDays.toString()
                            }
                        },
                        label = {
                            Text(
                                when (mode) {
                                    LocalHistoryMode.FOREVER -> stringResource(R.string.local_history_mode_forever_short)
                                    LocalHistoryMode.DAYS -> stringResource(R.string.local_history_mode_days_short)
                                    LocalHistoryMode.DISABLED -> stringResource(R.string.local_history_mode_disabled_short)
                                },
                            )
                        },
                    )
                }
            }
            OutlinedTextField(
                value = retentionDraft,
                onValueChange = { retentionDraft = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_local_history_retention_days)) },
                singleLine = true,
                enabled = localHistoryMode == LocalHistoryMode.DAYS,
                isError = localHistoryMode == LocalHistoryMode.DAYS && parsedRetentionDays == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minCharsDraft,
                    onValueChange = { minCharsDraft = it.filter(Char::isDigit) },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.field_min_chars)) },
                    singleLine = true,
                    isError = parsedMinChars == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = maxCharsDraft,
                    onValueChange = { maxCharsDraft = it.filter(Char::isDigit) },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.field_max_chars)) },
                    singleLine = true,
                    isError = parsedMaxChars == null || hasRangeError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            if (hasRangeError) {
                Text(
                    text = stringResource(R.string.error_max_chars_less_than_min),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    onSaveSettings(
                        syncSendEnabled,
                        receiveRealtimeEnabled,
                        localHistoryMode,
                        effectiveRetentionDays,
                        parsedMinChars ?: savedSettings.textCaptureMinChars,
                        parsedMaxChars ?: savedSettings.textCaptureMaxChars,
                        historySyncWifiOnly,
                    )
                },
                enabled = !uiState.working &&
                    hasChanges &&
                    hasValidNumbers &&
                    !hasRangeError,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.button_save_settings))
            }
        }
    }
}

@Composable
private fun AppInfoCard() {
    val uriHandler = LocalUriHandler.current
    val repositoryUrl = stringResource(R.string.app_open_source_url)

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_app_info))
            StatusLine(stringResource(R.string.label_app_version), BuildConfig.VERSION_NAME)
            Text(
                text = stringResource(R.string.label_open_source),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = repositoryUrl,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    uriHandler.openUri(repositoryUrl)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    history: List<HistoryItem>,
    onCopyHistoryItem: (HistoryItem) -> Unit,
    onDeleteHistoryItem: (HistoryItem) -> Unit,
    onOpenHistoryImage: (HistoryItem) -> Unit,
) {
    var selectedSkippedItem by remember { mutableStateOf<HistoryItem?>(null) }
    var selectedActionItem by remember { mutableStateOf<HistoryItem?>(null) }

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_local_history))
            if (history.isEmpty()) {
                Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    history.forEach { item ->
                        val isSkipped = item.syncState == SyncState.SKIPPED
                        Card(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { selectedActionItem = item },
                            ),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = historyPreviewText(item),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.contentKind == ContentKind.IMAGE) {
                                    Text(
                                        text = item.mimeType ?: "image/*",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        item.clientCreatedAt.toLocalDisplayTime(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    AssistChip(
                                        onClick = {
                                            if (isSkipped) {
                                                selectedSkippedItem = item
                                            }
                                        },
                                        colors = historyStatusChipColors(item.syncState),
                                        label = {
                                            Text(
                                                syncStateLabel(item.syncState),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedActionItem?.let { actionItem ->
        HistoryActionOverlay(
            item = actionItem,
            onDismiss = { selectedActionItem = null },
            onPrimaryAction = {
                when (actionItem.contentKind) {
                    ContentKind.TEXT -> onCopyHistoryItem(actionItem)
                    ContentKind.IMAGE -> onOpenHistoryImage(actionItem)
                }
                selectedActionItem = null
            },
            onDelete = {
                onDeleteHistoryItem(actionItem)
                selectedActionItem = null
            },
        )
    }

    selectedSkippedItem?.let { skippedItem ->
        AlertDialog(
            onDismissRequest = { selectedSkippedItem = null },
            confirmButton = {
                TextButton(onClick = { selectedSkippedItem = null }) {
                    Text(stringResource(R.string.action_understood))
                }
            },
            title = { Text(stringResource(R.string.dialog_skipped_title)) },
            text = {
                Text(skippedReasonDescription(skippedItem))
            },
        )
    }
}

@Composable
private fun HistoryActionOverlay(
    item: HistoryItem,
    onDismiss: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = when (item.contentKind) {
                            ContentKind.TEXT -> stringResource(R.string.history_action_title_text)
                            ContentKind.IMAGE -> stringResource(R.string.history_action_title_image)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = historyPreviewText(item),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.contentKind == ContentKind.IMAGE) {
                        Text(
                            text = item.mimeType ?: "image/*",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = item.clientCreatedAt.toLocalDisplayTime(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onPrimaryAction,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(
                                when (item.contentKind) {
                                    ContentKind.TEXT -> stringResource(R.string.action_copy)
                                    ContentKind.IMAGE -> stringResource(R.string.button_open_image)
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun localHistoryModeLabel(
    mode: LocalHistoryMode,
    retentionDays: Int,
): String = when (mode) {
    LocalHistoryMode.FOREVER -> stringResource(R.string.local_history_mode_forever_long)
    LocalHistoryMode.DAYS -> pluralStringResource(R.plurals.local_history_mode_days_long, retentionDays, retentionDays)
    LocalHistoryMode.DISABLED -> stringResource(R.string.local_history_mode_disabled_long)
}

@Composable
private fun syncStateLabel(state: SyncState): String = when (state) {
    SyncState.PENDING -> stringResource(R.string.sync_state_pending)
    SyncState.SYNCED -> stringResource(R.string.sync_state_synced)
    SyncState.FAILED -> stringResource(R.string.sync_state_failed)
    SyncState.SKIPPED -> stringResource(R.string.sync_state_skipped)
}

@Composable
private fun historyPreviewText(item: HistoryItem): String = when (item.contentKind) {
    ContentKind.TEXT -> item.previewText
    ContentKind.IMAGE -> stringResource(
        R.string.content_image_preview,
        item.previewText.ifBlank { stringResource(R.string.history_image_default_name) },
    )
}

@Composable
private fun historyStatusChipColors(state: SyncState) = when (state) {
    SyncState.PENDING -> AssistChipDefaults.assistChipColors(
        containerColor = Color(0xFFE7EEF7),
        labelColor = Color(0xFF425368),
    )

    SyncState.SYNCED -> AssistChipDefaults.assistChipColors(
        containerColor = Color(0xFFDFF4E6),
        labelColor = Color(0xFF0B6B43),
    )

    SyncState.SKIPPED -> AssistChipDefaults.assistChipColors(
        containerColor = Color(0xFFF6E8D8),
        labelColor = Color(0xFF8A5A12),
    )

    SyncState.FAILED -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        labelColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun skippedReasonDescription(item: HistoryItem): String = when (item.skipReason) {
    "image_server_limit", "图片超过服务端上传大小限制。" -> stringResource(R.string.image_skip_reason_server_limit)
    "image_file_missing", "本地历史中的源图片文件已丢失。" -> stringResource(R.string.image_skip_reason_file_missing)
    null -> when (item.contentKind) {
        ContentKind.IMAGE -> stringResource(R.string.skipped_reason_image_default)
        ContentKind.TEXT -> stringResource(R.string.skipped_reason_text_default)
    }

    else -> item.skipReason
}

private fun String.toLocalDisplayTime(): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    return runCatching {
        Instant.parse(this).atZone(zone).format(formatter)
    }.recoverCatching {
        OffsetDateTime.parse(this).atZoneSameInstant(zone).format(formatter)
    }.recoverCatching {
        LocalDateTime.parse(this).format(formatter)
    }.getOrElse { this }
}

@Composable
private fun imageUploadLimitLabel(limitBytes: Long?): String {
    val limit = limitBytes ?: return stringResource(R.string.image_limit_not_provided)
    if (limit <= 0L) return stringResource(R.string.image_limit_unlimited)
    val mb = limit / (1024f * 1024f)
    return if (mb >= 10f || mb % 1f == 0f) {
        "${mb.toInt()} MB"
    } else {
        String.format("%.1f MB", mb)
    }
}

private enum class MainTab(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    OVERVIEW(R.string.tab_overview, Icons.Outlined.Home),
    HISTORY(R.string.tab_history, Icons.Outlined.History),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Tune),
}

private data class SettingsCardSavedState(
    val syncSendEnabled: Boolean,
    val receiveRealtimeEnabled: Boolean,
    val localHistoryMode: LocalHistoryMode,
    val localHistoryRetentionDays: Int,
    val textCaptureMinChars: Int,
    val textCaptureMaxChars: Int,
    val historySyncWifiOnly: Boolean,
)

private fun Modifier.clearFocusOnTapOutside(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val currentFocusManager by rememberUpdatedState(focusManager)

    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial)
            currentFocusManager.clearFocus()
            waitForUpOrCancellation(pass = PointerEventPass.Final)
        }
    }
}

package com.clipboardriver.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.clipboardriver.client.model.PanelMode
import com.clipboardriver.client.model.CaptureResultState
import com.clipboardriver.client.model.ReceiveState
import com.clipboardriver.client.model.RealtimeStatus
import com.clipboardriver.client.model.SessionOwner
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ActionNotificationService : Service() {
    private val container: AppContainer by lazy {
        (application as ClipboardRiverApplication).container
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager

    private var overlayView: View? = null
    private var overlayViews: OverlayViews? = null
    private var overlayMode: PanelMode? = null
    private var overlayActionJob: Job? = null
    private var overlayCollectorJob: Job? = null
    private var overlayAutoCloseJob: Job? = null
    private var pendingOpenJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_OPEN_CAPTURE_PANEL -> openOverlay(
                mode = PanelMode.CAPTURE,
                delayMillis = intent.openDelayMillis(),
                sharedImageUri = intent.sharedImageUri(),
            )
            ACTION_OPEN_RECEIVE_PANEL -> openOverlay(PanelMode.RECEIVE, intent.openDelayMillis())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runBlocking {
            dismissOverlayInternal(releaseReceiveSession = true)
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openMainIntent = MainActivityIntentFactory.intent(this)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_content_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    openMainIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                getString(R.string.action_capture),
                notificationActionPendingIntent(this, PanelMode.CAPTURE, 2),
            )
            .addAction(
                0,
                getString(R.string.action_receive),
                notificationActionPendingIntent(this, PanelMode.RECEIVE, 3),
            )
            .build()
    }

    private fun openOverlay(
        mode: PanelMode,
        delayMillis: Long = 0L,
        sharedImageUri: Uri? = null,
    ) {
        if (!OverlayWindowSupport.canDraw(this)) {
            Toast.makeText(this, getString(R.string.toast_overlay_permission_required), Toast.LENGTH_LONG).show()
            OverlayWindowSupport.openPermissionSettings(this)
            return
        }
        pendingOpenJob?.cancel()
        serviceScope.launch {
            pendingOpenJob = this.coroutineContext[Job]
            if (delayMillis > 0L) {
                delay(delayMillis)
            }
            dismissOverlayInternal(releaseReceiveSession = true)
            showOverlay(mode, sharedImageUri)
            pendingOpenJob = null
        }
    }

    private suspend fun showOverlay(
        mode: PanelMode,
        sharedImageUri: Uri? = null,
    ) {
        val view = createOverlayView()
        val params = createOverlayLayoutParams()
        val addSucceeded = runCatching {
            windowManager.addView(view, params)
        }.isSuccess
        if (!addSucceeded) {
            overlayViews = null
            overlayMode = null
            Toast.makeText(this, getString(R.string.toast_overlay_show_failed), Toast.LENGTH_LONG).show()
            return
        }
        overlayView = view
        overlayMode = mode

        when (mode) {
            PanelMode.CAPTURE -> startCaptureOverlay(sharedImageUri)
            PanelMode.RECEIVE -> startReceiveOverlay()
        }
    }

    private suspend fun dismissOverlayInternal(releaseReceiveSession: Boolean) {
        overlayAutoCloseJob?.cancel()
        overlayAutoCloseJob = null
        overlayActionJob?.cancel()
        overlayActionJob = null
        overlayCollectorJob?.cancel()
        overlayCollectorJob = null
        pendingOpenJob?.cancel()
        pendingOpenJob = null

        if (releaseReceiveSession && overlayMode == PanelMode.RECEIVE) {
            container.realtimeSessionManager.releaseSession(SessionOwner.RECEIVE_PANEL)
        }

        overlayView?.let { view ->
            runCatching {
                windowManager.removeView(view)
            }
        }
        overlayView = null
        overlayViews = null
        overlayMode = null
    }

    private fun dismissOverlay() {
        serviceScope.launch {
            dismissOverlayInternal(releaseReceiveSession = true)
        }
    }

    private fun startCaptureOverlay(sharedImageUri: Uri? = null) {
        val initialMessage = if (sharedImageUri != null) {
            getString(R.string.share_image_processing)
        } else {
            getString(R.string.capture_processing_current)
        }
        render(
            OverlayRenderState(
                mode = PanelMode.CAPTURE,
                title = getString(R.string.action_capture),
                message = initialMessage,
            ),
        )

        overlayActionJob = serviceScope.launch {
            val result = if (sharedImageUri != null) {
                container.syncCoordinator.importSharedImage(sharedImageUri)
            } else {
                container.captureCoordinator.captureOnce()
            }
            render(
                OverlayRenderState(
                    mode = PanelMode.CAPTURE,
                    title = getString(R.string.action_capture),
                    message = result.message,
                ),
            )
            scheduleAutoClose(
                delayMillis = if (result.state == CaptureResultState.SYNCED) {
                    SUCCESS_AUTO_CLOSE_MILLIS
                } else {
                    DEFAULT_AUTO_CLOSE_MILLIS
                },
            )
        }
    }

    private fun startReceiveOverlay() {
        render(
            OverlayRenderState(
                mode = PanelMode.RECEIVE,
                title = getString(R.string.action_receive),
                message = getString(R.string.panel_receive_preparing),
                wsState = getString(R.string.status_not_connected_yet),
            ),
        )

        overlayCollectorJob = serviceScope.launch {
            combine(
                container.realtimeSessionManager.status,
                container.receiveCoordinator.state,
            ) { realtimeStatus, receiveState ->
                ReceiveOverlaySnapshot(realtimeStatus, receiveState)
            }.collect { snapshot ->
                render(
                    OverlayRenderState(
                        mode = PanelMode.RECEIVE,
                        title = getString(R.string.action_receive),
                        message = snapshot.receiveState.status.ifBlank { getString(R.string.panel_receive_preparing) },
                        wsState = snapshot.realtimeStatus.stateLabel.ifBlank { getString(R.string.status_not_connected_yet) },
                        latestPreview = snapshot.receiveState.latestPreview,
                        latestReceivedAt = snapshot.receiveState.latestReceivedAt,
                        latestOpenUri = snapshot.receiveState.latestOpenUri,
                        latestError = snapshot.receiveState.latestError ?: snapshot.realtimeStatus.lastError,
                    ),
                )
                if (
                    snapshot.receiveState.terminal ||
                    (!snapshot.realtimeStatus.connected && snapshot.realtimeStatus.attempt >= 5)
                ) {
                    scheduleAutoClose(
                        delayMillis = when {
                            snapshot.receiveState.hasOpenableImage() -> IMAGE_SUCCESS_AUTO_CLOSE_MILLIS
                            snapshot.receiveState.isSuccessfulTerminal() -> SUCCESS_AUTO_CLOSE_MILLIS
                            else -> DEFAULT_AUTO_CLOSE_MILLIS
                        },
                    )
                }
            }
        }

        overlayActionJob = serviceScope.launch {
            val config = container.configRepository.snapshot()
            val token = container.tokenStore.currentToken()
            if (!config.receiveRealtimeEnabled) {
                container.receiveCoordinator.showDisabledMessage()
                return@launch
            }
            if (token.isNullOrBlank() || config.serverBaseUrl.isBlank()) {
                container.receiveCoordinator.showRegistrationRequiredMessage()
                return@launch
            }
            container.receiveCoordinator.prepareForSession()
            container.realtimeSessionManager.requestSession(SessionOwner.RECEIVE_PANEL)
        }
    }

    private fun scheduleAutoClose(delayMillis: Long = DEFAULT_AUTO_CLOSE_MILLIS) {
        if (overlayAutoCloseJob?.isActive == true) return
        overlayAutoCloseJob = serviceScope.launch {
            delay(delayMillis)
            dismissOverlayInternal(releaseReceiveSession = true)
        }
    }

    private fun createOverlayView(): View {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_ClipboardRiver)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.view_action_overlay, null, false)
        val views = OverlayViews(
            root = view,
            titleView = view.findViewById(R.id.overlayTitle),
            messageView = view.findViewById(R.id.overlayMessage),
            wsStateView = view.findViewById(R.id.overlayWsState),
            latestSection = view.findViewById(R.id.overlayLatestSection),
            latestTextView = view.findViewById(R.id.overlayLatestText),
            latestReceivedAtView = view.findViewById(R.id.overlayLatestReceivedAt),
            openImageButton = view.findViewById(R.id.overlayOpenImageButton),
            errorView = view.findViewById(R.id.overlayError),
            closeButton = view.findViewById(R.id.overlayCloseButton),
        )
        views.closeButton.setOnClickListener {
            dismissOverlay()
        }
        overlayViews = views
        return view
    }

    private fun createOverlayLayoutParams(): WindowManager.LayoutParams {
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        val yOffset = statusBarHeightPx() + (resources.displayMetrics.density * 24).toInt()
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = yOffset
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setCanPlayMoveAnimation(false)
            }
        }
    }

    private fun statusBarHeightPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun render(state: OverlayRenderState) {
        val views = overlayViews ?: return
        views.titleView.text = state.title
        views.messageView.text = state.message
        views.wsStateView.isVisible = state.mode == PanelMode.RECEIVE
        views.wsStateView.text = getString(
            R.string.connection_status_format,
            state.wsState.ifBlank { getString(R.string.status_not_connected_yet) },
        )

        val hasLatestPreview = !state.latestPreview.isNullOrBlank()
        views.latestSection.isVisible = hasLatestPreview
        views.latestTextView.text = state.latestPreview.orEmpty().toOverlayPreview()
        views.latestReceivedAtView.text = state.latestReceivedAt.orEmpty()
        views.latestReceivedAtView.isVisible = !state.latestReceivedAt.isNullOrBlank()
        val openUri = state.latestOpenUri?.takeIf { it.isNotBlank() }
        views.openImageButton.isVisible = !openUri.isNullOrBlank()
        views.openImageButton.setOnClickListener(
            if (openUri != null) {
                View.OnClickListener { openImage(openUri) }
            } else {
                null
            },
        )

        views.errorView.isVisible = !state.latestError.isNullOrBlank()
        views.errorView.text = state.latestError.orEmpty()
    }

    private fun openImage(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, getString(R.string.toast_invalid_image_uri), Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val opened = runCatching {
            startActivity(intent)
            true
        }.recoverCatching { throwable ->
            if (throwable is ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.toast_no_image_viewer), Toast.LENGTH_LONG).show()
                false
            } else {
                throw throwable
            }
        }.getOrElse {
            Toast.makeText(this, getString(R.string.toast_open_image_failed), Toast.LENGTH_LONG).show()
            false
        }
        if (opened) {
            dismissOverlay()
        }
    }

    companion object {
        private const val ACTION_START = "com.clipboardriver.client.action.START_NOTIFICATION"
        private const val ACTION_OPEN_CAPTURE_PANEL = "com.clipboardriver.client.action.OPEN_CAPTURE_PANEL"
        private const val ACTION_OPEN_RECEIVE_PANEL = "com.clipboardriver.client.action.OPEN_RECEIVE_PANEL"
        private const val EXTRA_OPEN_DELAY_MILLIS = "open_delay_millis"
        private const val EXTRA_SHARED_IMAGE_URI = "shared_image_uri"
        private const val CHANNEL_ID = "clipboard_river_actions"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_AUTO_CLOSE_MILLIS = 3_500L
        private const val SUCCESS_AUTO_CLOSE_MILLIS = 2_000L
        private const val IMAGE_SUCCESS_AUTO_CLOSE_MILLIS = 4_000L

        fun start(context: Context) {
            val intent = Intent(context, ActionNotificationService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ActionNotificationService::class.java))
        }

        fun openPanel(context: Context, mode: PanelMode, delayMillis: Long = 0L): Boolean {
            if (!OverlayWindowSupport.canDraw(context)) {
                OverlayWindowSupport.openPermissionSettings(context)
                return false
            }
            val intent = Intent(context, ActionNotificationService::class.java).apply {
                action = when (mode) {
                    PanelMode.CAPTURE -> ACTION_OPEN_CAPTURE_PANEL
                    PanelMode.RECEIVE -> ACTION_OPEN_RECEIVE_PANEL
                }
                putExtra(EXTRA_OPEN_DELAY_MILLIS, delayMillis)
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun openSharedImage(
            context: Context,
            uri: Uri,
            delayMillis: Long = 0L,
        ): Boolean {
            if (!OverlayWindowSupport.canDraw(context)) {
                OverlayWindowSupport.openPermissionSettings(context)
                return false
            }
            val intent = Intent(context, ActionNotificationService::class.java).apply {
                action = ACTION_OPEN_CAPTURE_PANEL
                putExtra(EXTRA_OPEN_DELAY_MILLIS, delayMillis)
                putExtra(EXTRA_SHARED_IMAGE_URI, uri.toString())
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        private fun notificationActionPendingIntent(
            context: Context,
            mode: PanelMode,
            requestCode: Int,
        ): android.app.PendingIntent {
            val intent = if (mode == PanelMode.CAPTURE) {
                NotificationCaptureActivity.intent(context)
            } else {
                NotificationActionActivity.intent(context, mode)
            }
            return android.app.PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }
}

private object MainActivityIntentFactory {
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}

private fun Intent.openDelayMillis(): Long = getLongExtra("open_delay_millis", 0L).coerceAtLeast(0L)

private fun Intent.sharedImageUri(): Uri? =
    getStringExtra("shared_image_uri")?.let(Uri::parse)

private data class OverlayViews(
    val root: View,
    val titleView: TextView,
    val messageView: TextView,
    val wsStateView: TextView,
    val latestSection: View,
    val latestTextView: TextView,
    val latestReceivedAtView: TextView,
    val openImageButton: MaterialButton,
    val errorView: TextView,
    val closeButton: MaterialButton,
)

private data class OverlayRenderState(
    val mode: PanelMode,
    val title: String,
    val message: String,
    val wsState: String = "",
    val latestPreview: String? = null,
    val latestReceivedAt: String? = null,
    val latestOpenUri: String? = null,
    val latestError: String? = null,
)

private data class ReceiveOverlaySnapshot(
    val realtimeStatus: RealtimeStatus,
    val receiveState: ReceiveState,
)

private fun ReceiveState.isSuccessfulTerminal(): Boolean =
    terminal && latestError.isNullOrBlank() && !latestPreview.isNullOrBlank()

private fun ReceiveState.hasOpenableImage(): Boolean =
    terminal && latestError.isNullOrBlank() && !latestOpenUri.isNullOrBlank()

private fun String.toOverlayPreview(maxChars: Int = 180): String {
    val normalized = replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    return if (normalized.length <= maxChars) {
        normalized
    } else {
        normalized.take(maxChars).trimEnd() + "…"
    }
}

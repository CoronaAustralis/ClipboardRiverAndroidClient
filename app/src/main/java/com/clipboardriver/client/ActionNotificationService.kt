package com.clipboardriver.client

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.clipboardriver.client.model.*
import com.google.android.material.button.MaterialButton
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class ActionNotificationService : Service() {
    private val container get() = (application as ClipboardRiverApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: View? = null
    private var mode: PanelMode? = null
    private var opening: Job? = null
    private var action: Job? = null
    private var collector: Job? = null
    private var autoClose: Job? = null
    private var deadline: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, notification(this))
        val requested = when (intent?.action) {
            OPEN_CAPTURE -> PanelMode.CAPTURE
            OPEN_RECEIVE -> PanelMode.RECEIVE
            else -> null
        }
        if (requested == null || !OverlayWindowSupport.canDraw(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        opening?.cancel()
        opening = scope.launch {
            clearOverlay()
            showOverlay(requested)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeView()
        scope.cancel()
        container.realtimeSessionManager.scope.launch {
            container.realtimeSessionManager.releaseSession(SessionOwner.RECEIVE_PANEL)
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        start(this)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        dismiss()
    }

    private suspend fun clearOverlay() {
        action?.cancel()
        collector?.cancel()
        autoClose?.cancel()
        deadline?.cancel()
        action = null
        collector = null
        autoClose = null
        deadline = null
        container.realtimeSessionManager.releaseSession(SessionOwner.RECEIVE_PANEL)
        removeView()
    }

    private fun removeView() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        mode = null
    }

    private suspend fun showOverlay(requested: PanelMode) {
        val view = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_ClipboardRiver))
            .inflate(R.layout.view_action_overlay, null, false)
        view.isFocusableInTouchMode = true
        view.findViewById<MaterialButton>(R.id.overlayCloseButton).setOnClickListener { dismiss() }
        view.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else false
        }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.density * 48).toInt()
        }
        try {
            windowManager.addView(view, params)
            overlay = view
            mode = requested
            view.requestFocus()
            render(getString(if (requested == PanelMode.CAPTURE) R.string.cr_sending else R.string.cr_receive_waiting))
            if (!awaitFocus(view)) {
                render(getString(R.string.cr_focus_failed), error = getString(R.string.cr_focus_failed))
                scheduleClose(DEFAULT_CLOSE)
                return
            }
            if (requested == PanelMode.CAPTURE) startCapture() else startReceive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(this, R.string.cr_overlay_failed, Toast.LENGTH_LONG).show()
            dismiss()
        }
    }

    private suspend fun awaitFocus(view: View): Boolean {
        if (view.hasWindowFocus()) return true
        return withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (hasFocus && continuation.isActive) {
                            view.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                            continuation.resume(true)
                        }
                    }
                }
                view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                if (view.hasWindowFocus()) listener.onWindowFocusChanged(true)
                continuation.invokeOnCancellation {
                    view.post {
                        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                    }
                }
            }
        } ?: false
    }

    private fun startCapture() {
        action = scope.launch {
            val result = container.syncCoordinator.captureCurrentClipboard()
            render(result.message, error = result.message.takeIf { result.state == CaptureResultState.ERROR })
            scheduleClose(if (result.state == CaptureResultState.UPLOADED) SUCCESS_CLOSE else DEFAULT_CLOSE)
        }
    }

    private fun startReceive() {
        action = scope.launch {
            val config = container.configRepository.snapshot()
            if (container.tokenStore.currentToken().isNullOrBlank() ||
                container.realtimeSessionManager.status.value.state == ConnectionState.AUTH_REQUIRED) {
                render(getString(R.string.cr_not_registered), error = getString(R.string.cr_not_registered))
                scheduleClose(DEFAULT_CLOSE)
                return@launch
            }
            if (!config.receiveRealtimeEnabled) {
                render(getString(R.string.cr_receive_paused))
                scheduleClose(DEFAULT_CLOSE)
                return@launch
            }
            container.receiveCoordinator.prepareForSession()
            container.realtimeSessionManager.requestSession(SessionOwner.RECEIVE_PANEL)
            collector = scope.launch {
                combine(container.realtimeSessionManager.status, container.receiveCoordinator.state) { status, received ->
                    status to received
                }.collect { (status, received) ->
                    render(
                        received.status.ifBlank { getString(R.string.cr_receive_waiting) },
                        connection = connectionText(status), received = received,
                        error = received.latestError ?: status.lastError,
                    )
                    if (received.latestReceivedAt != null || received.terminal) deadline?.cancel()
                    if (received.terminal || status.terminal) {
                        scheduleClose(when {
                            received.latestOpenUri != null -> FILE_CLOSE
                            received.terminal && received.latestError == null -> SUCCESS_CLOSE
                            else -> DEFAULT_CLOSE
                        })
                    }
                }
            }
            deadline = scope.launch {
                delay(60_000)
                container.realtimeSessionManager.releaseSession(SessionOwner.RECEIVE_PANEL)
                collector?.cancel()
                render(getString(R.string.cr_receive_timeout))
                scheduleClose(DEFAULT_CLOSE)
            }
        }
    }

    private fun connectionText(status: RealtimeStatus): String = getString(when (status.state) {
        ConnectionState.DISCONNECTED -> R.string.cr_disconnected
        ConnectionState.CONNECTING -> R.string.cr_connecting
        ConnectionState.HANDSHAKING -> R.string.cr_handshaking
        ConnectionState.CONNECTED -> R.string.cr_connected
        ConnectionState.RETRYING -> R.string.cr_retrying
        ConnectionState.AUTH_REQUIRED -> R.string.cr_auth_required
        ConnectionState.ERROR -> R.string.cr_connection_failed
    })

    private fun render(message: String, connection: String? = null, received: ReceiveState? = null, error: String? = null) {
        val view = overlay ?: return
        view.findViewById<TextView>(R.id.overlayTitle).text =
            getString(if (mode == PanelMode.CAPTURE) R.string.cr_send_clipboard else R.string.cr_receive)
        view.findViewById<TextView>(R.id.overlayMessage).text = message
        view.findViewById<TextView>(R.id.overlayWsState).apply {
            isVisible = connection != null
            text = connection.orEmpty()
        }
        view.findViewById<View>(R.id.overlayLatestSection).isVisible = received?.latestPreview != null
        view.findViewById<TextView>(R.id.overlayLatestText).text = received?.latestPreview.orEmpty().take(180)
        view.findViewById<TextView>(R.id.overlayLatestReceivedAt).apply {
            isVisible = received?.latestReceivedAt != null
            text = received?.latestReceivedAt.orEmpty()
        }
        view.findViewById<MaterialButton>(R.id.overlayOpenImageButton).apply {
            isVisible = received?.latestOpenUri != null
            setOnClickListener {
                received?.latestOpenUri?.let { uri ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(
                            Uri.parse(uri), received.latestOpenMimeType ?: "*/*",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        dismiss()
                    }.onFailure { Toast.makeText(this@ActionNotificationService, R.string.cr_open_failed, Toast.LENGTH_LONG).show() }
                }
            }
        }
        view.findViewById<TextView>(R.id.overlayError).apply {
            isVisible = error != null
            text = error.orEmpty()
        }
    }

    private fun scheduleClose(delayMillis: Long) {
        if (autoClose?.isActive == true) return
        autoClose = scope.launch { delay(delayMillis); dismiss() }
    }

    private fun dismiss() {
        scope.launch {
            opening?.cancel()
            clearOverlay()
            stopSelf()
        }
    }

    companion object {
        private const val OPEN_CAPTURE = "com.clipboardriver.client.action.OPEN_CAPTURE_PANEL"
        private const val OPEN_RECEIVE = "com.clipboardriver.client.action.OPEN_RECEIVE_PANEL"
        private const val CHANNEL_ID = "clipboard_river_actions"
        // The shortcut becomes the foreground-service notification while a window is open.
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_CLOSE = 3_500L
        private const val SUCCESS_CLOSE = 2_000L
        private const val FILE_CLOSE = 4_000L

        fun start(context: Context) {
            ensureChannel(context)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED) return
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ActionNotificationService::class.java))
        }

        fun openPanel(context: Context, mode: PanelMode): Boolean {
            val coordinator = (context.applicationContext as ClipboardRiverApplication).container.syncCoordinator
            if (mode == PanelMode.CAPTURE && coordinator.busy.value) {
                Toast.makeText(context, R.string.cr_busy, Toast.LENGTH_SHORT).show()
                return true
            }
            if (!OverlayWindowSupport.canDraw(context)) return false
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ActionNotificationService::class.java)
                    .setAction(if (mode == PanelMode.CAPTURE) OPEN_CAPTURE else OPEN_RECEIVE))
                true
            }.getOrDefault(false)
        }

        private fun notification(context: Context): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.cr_notification_shortcuts))
                .setContentIntent(PendingIntent.getActivity(context, 1,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .addAction(0, context.getString(R.string.cr_send_clipboard), actionIntent(context, PanelMode.CAPTURE, 2))
                .addAction(0, context.getString(R.string.cr_receive), actionIntent(context, PanelMode.RECEIVE, 3))
                .build()

        private fun actionIntent(context: Context, mode: PanelMode, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(context, requestCode, NotificationActionActivity.intent(context, mode),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.cr_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
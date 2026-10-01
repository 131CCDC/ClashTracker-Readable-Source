package dev.clashaiaa.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * System overlay showing the live probe readout.
 *
 * The service owns its own [ProbeClient] so the overlay keeps updating while
 * the game is in the foreground and the control activity is stopped.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }

    private var panel: View? = null
    private var statusText: TextView? = null
    private var trackerHud: TrackerHudView? = null
    private var scalablePanel: ScalablePanelLayout? = null
    private var ghostLayer: GhostLayerView? = null
    private var client: ProbeClient? = null
    private var ghostClient: GhostClient? = null
    private val ghostState = GhostState()
    private val recentPlays = RecentPlayTracker()

    /**
     * Single source of "which player is me" for both layers. A confirmed
     * identity and the local deck are cached so the next battle is correct from
     * its first frame.
     */
    private val identityResolver = IdentityResolver(
        notifyLearned = { accountId -> SettingsStore.saveLearnedAccountId(this, accountId) },
        notifyLearnedDeck = { signature -> SettingsStore.saveLearnedDeck(this, signature) },
    )

    private var settings: OverlaySettings = OverlaySettings.DEFAULT
    private var connected = false
    private var state: BattleState? = null
    private var panelPlacement = PanelPlacement(0, 0, PanelGeometry.DEFAULT_SCALE)
    private var panelGesture = PanelGesture.NONE
    private var gestureStartX = 0
    private var gestureStartY = 0
    private var gestureTouchX = 0f
    private var gestureTouchY = 0f
    private var gestureStartScale = PanelGeometry.DEFAULT_SCALE
    private var gestureStartWidth = 1
    private var gestureStartHeight = 1
    private var basePanelWidth = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showGhostLayer()
        showPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_SETTINGS) {
            applySettings(SettingsStore.load(this))
        }
        if (intent?.action == ACTION_GHOST_OPACITY) {
            // The Ghost Drop sliders: a repaint of the marker layer and nothing
            // else. No client is restarted, the current battle is not touched,
            // and the markers already on the arena pick the new alpha up on the
            // next frame, so dragging the slider is visible immediately.
            val stored = SettingsStore.load(this)
            settings = settings.copy(
                ghostCardOpacity = stored.ghostCardOpacity,
                ghostTileOpacity = stored.ghostTileOpacity,
            )
            applyGhostOpacity(settings)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        client?.stop()
        client = null
        ghostClient?.stop()
        ghostClient = null
        ghostState.reset()
        panel?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        ghostLayer?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panel = null
        scalablePanel = null
        ghostLayer = null
        super.onDestroy()
    }

    private fun applySettings(updated: OverlaySettings) {
        val endpointChanged = updated.host != settings.host || updated.port != settings.port
        val ghostChanged = updated.ghostEnabled != settings.ghostEnabled
        // Only a changed identity input invalidates the session's evidence;
        // pushing the same settings again (opacity, endpoint) must not drop a
        // just-locked identity back to the provisional seat.
        val identityInputsChanged = updated.localAccountId != settings.localAccountId ||
            updated.localOwner != settings.localOwner
        settings = updated
        identityResolver.configuredAccountId = updated.localAccountId
        identityResolver.configuredOwner = updated.localOwner
        if (identityInputsChanged) identityResolver.reset()
        panel?.alpha = updated.alpha / 255f
        ghostLayer?.visibility = if (updated.ghostEnabled) View.VISIBLE else View.GONE
        applyGhostOpacity(updated)
        connected = false
        state = null
        recentPlays.reset()
        ghostState.reset()
        ghostLayer?.render(emptyList())
        render()
        if (endpointChanged) {
            startClient()
        }
        if (endpointChanged || ghostChanged) {
            startGhostClient()
        }
    }

    /**
     * Push the Ghost Drop alphas onto the marker layer. Nothing else about the
     * layer changes: the pending markers keep their identity, their position and
     * their remaining lifetime, so a slider drag is a repaint, not a restart.
     */
    private fun applyGhostOpacity(updated: OverlaySettings) {
        ghostLayer?.opacity = updated.ghostOpacity
    }

    private fun startClient() {
        client?.stop()
        val started = ProbeClient(
            host = settings.host,
            port = settings.port,
            executor = mainExecutor,
            listener = { event -> onProbeEvent(event) },
        )
        client = started
        started.start()
    }

    private fun startGhostClient() {
        ghostClient?.stop()
        if (!settings.ghostEnabled) {
            ghostClient = null
            ghostState.reset()
            ghostLayer?.render(emptyList())
            return
        }
        val started = GhostClient(
            host = settings.host,
            port = settings.port,
            executor = mainExecutor,
            listener = { event -> onGhostEvent(event) },
        )
        ghostClient = started
        started.start()
    }

    private fun onGhostEvent(event: GhostProbeEvent) {
        when (event) {
            is GhostProbeEvent.Snapshot -> {
                identityResolver.onGhost(event.feed)
                val identity = identityResolver.identity(state)
                val markers = ghostState.update(
                    feed = event.feed,
                    nowMs = event.receivedAtMs,
                    // Only a proved account may place a marker: the origin of a
                    // drop is drawn in the local view transform, so a guessed
                    // seat mirrors every marker onto the wrong side.
                    selfAccountId = if (identity.verified) identity.accountId else 0L,
                )
                ghostLayer?.render(markers)
                // The hand HUD shares this identity, so the two layers can no
                // longer disagree about which seat is local.
                render()
            }
            is GhostProbeEvent.Disconnected -> {
                ghostState.reset()
                ghostLayer?.render(emptyList())
                render()
            }
        }
    }

    private fun onProbeEvent(event: ProbeEvent) {
        when (event) {
            is ProbeEvent.Connected -> {
                connected = true
                render()
            }
            is ProbeEvent.Snapshot -> {
                connected = true
                state = event.state
                identityResolver.onFrame(event.state)
                render()
            }
            is ProbeEvent.Disconnected -> {
                // Never keep showing the last frame as if it were live.
                connected = false
                state = null
                recentPlays.reset()
                ghostState.reset()
                ghostLayer?.render(emptyList())
                render()
            }
        }
    }

    private fun showGhostLayer() {
        val view = GhostLayerView(this)
        val ghostParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        ghostLayer = view
        windowManager.addView(view, ghostParams)
    }

    private fun showPanel() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_panel, null) as ScalablePanelLayout
        // The window is the real layout container. The inflated root has no XML
        // parent, so its own layout_width is dropped; without an explicit
        // window width the weighted status line collapses and
        // "Probe: Disconnected" is clipped to "Probe:".
        val screenWidth = resources.displayMetrics.widthPixels
        basePanelWidth = minOf(dp(PANEL_WIDTH_DP), (screenWidth * 0.94f).toInt())
        panelPlacement = SettingsStore.loadPanelPlacement(this)
            ?: PanelGeometry.defaultPlacement(screenWidth, dp(DEFAULT_TOP_DP), basePanelWidth)
        panelPlacement = panelPlacement.copy(scale = PanelGeometry.clampScale(panelPlacement.scale))
        view.baseWidthPx = basePanelWidth
        view.contentScale = panelPlacement.scale
        params = WindowManager.LayoutParams(
            (basePanelWidth * panelPlacement.scale).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelPlacement.x
            y = panelPlacement.y
        }

        statusText = view.findViewById(R.id.statusText)
        trackerHud = view.findViewById(R.id.trackerHud)

        view.findViewById<View>(R.id.closeButton).setOnClickListener { stopSelf() }
        view.findViewById<View>(R.id.alphaButton).setOnClickListener { cycleAlpha() }
        view.gestureHandler = { event -> onPanelGesture(view, event) }

        panel = view
        scalablePanel = view
        windowManager.addView(view, params)
        view.post { clampAndPersistPanel(view) }
        running = true

        settings = SettingsStore.load(this)
        identityResolver.configuredAccountId = settings.localAccountId
        identityResolver.configuredOwner = settings.localOwner
        identityResolver.seedLearned(SettingsStore.loadLearnedAccountId(this))
        identityResolver.seedLearnedDeck(SettingsStore.loadLearnedDeck(this))
        view.alpha = settings.alpha / 255f
        ghostLayer?.visibility = if (settings.ghostEnabled) View.VISIBLE else View.GONE
        applyGhostOpacity(settings)
        render()
        startClient()
        startGhostClient()
    }

    private fun onPanelGesture(view: ScalablePanelLayout, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val resizeZone = dp(44) * panelPlacement.scale
                val headerHeight = dp(38) * panelPlacement.scale
                val headerControls = dp(86) * panelPlacement.scale
                panelGesture = when {
                    event.x >= view.width - resizeZone && event.y >= view.height - resizeZone -> PanelGesture.RESIZE
                    event.y <= headerHeight && event.x < view.width - headerControls -> PanelGesture.DRAG
                    else -> PanelGesture.NONE
                }
                if (panelGesture == PanelGesture.NONE) return false
                gestureStartX = params.x
                gestureStartY = params.y
                gestureTouchX = event.rawX
                gestureTouchY = event.rawY
                gestureStartScale = panelPlacement.scale
                gestureStartWidth = view.width.coerceAtLeast(1)
                gestureStartHeight = view.height.coerceAtLeast(1)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - gestureTouchX
                val deltaY = event.rawY - gestureTouchY
                when (panelGesture) {
                    PanelGesture.DRAG -> {
                        panelPlacement = panelPlacement.copy(
                            x = gestureStartX + deltaX.toInt(),
                            y = gestureStartY + deltaY.toInt(),
                        )
                    }
                    PanelGesture.RESIZE -> {
                        val nextScale = PanelGeometry.resizedScale(
                            gestureStartScale,
                            deltaX,
                            deltaY,
                            gestureStartWidth,
                            gestureStartHeight,
                        )
                        panelPlacement = panelPlacement.copy(scale = nextScale)
                        view.contentScale = nextScale
                        params.width = (basePanelWidth * nextScale).roundToInt()
                    }
                    PanelGesture.NONE -> return false
                }
                val estimatedHeight = if (panelGesture == PanelGesture.RESIZE) {
                    (gestureStartHeight * panelPlacement.scale / gestureStartScale).roundToInt()
                } else {
                    view.height
                }
                panelPlacement = PanelGeometry.clamp(
                    panelPlacement,
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels,
                    params.width,
                    estimatedHeight,
                )
                params.x = panelPlacement.x
                params.y = panelPlacement.y
                runCatching { windowManager.updateViewLayout(view, params) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (panelGesture == PanelGesture.NONE) return false
                clampAndPersistPanel(view)
                panelGesture = PanelGesture.NONE
                view.performClick()
                return true
            }
        }
        return false
    }

    private fun clampAndPersistPanel(view: ScalablePanelLayout) {
        panelPlacement = PanelGeometry.clamp(
            panelPlacement,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
            view.width.coerceAtLeast(params.width),
            view.height,
        )
        params.x = panelPlacement.x
        params.y = panelPlacement.y
        runCatching { windowManager.updateViewLayout(view, params) }
        SettingsStore.savePanelPlacement(this, panelPlacement)
    }

    private fun cycleAlpha() {
        val next = when {
            settings.alpha > 200 -> 160
            settings.alpha > 110 -> 80
            else -> 255
        }
        settings = settings.copy(alpha = next)
        SettingsStore.save(this, settings)
        panel?.alpha = next / 255f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun render() {
        val identity = identityResolver.identity(state)
        val recent = if (identity.usable) {
            recentPlays.update(state, identity.owner, identity.accountId)
        } else {
            recentPlays.reset()
            emptyList()
        }
        val model = OverlayRenderer.model(
            connected,
            state,
            identity,
            recent,
        )
        statusText?.text = model.status
        trackerHud?.render(model)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_probe)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.clashaiaa.overlay.START"
        const val ACTION_STOP = "dev.clashaiaa.overlay.STOP"
        const val ACTION_REFRESH_SETTINGS = "dev.clashaiaa.overlay.REFRESH_SETTINGS"

        /** Ghost Drop opacity only: repaint the marker layer, keep everything else. */
        const val ACTION_GHOST_OPACITY = "dev.clashaiaa.overlay.GHOST_OPACITY"

        private const val CHANNEL_ID = "clashaiaa_overlay"
        private const val NOTIFICATION_ID = 26888

        /** Narrow enough to stay out of the way, wide enough for the status line. */
        private const val PANEL_WIDTH_DP = 390
        private const val DEFAULT_TOP_DP = 12

        private enum class PanelGesture { NONE, DRAG, RESIZE }

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }

        fun refreshSettings(context: Context) {
            if (!running) return
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH_SETTINGS)
            )
        }

        /** Live Ghost Drop opacity: no reconnect, no battle restart, no reset. */
        fun refreshGhostOpacity(context: Context) {
            if (!running) return
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_GHOST_OPACITY)
            )
        }
    }
}

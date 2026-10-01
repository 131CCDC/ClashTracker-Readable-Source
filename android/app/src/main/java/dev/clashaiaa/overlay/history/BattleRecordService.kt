package dev.clashaiaa.overlay.history

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.clashaiaa.overlay.GhostClient
import dev.clashaiaa.overlay.GhostProbeEvent
import dev.clashaiaa.overlay.HistoryActivity
import dev.clashaiaa.overlay.IdentityResolver
import dev.clashaiaa.overlay.LocalIdentity
import dev.clashaiaa.overlay.ProbeClient
import dev.clashaiaa.overlay.ProbeEvent
import dev.clashaiaa.overlay.BattleState
import dev.clashaiaa.overlay.R
import dev.clashaiaa.overlay.SettingsStore
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists a crash-safe live row and finalizes directly from the validated
 * game-owned native result. Battle Log payloads are optional enrichment.
 *
 * This service is the module's whole runtime. It is deliberately separate from
 * [dev.clashaiaa.overlay.OverlayService]: the HUD can be off, on, restarted or
 * reconfigured without the recorder losing its place, and the recorder's own
 * poll loop can never make the HUD's frame path slower. It reads the same
 * probe endpoint. Database writes happen only in the idle reconciler.
 *
 * Lifecycle per battle:
 *
 * ```
 * BattleStart       -> persist INCOMPLETE
 * NativeFinalized   -> persist COMPLETE once
 * BattleEnd         -> diagnostic only
 * Stable idle       -> optional Battle Log enrichment of the same uid
 * ```
 */
class BattleRecordService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }
    private val names by lazy { CardNames(this) }
    private val recorder by lazy { BattleRecorder(names) }
    private val cardPlayRecorder = CardPlayRecorder()

    private val identityResolver = IdentityResolver(
        notifyLearned = { accountId -> SettingsStore.saveLearnedAccountId(this, accountId) },
        notifyLearnedDeck = { signature -> SettingsStore.saveLearnedDeck(this, signature) },
    )

    private var client: ProbeClient? = null
    private var ghostClient: GhostClient? = null
    private var lastStatus = "等待探针"
    private val reconciling = AtomicBoolean(false)
    private var lastReconcileFingerprint = ""
    private var latestProbeState: BattleState? = null
    private var currentIdentity: LocalIdentity = LocalIdentity.UNKNOWN

    /** Fires while no frame is arriving, so a dead link still closes the battle. */
    private val idleTicker = object : Runnable {
        override fun run() {
            for (event in recorder.onIdle()) observe(event)
            handler.postDelayed(this, IDLE_TICK_MS)
        }
    }

    private val historyTicker = object : Runnable {
        override fun run() {
            reconcileWhenIdle()
            handler.postDelayed(this, HISTORY_TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIFICATION_ID, buildNotification())
        val settings = SettingsStore.load(this)
        identityResolver.configuredAccountId = settings.localAccountId
        identityResolver.configuredOwner = settings.localOwner
        identityResolver.seedLearned(SettingsStore.loadLearnedAccountId(this))
        identityResolver.seedLearnedDeck(SettingsStore.loadLearnedDeck(this))
        startClients(settings.host, settings.port)
        handler.postDelayed(idleTicker, IDLE_TICK_MS)
        handler.postDelayed(historyTicker, INITIAL_RECONCILE_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_SETTINGS -> {
                val settings = SettingsStore.load(this)
                val endpointChanged = client == null ||
                    settings.host != currentHost || settings.port != currentPort
                identityResolver.configuredAccountId = settings.localAccountId
                identityResolver.configuredOwner = settings.localOwner
                if (endpointChanged) {
                    stopClients()
                    startClients(settings.host, settings.port)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopClients()
        super.onDestroy()
    }

    private var currentHost = ""
    private var currentPort = 0

    private fun startClients(host: String, port: Int) {
        currentHost = host
        currentPort = port
        val probe = ProbeClient(
            host = host,
            port = port,
            executor = mainExecutor,
            listener = ::onProbeEvent,
        )
        client = probe
        probe.start()
        val ghost = GhostClient(host, port, mainExecutor, ::onGhostEvent)
        ghostClient = ghost
        ghost.start()
    }

    private fun stopClients() {
        client?.stop()
        client = null
        ghostClient?.stop()
        ghostClient = null
    }

    private fun onProbeEvent(event: ProbeEvent) {
        when (event) {
            is ProbeEvent.Connected -> {
                lastStatus = "已连接探针"
                updateNotification()
            }
            is ProbeEvent.Disconnected -> {
                lastStatus = "探针断开: ${event.reason}"
                updateNotification()
                for (finished in recorder.onIdle()) observe(finished)
            }
            is ProbeEvent.Snapshot -> {
                latestProbeState = event.state
                runCatching { identityResolver.onFrame(event.state) }
                val resolved = identityResolver.identity(event.state)
                currentIdentity = resolved
                val frame = BattleFrame.parse(event.raw) ?: return
                if (frame.inBattle && !frame.stale) {
                    lastStatus = "对战中 · tick ${frame.tick}"
                    updateNotification()
                } else if (lastStatus.startsWith("对战中")) {
                    lastStatus = "等待下一场"
                    updateNotification()
                }
                val events = recorder.onFrame(
                    frame,
                    resolved.owner,
                    resolved.source.name,
                    resolved.verified,
                )
                for (recorded in events) observe(recorded)
            }
        }
    }

    private fun onGhostEvent(event: GhostProbeEvent) {
        if (event is GhostProbeEvent.Snapshot) {
            runCatching { identityResolver.onGhost(event.feed) }
            currentIdentity = identityResolver.identity(latestProbeState)
            val records = cardPlayRecorder.observe(
                feed = event.feed,
                activeBattleUid = recorder.activeBattleUid,
                activeAccountIds = recorder.activeAccountIds,
                identity = currentIdentity,
                receivedAtMs = event.receivedAtMs,
            )
            BattleHistory.saveCardPlays(this, records)
        }
    }

    private fun observe(event: BattleRecorder.Event) {
        BattleWorkingSessions.observe(event)
        when (event) {
            is BattleRecorder.Event.Started -> {
                Log.i(TAG, "[Battle] working session created uid=${event.record.battleUid}")
                BattleHistory.save(this, event.record)
                BattleHistory.saveCardPlays(
                    this,
                    cardPlayRecorder.attachPending(
                        event.record.battleUid,
                        recorder.activeAccountIds,
                        currentIdentity,
                    ),
                )
                lastStatus = "对战会话已建立（等待结算）"
                updateNotification()
            }
            is BattleRecorder.Event.Finalized -> {
                Log.i(
                    TAG,
                    "[Battle] native result finalized uid=${event.record.battleUid} " +
                        "winner=${event.record.winnerOwner} result=${event.record.result.wire}",
                )
                BattleHistory.save(this, event.record)
                lastStatus = "原生结算已保存"
                updateNotification()
            }
            is BattleRecorder.Event.Finished -> {
                Log.i(TAG, "[Battle] battle scene exited uid=${event.record.battleUid}")
                // Finalized rows were already saved authoritatively. Otherwise
                // update the crash-safe row with duration and any raw evidence.
                if (event.record.winnerOwner == null) BattleHistory.save(this, event.record)
                lastStatus = "已离开对战"
                updateNotification()
                handler.postDelayed({ reconcileWhenIdle() }, POST_BATTLE_RECONCILE_DELAY_MS)
            }
        }
    }

    private fun reconcileWhenIdle() {
        if (recorder.active || !reconciling.compareAndSet(false, true)) return
        val pending = BattleWorkingSessions.snapshot()
        val fingerprint = BattleHistorySync.fingerprint(this, pending)
        if (fingerprint == lastReconcileFingerprint) {
            reconciling.set(false)
            return
        }
        lastReconcileFingerprint = fingerprint
        Thread {
            val result = runCatching { BattleHistorySync.syncBlocking(this, pending) }
            result.onSuccess { report ->
                for (uid in report.consumedLiveUids) BattleWorkingSessions.consume(uid)
                if (report.imported + report.updated > 0) BattleHistory.notifyChanged()
                Log.i(TAG, "[History] ${report.summary.replace('\n', ' ')}")
                handler.post {
                    if (report.imported > 0) {
                        lastStatus = "已保存 ${report.imported} 条权威战绩"
                        updateNotification()
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "[History] reconciliation failed", error)
            }
            reconciling.set(false)
        }.apply { name = "battle-history-reconcile"; isDaemon = true }.start()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "对战记录", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        val intent = Intent(this, HistoryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("ClashTracker 对战记录")
            .setContentText(lastStatus)
            .setSmallIcon(R.drawable.ic_probe)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        private const val CHANNEL_ID = "clashaiaa_battle_record"
        private const val NOTIFICATION_ID = 0x5A17
        private const val IDLE_TICK_MS = 1_000L
        private const val HISTORY_TICK_MS = 30_000L
        private const val INITIAL_RECONCILE_DELAY_MS = 3_000L
        private const val POST_BATTLE_RECONCILE_DELAY_MS = 2_000L
        private const val TAG = "ClashTrackerBattle"

        const val ACTION_STOP = "dev.clashaiaa.overlay.action.BATTLE_RECORD_STOP"
        const val ACTION_REFRESH_SETTINGS = "dev.clashaiaa.overlay.action.BATTLE_RECORD_REFRESH"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BattleRecordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BattleRecordService::class.java).setAction(ACTION_STOP),
            )
        }

        fun refresh(context: Context) {
            if (!running) return
            context.startService(
                Intent(context, BattleRecordService::class.java).setAction(ACTION_REFRESH_SETTINGS),
            )
        }
    }
}

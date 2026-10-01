package dev.clashaiaa.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.clashaiaa.overlay.history.BattleRecordService
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Control surface: overlay permission, start/stop, seat selection and a live
 * preview of the same data the panel renders so the probe link can be verified
 * without the overlay.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }
    private val pingExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "probe-ping").apply { isDaemon = true }
    }

    private var previewClient: ProbeClient? = null
    private var previewGhost: GhostClient? = null
    private var previewConnected = false
    private var previewState: BattleState? = null
    private var settings: OverlaySettings = OverlaySettings.DEFAULT

    /**
     * The preview resolves the local identity from the same evidence the
     * overlay uses, so "which player is shown here" cannot differ between the
     * two surfaces.
     */
    private val identityResolver = IdentityResolver(
        notifyLearned = { accountId -> SettingsStore.saveLearnedAccountId(this, accountId) },
        notifyLearnedDeck = { signature -> SettingsStore.saveLearnedDeck(this, signature) },
    )

    private lateinit var endpointText: TextView
    private lateinit var previewStatus: TextView
    private lateinit var previewElixir: TextView
    private lateinit var previewHand: TextView
    private lateinit var seatOwner0: RadioButton
    private lateinit var seatOwner1: RadioButton
    private lateinit var accountIdInput: EditText
    private lateinit var ghostEnabledInput: CheckBox
    private lateinit var ghostCardOpacityInput: SeekBar
    private lateinit var ghostTileOpacityInput: SeekBar
    private lateinit var ghostCardOpacityValue: TextView
    private lateinit var ghostTileOpacityValue: TextView
    private lateinit var pingResult: TextView
    private lateinit var candidateButtons: List<Button>
    private lateinit var candidateHint: TextView

    /** True while the inputs are being filled from the settings, not by the user. */
    private var bindingInputs = false

    /** The recorder toggle, so its label can follow the service without a rebuild. */
    private var recordButton: Button? = null

    /**
     * The sliders write the preference on every move but tell the overlay at most
     * once per [OPACITY_PUSH_MS]: a drag is dozens of events, and the layer only
     * has to be repainted at the speed a finger can see.
     */
    private val pushGhostOpacity = Runnable {
        SettingsStore.save(this, settings)
        OverlayService.refreshGhostOpacity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = SettingsStore.load(this)

        endpointText = findViewById(R.id.endpointText)
        previewStatus = findViewById(R.id.previewStatus)
        previewElixir = findViewById(R.id.previewElixir)
        previewHand = findViewById(R.id.previewHand)
        seatOwner0 = findViewById(R.id.seatOwner0)
        seatOwner1 = findViewById(R.id.seatOwner1)
        accountIdInput = findViewById(R.id.accountIdInput)
        ghostEnabledInput = findViewById(R.id.ghostEnabledInput)
        ghostCardOpacityInput = findViewById(R.id.ghostCardOpacityInput)
        ghostTileOpacityInput = findViewById(R.id.ghostTileOpacityInput)
        ghostCardOpacityValue = findViewById(R.id.ghostCardOpacityValue)
        ghostTileOpacityValue = findViewById(R.id.ghostTileOpacityValue)
        pingResult = findViewById(R.id.pingResult)
        candidateButtons = listOf(findViewById(R.id.candidateButton0), findViewById(R.id.candidateButton1))
        candidateHint = findViewById(R.id.candidateHint)

        bindSettingsInputs()
        bindGhostOpacityInputs()
        applyIdentitySettings()
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.permissionButton).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.startButton).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopOverlay() }
        findViewById<Button>(R.id.pingButton).setOnClickListener { testProbe() }
        addBattleHistoryControls()

        requestNotificationPermissionIfNeeded()
    }

    /**
     * The two entry points of the battle-history module, added to the existing
     * settings column rather than to a new layout: the control surface stays
     * one screen, and nothing the HUD already reads is moved or renamed.
     */
    private fun addBattleHistoryControls() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as? ViewGroup
            ?: return
        val column = scroll.getChildAt(0) as? LinearLayout ?: return

        column.addView(
            TextView(this).apply {
                text = getString(R.string.history_title)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(4))
            },
        )
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    Button(this@MainActivity).apply {
                        text = getString(R.string.history_open)
                        isAllCaps = false
                        setOnClickListener {
                            startActivity(HistoryActivity.intent(this@MainActivity))
                        }
                    },
                )
                addView(
                    Button(this@MainActivity).apply {
                        recordButton = this
                        text = recordButtonLabel()
                        isAllCaps = false
                        setOnClickListener {
                            if (BattleRecordService.running) {
                                BattleRecordService.stop(this@MainActivity)
                            } else {
                                BattleRecordService.start(this@MainActivity)
                            }
                            // The service flips its own flag in onCreate/onDestroy;
                            // re-read it on the next frame instead of guessing.
                            handler.postDelayed({ recordButton?.text = recordButtonLabel() }, 500)
                        }
                    },
                )
            },
        )
    }

    private fun recordButtonLabel(): String =
        if (BattleRecordService.running) {
            getString(R.string.history_record_stop)
        } else {
            getString(R.string.history_record_start)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        settings = SettingsStore.load(this)
        bindSettingsInputs()
        applyIdentitySettings()
        endpointText.text = getString(R.string.endpoint_format, settings.host, settings.port)
        if (OverlayService.running) {
            stopPreview()
            previewStatus.text = getString(R.string.overlay_running)
            previewElixir.text = ""
            previewHand.text = ""
        } else {
            startPreview()
        }
    }

    override fun onPause() {
        stopPreview()
        super.onPause()
    }

    override fun onDestroy() {
        pingExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindSettingsInputs() {
        bindingInputs = true
        if (settings.localOwner == 1) seatOwner1.isChecked = true else seatOwner0.isChecked = true
        accountIdInput.setText(if (settings.localAccountId == 0L) "" else settings.localAccountId.toString())
        ghostEnabledInput.isChecked = settings.ghostEnabled
        bindingInputs = false
    }

    /**
     * The two Ghost Drop sliders, bound to the same numbers the renderer reads.
     * A move updates the label at once, folds the value into the settings, and
     * hands the overlay a repaint without a reconnect or a battle restart; the
     * value is written to the preferences too, so it survives a restart of the
     * app or the tablet.
     */
    private fun bindGhostOpacityInputs() {
        bindingInputs = true
        ghostCardOpacityInput.progress = percentOf(settings.ghostCardOpacity)
        ghostTileOpacityInput.progress = percentOf(settings.ghostTileOpacity)
        bindingInputs = false
        ghostCardOpacityValue.text = getString(R.string.ghost_opacity_format, ghostCardOpacityInput.progress)
        ghostTileOpacityValue.text = getString(R.string.ghost_opacity_format, ghostTileOpacityInput.progress)

        ghostCardOpacityInput.setOnSeekBarChangeListener(
            opacityListener { percent ->
                settings = settings.copy(ghostCardOpacity = percent / 100f)
                ghostCardOpacityValue.text = getString(R.string.ghost_opacity_format, percent)
            }
        )
        ghostTileOpacityInput.setOnSeekBarChangeListener(
            opacityListener { percent ->
                settings = settings.copy(ghostTileOpacity = percent / 100f)
                ghostTileOpacityValue.text = getString(R.string.ghost_opacity_format, percent)
            }
        )
    }

    private fun opacityListener(apply: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (bindingInputs) return
            apply(progress)
            handler.removeCallbacks(pushGhostOpacity)
            handler.postDelayed(pushGhostOpacity, OPACITY_PUSH_MS)
        }

        override fun onStartTrackingTouch(bar: SeekBar?) = Unit

        override fun onStopTrackingTouch(bar: SeekBar?) {
            // The last value of a drag must not wait for the coalescing window.
            handler.removeCallbacks(pushGhostOpacity)
            handler.post(pushGhostOpacity)
        }
    }

    private fun percentOf(opacity: Float): Int = (opacity * 100f).toInt().coerceIn(0, 100)

    private fun applyIdentitySettings() {
        identityResolver.configuredAccountId = settings.localAccountId
        identityResolver.configuredOwner = settings.localOwner
        identityResolver.seedLearned(SettingsStore.loadLearnedAccountId(this))
        identityResolver.seedLearnedDeck(SettingsStore.loadLearnedDeck(this))
    }

    /**
     * The two account ids of the live battle, as one-tap candidates: the fastest
     * way to pin "which player is me" without knowing the id by heart. The panel
     * footer names the account it is reading, so the choice is verifiable.
     */
    private fun updateIdentityCandidates() {
        val players = previewState?.players.orEmpty()
        candidateButtons.forEachIndexed { index, button ->
            val player = players.getOrNull(index)
            if (player == null || player.accountId == 0L) {
                button.text = getString(R.string.candidate_empty)
                button.isEnabled = false
            } else {
                button.isEnabled = true
                button.text = getString(R.string.candidate_format, player.accountId, player.owner)
                button.setOnClickListener { pinAccountId(player.accountId) }
            }
        }
    }

    private fun pinAccountId(accountId: Long) {
        accountIdInput.setText(accountId.toString())
        saveSettings()
        Toast.makeText(this, getString(R.string.candidate_pinned, accountId), Toast.LENGTH_SHORT).show()
    }

    private fun saveSettings() {
        val accountId = accountIdInput.text.toString().trim().toLongOrNull() ?: 0L
        settings = settings.copy(
            localOwner = if (seatOwner1.isChecked) 1 else 0,
            localAccountId = if (accountId < 0) 0L else accountId,
            ghostEnabled = ghostEnabledInput.isChecked,
        )
        SettingsStore.save(this, settings)
        applyIdentitySettings()
        identityResolver.reset()
        OverlayService.refreshSettings(this)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        renderPreview()
    }

    private fun startPreview() {
        if (previewClient != null) return
        previewConnected = false
        previewState = null
        val client = ProbeClient(
            host = settings.host,
            port = settings.port,
            executor = mainExecutor,
            listener = { event -> onPreviewEvent(event) },
        )
        previewClient = client
        client.start()
        if (settings.ghostEnabled) {
            val ghost = GhostClient(
                host = settings.host,
                port = settings.port,
                executor = mainExecutor,
                listener = { event ->
                    if (event is GhostProbeEvent.Snapshot) {
                        identityResolver.onGhost(event.feed)
                        renderPreview()
                    }
                },
            )
            previewGhost = ghost
            ghost.start()
        }
    }

    private fun stopPreview() {
        previewClient?.stop()
        previewClient = null
        previewGhost?.stop()
        previewGhost = null
        previewConnected = false
        previewState = null
    }

    private fun onPreviewEvent(event: ProbeEvent) {
        when (event) {
            is ProbeEvent.Connected -> previewConnected = true
            is ProbeEvent.Snapshot -> {
                previewConnected = true
                previewState = event.state
                identityResolver.onFrame(event.state)
            }
            is ProbeEvent.Disconnected -> {
                previewConnected = false
                previewState = null
            }
        }
        renderPreview()
    }

    private fun renderPreview() {
        val identity = identityResolver.identity(previewState)
        val model = OverlayRenderer.model(
            previewConnected,
            previewState,
            identity,
        )
        previewStatus.text = if (model.notice != null) {
            "${model.status} · ${model.notice}"
        } else {
            model.status
        }
        previewElixir.text = when {
            model.waiting -> getString(R.string.waiting_for_battle)
            else -> getString(R.string.preview_elixir_format, model.elixir)
        }
        previewHand.text = getString(R.string.preview_hand_format, model.cards.joinToString("  "))
        candidateHint.text = getString(R.string.candidate_hint, identity.label)
        updateIdentityCandidates()
    }

    private fun testProbe() {
        pingResult.text = ""
        val host = settings.host
        val port = settings.port
        pingExecutor.execute {
            val ok = ProbeClient(host = host, port = port, executor = mainExecutor, listener = {}).ping()
            handler.post {
                pingResult.text =
                    getString(if (ok) R.string.ping_ok else R.string.ping_failed)
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        stopPreview()
        OverlayService.start(this)
    }

    private fun stopOverlay() {
        OverlayService.stop(this)
        handler.postDelayed({ if (!OverlayService.running) startPreview() }, 400)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 26888

        /** Coalescing window for a slider drag; a tap lands well inside it. */
        const val OPACITY_PUSH_MS = 60L
    }
}

package dev.clashaiaa.overlay

import android.content.Context

data class OverlaySettings(
    val host: String,
    val port: Int,
    val localOwner: Int,
    val localAccountId: Long,
    val alpha: Int,
    val ghostEnabled: Boolean,
    /** Ghost Drop card-face opacity, `0.0 .. 1.0`. See [GhostOpacity]. */
    val ghostCardOpacity: Float,
    /** Ghost Drop placement-square opacity, `0.0 .. 1.0`. */
    val ghostTileOpacity: Float,
) {
    /** The marker alphas, clamped once for every caller. */
    val ghostOpacity: GhostOpacity
        get() = GhostOpacity.of(ghostCardOpacity, ghostTileOpacity)

    companion object {
        val DEFAULT = OverlaySettings(
            host = "127.0.0.1",
            port = ProbeJson.DEFAULT_PORT,
            localOwner = 0,
            localAccountId = 0L,
            alpha = 255,
            ghostEnabled = true,
            ghostCardOpacity = GHOST_CARD_OPACITY_DEFAULT,
            ghostTileOpacity = GHOST_TILE_OPACITY_DEFAULT,
        )
    }
}

/** SharedPreferences-backed settings; no database, no JSON config file. */
object SettingsStore {
    private const val FILE = "clashaiaa_overlay"
    private const val KEY_HOST = "probe_host"
    private const val KEY_PORT = "probe_port"
    private const val KEY_OWNER = "local_owner"
    private const val KEY_ACCOUNT = "local_account_id"
    private const val KEY_ALPHA = "overlay_alpha"
    private const val KEY_GHOST = "ghost_enabled"

    /**
     * Ghost Drop alphas. Stored as floats in the settings the app already owns,
     * so the slider in the settings screen and the renderer read the same
     * numbers; there is no second copy of the opacity anywhere.
     */
    private const val KEY_GHOST_CARD_OPACITY = "ghost_card_opacity"
    private const val KEY_GHOST_TILE_OPACITY = "ghost_tile_opacity"

    private const val KEY_PANEL_X = "panel_x"
    private const val KEY_PANEL_Y = "panel_y"
    private const val KEY_PANEL_SCALE = "panel_scale"

    /**
     * v2.3 key on purpose. Up to 2.2 the cached account could come from the
     * probe's `self_hint`, which the live audit caught naming the wrong client
     * (both clients recorded the same first issuer), so an account learned by
     * that build is not evidence and is ignored rather than trusted. Only an
     * account confirmed by this device's own local-input evidence is stored
     * here, and the next card play re-learns it.
     */
    private const val KEY_LEARNED_ACCOUNT = "learned_local_account_id_v2"

    /**
     * The local deck, as sorted card ids, remembered next to the account so a
     * battle can be identified from its first frame even if the account is not
     * the one that was cached (another account on the same tablet).
     */
    private const val KEY_LEARNED_DECK = "learned_local_deck_v1"

    fun load(context: Context): OverlaySettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return OverlaySettings(
            host = prefs.getString(KEY_HOST, OverlaySettings.DEFAULT.host) ?: OverlaySettings.DEFAULT.host,
            port = prefs.getInt(KEY_PORT, OverlaySettings.DEFAULT.port),
            localOwner = prefs.getInt(KEY_OWNER, OverlaySettings.DEFAULT.localOwner),
            localAccountId = prefs.getLong(KEY_ACCOUNT, OverlaySettings.DEFAULT.localAccountId),
            alpha = prefs.getInt(KEY_ALPHA, OverlaySettings.DEFAULT.alpha),
            ghostEnabled = prefs.getBoolean(KEY_GHOST, OverlaySettings.DEFAULT.ghostEnabled),
            // A preference written by an older build (or a half-written one) is
            // clamped on read, so the renderer never sees an out-of-range alpha.
            ghostCardOpacity = normalizeGhostOpacity(
                prefs.getFloat(KEY_GHOST_CARD_OPACITY, GHOST_CARD_OPACITY_DEFAULT),
                GHOST_CARD_OPACITY_DEFAULT,
            ),
            ghostTileOpacity = normalizeGhostOpacity(
                prefs.getFloat(KEY_GHOST_TILE_OPACITY, GHOST_TILE_OPACITY_DEFAULT),
                GHOST_TILE_OPACITY_DEFAULT,
            ),
        )
    }

    fun save(context: Context, settings: OverlaySettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, settings.host)
            .putInt(KEY_PORT, settings.port)
            .putInt(KEY_OWNER, settings.localOwner)
            .putLong(KEY_ACCOUNT, settings.localAccountId)
            .putInt(KEY_ALPHA, settings.alpha)
            .putBoolean(KEY_GHOST, settings.ghostEnabled)
            .putFloat(KEY_GHOST_CARD_OPACITY, settings.ghostOpacity.card)
            .putFloat(KEY_GHOST_TILE_OPACITY, settings.ghostOpacity.tile)
            .apply()
    }

    fun loadPanelPlacement(context: Context): PanelPlacement? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PANEL_SCALE)) return null
        return PanelPlacement(
            x = prefs.getInt(KEY_PANEL_X, 0),
            y = prefs.getInt(KEY_PANEL_Y, 0),
            scale = PanelGeometry.clampScale(prefs.getFloat(KEY_PANEL_SCALE, PanelGeometry.DEFAULT_SCALE)),
        )
    }

    fun savePanelPlacement(context: Context, placement: PanelPlacement) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PANEL_X, placement.x)
            .putInt(KEY_PANEL_Y, placement.y)
            .putFloat(KEY_PANEL_SCALE, PanelGeometry.clampScale(placement.scale))
            .apply()
    }

    fun loadLearnedAccountId(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_LEARNED_ACCOUNT, 0L)
            .coerceAtLeast(0L)

    fun saveLearnedAccountId(context: Context, accountId: Long) {
        if (accountId <= 0L) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LEARNED_ACCOUNT, accountId)
            .apply()
    }

    fun loadLearnedDeck(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LEARNED_DECK, "")
            ?.takeIf { it.isNotBlank() }
            ?: ""

    fun saveLearnedDeck(context: Context, signature: String) {
        if (signature.isBlank()) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_LEARNED_DECK, signature)
            .apply()
    }
}

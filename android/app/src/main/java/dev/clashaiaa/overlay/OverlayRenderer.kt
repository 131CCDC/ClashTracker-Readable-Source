package dev.clashaiaa.overlay

import java.util.Locale

/** Exactly what the floating panel shows for one moment in time. */
data class OverlayModel(
    val status: String,
    val waiting: Boolean,
    val elixir: String,
    /**
     * The raw probe elixir behind [elixir], for the affordability sweep. The
     * formatted string carries one decimal, so elixir recovering from 4.98 to
     * 5.01 looks like no change at all in it - the gauge must run on the raw
     * value, exactly like the Windows HUD's `apply`.
     */
    val elixirValue: Float? = null,
    val cards: List<String>,
    val cardIds: List<Int>,
    /** Costs attested by the probe's own `card_runtime`; null falls back to art data. */
    val cardCosts: List<Int?>,
    /** Native `EVO` / `READY` / `★` marks, aligned with [cardIds]. */
    val cardBadges: List<CardBadge?>,
    val cycleIds: List<Int>,
    /** Marks for the native cycle row; its first tile is `NEXT`. */
    val cycleBadges: List<CardBadge?>,
    val recentIds: List<Int>,
    val showBattleData: Boolean,
    /** Drawn in place of the card area while there is nothing live to show. */
    val notice: String? = null,
    /** One short line naming the local identity the panel used. */
    val identityLabel: String = "",
    /** False when [identityLabel] is a seat fallback rather than proof. */
    val identityVerified: Boolean = true,
)

/** Pure formatting rules for the overlay, kept separate so they are testable. */
object OverlayRenderer {

    const val DISCONNECTED = "Probe: Disconnected"
    const val CONNECTED = "Probe: Connected"
    const val EMPTY_CARD = "--"
    const val EMPTY_ELIXIR = "--"
    const val SLOT_COUNT = 4
    const val WAITING_NOTICE = "等待对局数据"

    fun disconnected(): OverlayModel = OverlayModel(
        status = DISCONNECTED,
        waiting = false,
        elixir = EMPTY_ELIXIR,
        cards = List(SLOT_COUNT) { EMPTY_CARD },
        cardIds = List(SLOT_COUNT) { 0 },
        cardCosts = List(SLOT_COUNT) { null },
        cardBadges = List(SLOT_COUNT) { null },
        cycleIds = emptyList(),
        cycleBadges = emptyList(),
        recentIds = emptyList(),
        showBattleData = false,
    )

    /**
     * The Windows HUD's rules: opponent elixir to one decimal, the opponent's
     * hand, the native cycle and the recent row, all from one frame. The panel
     * renders from the first frame of a battle: a seat fallback is drawn and
     * labelled rather than withheld, and the identity the frame was read from is
     * always named in the footer.
     */
    fun model(
        connected: Boolean,
        state: BattleState?,
        identity: LocalIdentity,
        recentIds: List<Int> = emptyList(),
    ): OverlayModel {
        if (!connected) {
            return disconnected()
        }
        if (state == null || !state.inBattle || state.stale || !identity.usable) {
            return OverlayModel(
                status = CONNECTED,
                waiting = true,
                elixir = EMPTY_ELIXIR,
                cards = List(SLOT_COUNT) { EMPTY_CARD },
                cardIds = List(SLOT_COUNT) { 0 },
                cardCosts = List(SLOT_COUNT) { null },
                cardBadges = List(SLOT_COUNT) { null },
                cycleIds = emptyList(),
                cycleBadges = emptyList(),
                recentIds = emptyList(),
                showBattleData = false,
                notice = WAITING_NOTICE,
                identityLabel = identity.label,
                identityVerified = identity.verified,
            )
        }
        val opponent = state.opponent(identity.owner, identity.accountId)
        val hero = opponent?.hero
        return OverlayModel(
            status = CONNECTED,
            waiting = false,
            elixir = formatElixir(opponent),
            elixirValue = opponent?.elixir,
            cards = List(SLOT_COUNT) { cardLabel(opponent?.hand?.getOrNull(it)) },
            cardIds = List(SLOT_COUNT) { opponent?.hand?.getOrNull(it)?.cardId ?: 0 },
            cardCosts = List(SLOT_COUNT) { opponent?.hand?.getOrNull(it)?.cost },
            cardBadges = List(SLOT_COUNT) {
                CardBadges.of(opponent?.hand?.getOrNull(it)?.role, hero)
            },
            cycleIds = opponent?.cycle ?: emptyList(),
            cycleBadges = (opponent?.cycle ?: emptyList()).map {
                CardBadges.of(opponent?.roleFor(it), hero)
            },
            recentIds = recentIds,
            showBattleData = true,
            identityLabel = identity.label,
            identityVerified = identity.verified,
        )
    }

    /** One decimal, exactly like the Windows HUD's elixir pill. */
    fun formatElixir(opponent: ProbePlayer?): String =
        opponent?.let { String.format(Locale.US, "%.1f", it.elixir) } ?: EMPTY_ELIXIR

    /**
     * Card names come from the game's own asset string through the probe. An
     * unknown id is shown as `Unknown(<id>)`; an empty slot stays `--`.
     */
    fun cardLabel(card: HandCard?): String = when {
        card == null || card.cardId == 0 -> EMPTY_CARD
        !card.name.isNullOrBlank() -> card.name
        else -> "Unknown(${card.cardId})"
    }
}

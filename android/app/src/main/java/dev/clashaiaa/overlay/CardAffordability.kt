package dev.clashaiaa.overlay

/**
 * Enemy hand affordability: can the opponent pay for this card right now?
 *
 * The Android port of `tracker/card_affordability.py`, with the same three
 * concerns kept apart - mixing them is how a HUD ends up claiming a card is
 * castable when it is not:
 *
 *  - **data** - [calculateAffordability] turns the *raw* probe elixir and one
 *    card's effective cost into `progress` / `ready`. Nothing is rounded: the
 *    panel prints one decimal, and 4.98 must stay short of a 5-cost card.
 *  - **animation** - [AffordabilityAnimator] owns `visualProgress`. Elixir
 *    coming back is interpolated over [RISE_SECONDS] so the sweep glides between
 *    probe frames; elixir being *spent*, and a card *entering* the hand, snap to
 *    the truth on the spot, because the visual must never lag behind "he just
 *    played that".
 *  - **render** - `TrackerHudView` paints the grey face and the dark unpaid
 *    sector from [missingFraction] and recomputes nothing.
 *
 * Nothing here is predicted and the probe is not changed: the effective cost is
 * the probe's own `selected_cost` when it attests one, otherwise the bundled
 * `cards.json` entry - exactly the priority the Windows HUD uses. A cost the
 * data cannot supply is reported as *unknown* rather than guessed, and an
 * unknown or zero cost is never divided by.
 *
 * This file is deliberately free of Android imports so every rule below is
 * covered by plain JVM unit tests.
 */

/** Radial sweep resolution: 120 steps = 3 degrees, the same as the Windows HUD. */
const val SWEEP_STEPS = 120

/**
 * How long the wedge takes to travel from where it is to a *new* target when
 * elixir RISES, in seconds. Inside the 80-150 ms band the HUD was specified
 * with. A fall never uses it.
 */
const val RISE_SECONDS = 0.12f

/** Highest cost the game can express; anything above it is a broken row. */
const val MAX_COST = 15f

/**
 * The grey "cannot pay for this yet" layer is not an opacity drop: a card the
 * opponent cannot afford still has to stay identifiable. Colour is removed and
 * the image is only slightly darkened and flattened, so the face, the frame and
 * the cost badge stay legible.
 *
 * `out = (luma * BRIGHTNESS - 0.5) * CONTRAST + 0.5`
 *     `= luma * (BRIGHTNESS * CONTRAST) + 0.5 * (1 - CONTRAST)`
 */
const val GREY_BRIGHTNESS = 0.80f
const val GREY_CONTRAST = 0.92f

/** Rec. 709-ish luma weights, the same triplet `ColorMatrix.setSaturation(0)` uses. */
const val LUMA_R = 0.213f
const val LUMA_G = 0.715f
const val LUMA_B = 0.072f

/**
 * The second, darker layer of the "cannot pay for this yet" look.
 *
 * A grey card on its own says "not now" but not *how far away* it is, so the
 * unpaid share of the cost is covered by a darker clock sector on top of the
 * grey face: two 2-cost cards look identical, a 4-cost card keeps half of its
 * face dark while a 7-cost card keeps seven tenths of it. Both layers are the
 * same tile - the sector is clipped to the card face and the badges, the cost
 * and the name are painted after it, so no mark is greyed by accident.
 *
 * [AFFORD_MASK_ALPHA] is high enough to read as "still missing" at a glance and
 * short of opaque so the art underneath stays identifiable.
 */
const val AFFORD_MASK_ALPHA = 0x9E

/** The panel's own ink (`#0b0e14`) as the sector's colour, without its alpha. */
const val AFFORD_MASK_RGB = 0x0B0E14

/** ARGB ink of the unpaid sector: [AFFORD_MASK_RGB] at [AFFORD_MASK_ALPHA]. */
val AFFORD_MASK_INK: Int = (AFFORD_MASK_ALPHA shl 24) or AFFORD_MASK_RGB

/**
 * 0 degrees is 3 o'clock and a positive sweep runs clockwise, so -90 starts the
 * sweep at 12 o'clock - the game's own hand gauge, and the Windows HUD's.
 */
const val SWEEP_START_DEGREES = -90f
const val SWEEP_FULL_DEGREES = 360f

/** Sweep step for one visual progress, floored so < 1.0 never looks ready. */
fun sweepBucket(progress: Float): Int {
    if (progress.isNaN()) return 0
    return (progress * SWEEP_STEPS).toInt().coerceIn(0, SWEEP_STEPS)
}

/**
 * The share of the cost that is still unpaid: `1 - progress`, clamped, with a
 * NaN read as "nothing paid" rather than as a reason to paint nothing.
 */
fun missingFraction(progress: Float): Float {
    if (progress.isNaN()) return 1f
    return (1f - progress).coerceIn(0f, 1f)
}

/** Where the dark sector begins: the leading edge of the paid-for sweep. */
fun maskStartDegrees(progress: Float): Float =
    SWEEP_START_DEGREES + SWEEP_FULL_DEGREES * (1f - missingFraction(progress))

/** How wide the dark sector is; 0 once the card is affordable. */
fun maskSweepDegrees(progress: Float): Float = SWEEP_FULL_DEGREES * missingFraction(progress)

/** The truth for one card at one moment: pure data, no animation. */
data class Affordability(
    /**
     * False when the cost is unknown - the panel then keeps the plain art
     * instead of painting a claim it cannot back.
     */
    val known: Boolean,
    val cost: Float?,
    val progress: Float,
    val ready: Boolean,
) {
    val bucket: Int get() = sweepBucket(progress)
}

/**
 * `progress` / `ready` for one card, straight from the raw probe values.
 *
 * [elixir] is the opponent's live elixir as published (never re-derived and
 * never the rounded panel text); [cost] is the effective cost of that card.
 *
 *  - a cost of 0 (or less) is always castable;
 *  - an unknown cost, a NaN, or elixir that is not live is *not* a claim: it
 *    yields `known = false` and full colour, so the tile keeps its normal art;
 *  - nothing here throws, divides by zero, or produces NaN.
 */
fun calculateAffordability(elixir: Float?, cost: Float?): Affordability {
    if (cost == null || cost.isNaN() || cost < 0f || cost > MAX_COST) {
        return Affordability(known = false, cost = null, progress = 1f, ready = false)
    }
    if (elixir == null || elixir.isNaN()) {
        return Affordability(known = false, cost = cost, progress = 1f, ready = false)
    }
    if (cost <= 0f) {
        return Affordability(known = true, cost = cost, progress = 1f, ready = true)
    }
    // Double for the comparison and the ratio: the raw value is what decides
    // READY, and rounding the elixir first is what makes a HUD say "ready" a
    // frame early.
    val value = elixir.toDouble()
    val fee = cost.toDouble()
    if (value.isNaN()) {
        return Affordability(known = false, cost = cost, progress = 1f, ready = false)
    }
    val progress = (value / fee).coerceIn(0.0, 1.0)
    return Affordability(
        known = true,
        cost = cost,
        progress = progress.toFloat(),
        ready = value >= fee,
    )
}

/**
 * What this card actually costs right now, or null when unknown.
 *
 * Priority: the probe's attested `card_runtime[].selected_cost` for the owning
 * deck slot, then the bundled table. A future mechanic that changes a cost in
 * play only has to keep filling `selected_cost` - the HUD needs no change.
 */
fun effectiveCost(attested: Int?, catalog: Int?): Float? {
    val live = attested?.toFloat()?.takeIf { it in 0f..MAX_COST }
    if (live != null) return live
    return catalog?.toFloat()?.takeIf { it in 0f..MAX_COST }
}

/** One hand slot: the data above plus the value actually being painted. */
data class CardAffordabilityState(
    val cardId: Int = 0,
    val cost: Float? = null,
    val enemyElixir: Float? = null,
    val targetProgress: Float = 1f,
    val visualProgress: Float = 1f,
    val ready: Boolean = false,
    val known: Boolean = false,
) {
    val bucket: Int get() = sweepBucket(visualProgress)

    /** True while the grey layer belongs on the tile at all. */
    val dimmed: Boolean get() = known && visualProgress < 1f
}

/**
 * Data -> visual interpolation for the fixed hand slots.
 *
 * Asymmetric on purpose:
 *
 *  - elixir RISES - the sweep closes the remaining gap over [riseSeconds], so
 *    one probe frame's worth of recovery glides instead of stepping;
 *  - elixir FALLS - the visual snaps immediately, so a card the opponent can no
 *    longer pay for is grey on the very frame that spent the elixir;
 *  - a card ENTERS the hand, or its cost changes (the slot's identity changed) -
 *    the visual snaps to the target, because that is a new card arriving with a
 *    real elixir pool behind it, not "charging up from zero".
 *
 * Interpolation never touches [CardAffordabilityState.ready]: that is the raw
 * elixir comparison, so it is already true while the wedge is still travelling.
 */
class AffordabilityAnimator(
    private val slots: Int = OverlayRenderer.SLOT_COUNT,
    private val riseSeconds: Float = RISE_SECONDS,
) {
    var states: List<CardAffordabilityState> = List(slots) { CardAffordabilityState() }
        private set

    private var lastNow: Double? = null

    /** True while any slot is still travelling, i.e. another frame is needed. */
    val animating: Boolean
        get() = states.any { it.known && it.visualProgress < it.targetProgress }

    /** Forgets everything: battle end, staleness and disconnect all land here. */
    fun reset(): List<CardAffordabilityState> {
        states = List(slots) { CardAffordabilityState() }
        lastNow = null
        return states
    }

    /**
     * Fraction of the remaining gap this update may close.
     *
     * `min(1, elapsed / riseSeconds)`: a gap still takes the whole window to
     * close, so the sweep glides, and a gap left alone for longer than the
     * window closes *completely* - which is what stops a sparse update (a frame
     * with no animation tick behind it, or a hidden panel) from freezing the
     * sweep a sliver short of its target for good.
     */
    private fun travel(nowSeconds: Double): Float {
        val previous = lastNow
        lastNow = nowSeconds
        if (riseSeconds <= 0f) return 1f
        val elapsed = if (previous == null) 0.0 else (nowSeconds - previous).coerceAtLeast(0.0)
        return (elapsed / riseSeconds).coerceIn(0.0, 1.0).toFloat()
    }

    /** Recompute every slot. [nowSeconds] is monotonic seconds, injected. */
    fun update(
        cardIds: List<Int>,
        costs: List<Float?>,
        elixir: Float?,
        nowSeconds: Double,
    ): List<CardAffordabilityState> {
        val travel = travel(nowSeconds)
        states = List(slots) { index ->
            val previous = states[index]
            val cardId = cardIds.getOrNull(index) ?: 0
            val cost = costs.getOrNull(index)
            val result = calculateAffordability(elixir, cost)
            val snap = cardId != previous.cardId ||
                previous.cost != result.cost ||
                !previous.known ||
                result.progress < previous.visualProgress
            val visual = when {
                // The truth lands on this frame: a new card, a changed cost, a
                // slot seen for the first time, or elixir that was just spent.
                snap -> result.progress
                result.progress > previous.visualProgress -> {
                    val step = if (travel >= 1f) {
                        result.progress
                    } else {
                        previous.visualProgress +
                            (result.progress - previous.visualProgress) * travel
                    }
                    // One step is the smallest thing the mask can show, and an
                    // asymptotic approach would leave a 3-degree hairline of grey
                    // on a READY card for seconds. Close the last step exactly.
                    if (result.progress - step < 1f / SWEEP_STEPS) result.progress else step
                }
                else -> previous.visualProgress
            }
            CardAffordabilityState(
                cardId = cardId,
                cost = result.cost,
                enemyElixir = elixir,
                targetProgress = result.progress,
                visualProgress = visual,
                ready = result.ready,
                known = result.known,
            )
        }
        return states
    }
}

/**
 * The 4x5 colour matrix (row-major, exactly what `android.graphics.ColorMatrix`
 * takes) that turns a card face into its grey layer: desaturated to luma, then
 * darkened and flattened by [GREY_BRIGHTNESS] / [GREY_CONTRAST].
 */
fun greyColorMatrix(): FloatArray {
    val scale = GREY_BRIGHTNESS * GREY_CONTRAST
    val offset = (0.5f - 0.5f * GREY_CONTRAST) * 255f
    return floatArrayOf(
        LUMA_R * scale, LUMA_G * scale, LUMA_B * scale, 0f, offset,
        LUMA_R * scale, LUMA_G * scale, LUMA_B * scale, 0f, offset,
        LUMA_R * scale, LUMA_G * scale, LUMA_B * scale, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )
}

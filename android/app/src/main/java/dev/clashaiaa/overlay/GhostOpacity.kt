package dev.clashaiaa.overlay

import kotlin.math.roundToInt

/**
 * Ghost Drop opacity: the two alphas the pending-enemy-placement marker is drawn
 * with, as settings the user owns instead of constants in the renderer.
 *
 * The card face and the placement square are separate dials on purpose. The face
 * is what has to be readable against a busy arena, and it is the one the slider
 * in the settings screen drives; the square is a thin outline that becomes
 * intrusive long before the art does, so it keeps its own, much lower default.
 * Raising the face must never drag the square along with it.
 *
 * Both values are normalised to `0.0 .. 1.0` - `0.0` is invisible, `1.0` is
 * fully opaque - and both are converted to the 0..255 byte the canvas wants in
 * exactly one place, so the settings screen, the preferences and the renderer
 * cannot drift into two different numbers.
 *
 * This file is deliberately free of Android imports so the rules below are
 * covered by plain JVM unit tests.
 */

/** What the renderer hard-coded before this was a setting: `166 / 255`. */
const val GHOST_CARD_OPACITY_DEFAULT = 0.65f

/** The placement square's own historical alpha, `56 / 255`. */
const val GHOST_TILE_OPACITY_DEFAULT = 0.22f

/**
 * One opacity, clamped into range. A NaN (a half-written preference, a slider
 * read at the wrong moment) falls back to [fallback] rather than to a value that
 * would make the marker vanish or cover the arena.
 */
fun normalizeGhostOpacity(value: Float, fallback: Float): Float =
    if (value.isNaN()) fallback else value.coerceIn(0f, 1f)

/** The 0..255 byte a `Paint` takes, from an already normalised opacity. */
fun ghostOpacityAlpha(opacity: Float): Int = (opacity * 255f).roundToInt().coerceIn(0, 255)

/** The pair the marker layer paints with. */
data class GhostOpacity(
    val card: Float,
    val tile: Float,
) {
    val cardAlpha: Int get() = ghostOpacityAlpha(card)
    val tileAlpha: Int get() = ghostOpacityAlpha(tile)

    /** True at `0%`: the marker is drawn, and is invisible. */
    val invisible: Boolean get() = cardAlpha == 0 && tileAlpha == 0

    companion object {
        /** The shipped look: an almost-fully-visible face over a faint square. */
        val DEFAULT = GhostOpacity(GHOST_CARD_OPACITY_DEFAULT, GHOST_TILE_OPACITY_DEFAULT)

        /** The only way to build one: both inputs are clamped, NaN included. */
        fun of(card: Float, tile: Float): GhostOpacity = GhostOpacity(
            card = normalizeGhostOpacity(card, GHOST_CARD_OPACITY_DEFAULT),
            tile = normalizeGhostOpacity(tile, GHOST_TILE_OPACITY_DEFAULT),
        )
    }
}

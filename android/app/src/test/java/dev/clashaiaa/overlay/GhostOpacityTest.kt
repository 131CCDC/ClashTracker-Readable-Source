package dev.clashaiaa.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ghost Drop opacity rules: the historical default, the full 0..100% range,
 * and the fact that a broken preference can never make the marker invisible or
 * cover the arena.
 *
 * `GhostOpacity` has no Android imports, so this is the same rule set the
 * renderer, the settings screen and the preferences all go through.
 */
class GhostOpacityTest {

    @Test
    fun `the default is the alpha the renderer used to hard-code`() {
        assertEquals(GHOST_CARD_OPACITY_DEFAULT, GhostOpacity.DEFAULT.card)
        assertEquals(GHOST_TILE_OPACITY_DEFAULT, GhostOpacity.DEFAULT.tile)
        // 0.65 * 255 = 165.75 -> 166, the byte the layer painted before.
        assertEquals(166, GhostOpacity.DEFAULT.cardAlpha)
        // 0.22 * 255 = 56.1 -> 56, likewise.
        assertEquals(56, GhostOpacity.DEFAULT.tileAlpha)
    }

    @Test
    fun `both ends of the range are exact`() {
        val invisible = GhostOpacity.of(0f, 0f)
        assertEquals(0, invisible.cardAlpha)
        assertEquals(0, invisible.tileAlpha)
        assertTrue(invisible.invisible)

        val opaque = GhostOpacity.of(1f, 1f)
        assertEquals(255, opaque.cardAlpha)
        assertEquals(255, opaque.tileAlpha)
        assertFalse(opaque.invisible)
    }

    @Test
    fun `every step of the slider is a different byte`() {
        val alphas = listOf(0, 25, 50, 75, 100).map { GhostOpacity.of(it / 100f, 0f).cardAlpha }
        assertEquals(listOf(0, 64, 128, 191, 255), alphas)
        assertEquals(alphas.sorted(), alphas)
        assertEquals(alphas.size, alphas.toSet().size)
    }

    @Test
    fun `out of range and NaN fall back instead of throwing`() {
        assertEquals(1f, GhostOpacity.of(4f, 2f).card)
        assertEquals(0f, GhostOpacity.of(-1f, -0.5f).card)
        assertEquals(GHOST_CARD_OPACITY_DEFAULT, GhostOpacity.of(Float.NaN, 0.5f).card)
        assertEquals(GHOST_TILE_OPACITY_DEFAULT, GhostOpacity.of(0.5f, Float.NaN).tile)
        assertEquals(255, GhostOpacity.of(Float.POSITIVE_INFINITY, 0f).cardAlpha)
    }

    @Test
    fun `the face and the square move independently`() {
        // Raising the face must not drag the faint placement square with it.
        val louder = GhostOpacity.of(1f, GHOST_TILE_OPACITY_DEFAULT)
        assertEquals(255, louder.cardAlpha)
        assertEquals(GhostOpacity.DEFAULT.tileAlpha, louder.tileAlpha)
        val quieter = GhostOpacity.of(GHOST_CARD_OPACITY_DEFAULT, 1f)
        assertEquals(GhostOpacity.DEFAULT.cardAlpha, quieter.cardAlpha)
        assertEquals(255, quieter.tileAlpha)
    }
}

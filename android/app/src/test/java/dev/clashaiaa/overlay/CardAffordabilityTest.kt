package dev.clashaiaa.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android port of `tests/test_card_affordability.py`: the same acceptance
 * cases A-F, the same animation rules and the same grey recipe, so the tablet
 * gauge cannot drift from the Windows one.
 */
class CardAffordabilityTest {

    // ---- case A: a fixed 5-cost card ------------------------------------

    @Test
    fun `a five cost card follows the elixir`() {
        val expected = listOf(
            Triple(0f, 0f, false),
            Triple(1f, 0.2f, false),
            Triple(2.5f, 0.5f, false),
            Triple(4f, 0.8f, false),
            Triple(4.99f, 0.998f, false),
            Triple(5f, 1f, true),
            Triple(10f, 1f, true),
        )
        for ((elixir, progress, ready) in expected) {
            val result = calculateAffordability(elixir, 5f)
            assertEquals("progress at $elixir", progress, result.progress, 0.001f)
            assertEquals("ready at $elixir", ready, result.ready)
            assertTrue(result.known)
            assertEquals(5f, result.cost)
        }
    }

    @Test
    fun `case C three and a half elixir is eighty seven and a half percent`() {
        val result = calculateAffordability(3.5f, 4f)
        assertEquals(0.875f, result.progress, 1e-6f)
        assertFalse(result.ready)
        assertEquals(105, result.bucket)          // 0.875 * 120
    }

    @Test
    fun `case F the ready boundary is the raw value`() {
        assertFalse(calculateAffordability(4.98f, 5f).ready)
        assertFalse(calculateAffordability(4.99f, 5f).ready)
        assertTrue(calculateAffordability(5.00f, 5f).ready)
        assertTrue(calculateAffordability(5.01f, 5f).ready)
        // The panel text is identical on both sides of the boundary, which is why
        // the raw value has to drive the gauge.
        assertEquals(OverlayRenderer.formatElixir(player(4.98f)), OverlayRenderer.formatElixir(player(5.01f)))
        val nearly = calculateAffordability(4.99f, 5f)
        assertTrue(nearly.progress < 1f)
        assertTrue(nearly.bucket < SWEEP_STEPS)
        assertEquals(SWEEP_STEPS, calculateAffordability(5.00f, 5f).bucket)
    }

    @Test
    fun `the bucket is floored so a sliver never paints as ready`() {
        for (elixir in listOf(4.9f, 4.99f, 4.999f, 4.999999f)) {
            val result = calculateAffordability(elixir, 5f)
            assertFalse("ready at $elixir", result.ready)
            assertTrue("bucket at $elixir", result.bucket < SWEEP_STEPS)
            val state = CardAffordabilityState(
                known = true,
                targetProgress = result.progress,
                visualProgress = result.progress,
            )
            assertTrue("dimmed at $elixir", state.dimmed)
        }
    }

    @Test
    fun `a free card is always castable`() {
        val result = calculateAffordability(0f, 0f)
        assertTrue(result.known)
        assertTrue(result.ready)
        assertEquals(1f, result.progress)
        // A negative cost is broken data, not a free card.
        assertFalse(calculateAffordability(0f, -1f).known)
    }

    @Test
    fun `an unknown cost is never a claim and never divides`() {
        for (result in listOf(
            calculateAffordability(3f, null),
            calculateAffordability(null, 5f),
            calculateAffordability(null, null),
            calculateAffordability(3f, Float.NaN),
            calculateAffordability(Float.NaN, 5f),
            calculateAffordability(10f, 999f),
        )) {
            assertFalse(result.known)
            assertFalse(result.ready)
            assertEquals(1f, result.progress)
            assertFalse(result.progress.isNaN())
        }
    }

    @Test
    fun `the effective cost prefers the probe and falls back to the table`() {
        assertEquals(7f, effectiveCost(7, 4))
        assertEquals(4f, effectiveCost(null, 4))
        // A broken probe row is not a cost: the table answers instead.
        assertEquals(4f, effectiveCost(99, 4))
        assertNull(effectiveCost(null, null))
        assertNull(effectiveCost(99, null))
    }

    // ---- case B: spending re-greys on the same frame ---------------------

    @Test
    fun `case B spending elixir regreys the hand on the same frame`() {
        val animator = AffordabilityAnimator()
        val ids = listOf(10, 11, 12, 13)
        val costs = listOf(3f, 4f, 5f, 7f)
        animator.update(ids, costs, 0f, 0.0)
        val settled = animator.update(ids, costs, 10f, 0.5)
        assertTrue(settled.all { it.ready })
        assertTrue(settled.all { it.visualProgress == 1f })

        // The 7-cost play lands: elixir is 3 on the next frame. No interpolation
        // is allowed on the way down - the panel must not keep showing castable.
        val spent = animator.update(ids, costs, 3f, 0.7)
        assertTrue(spent[0].ready)
        assertEquals(1f, spent[0].visualProgress)
        val expected = listOf(0.75f, 0.6f, 3f / 7f)
        for ((index, progress) in expected.withIndex()) {
            val slot = spent[index + 1]
            assertFalse("slot $index", slot.ready)
            assertEquals("visual $index", progress, slot.visualProgress, 1e-6f)
            assertEquals("target $index", progress, slot.targetProgress, 1e-6f)
            assertTrue("dimmed $index", slot.dimmed)
        }
    }

    // ---- case D: a card entering the hand -------------------------------

    @Test
    fun `case D a card entering the hand arrives at its real state`() {
        val animator = AffordabilityAnimator()
        animator.update(listOf(10, 11, 12, 13), listOf(3f, 3f, 3f, 3f), 6f, 0.0)
        val arrived = animator.update(listOf(10, 11, 12, 14), listOf(3f, 3f, 3f, 8f), 6f, 0.05)
        assertEquals(14, arrived[3].cardId)
        assertEquals(0.75f, arrived[3].targetProgress, 1e-6f)
        // First frame it exists, already three quarters coloured - never 0.
        assertEquals(0.75f, arrived[3].visualProgress, 1e-6f)
        assertEquals(90, arrived[3].bucket)
        assertFalse(arrived[3].ready)
    }

    @Test
    fun `a cost change snaps like a new card`() {
        val animator = AffordabilityAnimator()
        animator.update(listOf(10), listOf(5f), 5f, 0.0)
        assertEquals(1f, animator.states[0].visualProgress)
        val changed = animator.update(listOf(10), listOf(7f), 5f, 0.01)
        assertEquals(5f / 7f, changed[0].visualProgress, 1e-6f)
        assertFalse(changed[0].ready)
    }

    // ---- case E: double elixir needs no special case ---------------------

    @Test
    fun `case E double elixir needs no multiplier anywhere`() {
        fun sweep(rate: Float, seconds: Float = 2f, step: Double = 0.2): CardAffordabilityState {
            val animator = AffordabilityAnimator(slots = 1)
            var elixir = 0f
            var now = 0.0
            animator.update(listOf(100), listOf(8f), elixir, now)
            while (now < seconds) {
                now += step
                elixir += rate * step.toFloat()
                animator.update(listOf(100), listOf(8f), elixir, now)
            }
            return animator.states[0]
        }

        val single = sweep(1f / 2.8f)
        val double = sweep(2f / 2.8f)
        val triple = sweep(3f / 2.8f)
        assertEquals(2 * single.targetProgress, double.targetProgress, 1e-5f)
        assertEquals(3 * single.targetProgress, triple.targetProgress, 1e-5f)
        assertEquals(double.targetProgress, double.visualProgress, 0.01f)
    }

    // ---- animation shape -------------------------------------------------

    @Test
    fun `elixir coming back glides over the rise window`() {
        val animator = AffordabilityAnimator(slots = 1)
        animator.update(listOf(10), listOf(5f), 0f, 0.0)
        assertEquals(0f, animator.states[0].visualProgress)
        // Elixir is suddenly enough: READY is true on this very frame, but the
        // sweep still travels, and a quarter of the window travels a quarter.
        val ready = animator.update(listOf(10), listOf(5f), 5f, RISE_SECONDS / 4.0)
        assertTrue(ready[0].ready)
        assertEquals(0.25f, ready[0].visualProgress, 1e-6f)
        assertTrue(ready[0].dimmed)
        // A gap left alone for a full window closes completely, exactly.
        val settled = animator.update(listOf(10), listOf(5f), 5f, RISE_SECONDS * 2.0)
        assertEquals(1f, settled[0].visualProgress)
        assertFalse(settled[0].dimmed)
    }

    @Test
    fun `a slow recovery glides instead of stepping`() {
        // The real loop: ~33 ms frames, a probe frame every ~200 ms.
        val animator = AffordabilityAnimator(slots = 1)
        var elixir = 3f
        var now = 0.0
        animator.update(listOf(10), listOf(5f), elixir, now)
        val buckets = mutableListOf(animator.states[0].bucket)
        repeat(24) { tick ->
            now += 0.033
            if (tick % 6 == 5) elixir += 0.07f
            animator.update(listOf(10), listOf(5f), elixir, now)
            buckets.add(animator.states[0].bucket)
        }
        val steps = buckets.zipWithNext { earlier, later -> later - earlier }
        assertTrue("the sweep never moved: $buckets", steps.any { it != 0 })
        assertTrue("the sweep stepped instead of gliding: $buckets", steps.max() <= 2)
    }

    @Test
    fun `the animator only asks for frames while it is travelling`() {
        val animator = AffordabilityAnimator(slots = 1)
        animator.update(listOf(10), listOf(5f), 3f, 0.0)
        assertFalse(animator.animating)                       // settled, no frames
        animator.update(listOf(10), listOf(5f), 4f, 0.033)
        assertTrue(animator.animating)                        // mid-travel
        animator.update(listOf(10), listOf(5f), 4f, 5.0)
        assertFalse(animator.animating)
    }

    @Test
    fun `a hand that is not live is not a claim`() {
        val animator = AffordabilityAnimator()
        val states = animator.update(listOf(10, 11, 12, 13), listOf(3f, 4f, 5f, 7f), null, 0.0)
        assertTrue(states.none { it.known })
        assertTrue(states.all { it.visualProgress == 1f })
        assertTrue(states.none { it.dimmed })
    }

    @Test
    fun `reset forgets the battle`() {
        val animator = AffordabilityAnimator()
        animator.update(listOf(10, 11, 12, 13), listOf(3f, 4f, 5f, 7f), 3f, 0.0)
        assertTrue(animator.states.any { it.known })
        val reset = animator.reset()
        assertTrue(reset.none { it.known })
        assertTrue(reset.all { it.cardId == 0 })
    }

    @Test
    fun `a short or missing row never throws`() {
        val animator = AffordabilityAnimator()
        val states = animator.update(listOf(10), listOf(3f), 3f, 0.0)
        assertEquals(OverlayRenderer.SLOT_COUNT, states.size)
        assertEquals(0, states[3].cardId)
        assertFalse(states[3].known)
    }

    @Test
    fun `repeated updates without elapsed time do not move`() {
        val animator = AffordabilityAnimator(slots = 1)
        animator.update(listOf(10), listOf(5f), 0f, 0.0)
        val first = animator.update(listOf(10), listOf(5f), 4f, 1.0)
        val second = animator.update(listOf(10), listOf(5f), 4f, 1.0)
        assertEquals(first[0].visualProgress, second[0].visualProgress, 0f)
    }

    @Test
    fun `buckets cover the whole circle and are clamped`() {
        assertEquals(0, sweepBucket(0f))
        assertEquals(SWEEP_STEPS / 4, sweepBucket(0.25f))
        assertEquals(SWEEP_STEPS / 2, sweepBucket(0.5f))
        assertEquals(3 * SWEEP_STEPS / 4, sweepBucket(0.75f))
        assertEquals(SWEEP_STEPS, sweepBucket(1f))
        assertEquals(0, sweepBucket(-3f))
        assertEquals(SWEEP_STEPS, sweepBucket(9f))
    }

    // ---- the dark unpaid sector ------------------------------------------

    @Test
    fun `the sector covers exactly the unpaid share of the cost`() {
        // The acceptance case: 2 elixir against a 4, 5 and 7 cost hand. All
        // three tiles are grey, and the dark sector tells them apart.
        val expected = listOf(4f to 0.5f, 5f to 0.6f, 7f to 1f - 2f / 7f)
        for ((cost, missing) in expected) {
            val affordability = calculateAffordability(2f, cost)
            assertFalse("ready at $cost", affordability.ready)
            assertEquals("missing at $cost", missing, missingFraction(affordability.progress), 1e-4f)
            assertEquals("sweep at $cost", missing * 360f, maskSweepDegrees(affordability.progress), 1e-2f)
            assertEquals(
                "start at $cost",
                -90f + (1f - missing) * 360f,
                maskStartDegrees(affordability.progress),
                1e-2f,
            )
        }
    }

    @Test
    fun `an affordable card has no sector and a spent one is fully dark`() {
        assertEquals(0f, missingFraction(1f))
        assertEquals(0f, maskSweepDegrees(1f))
        assertEquals(360f, maskSweepDegrees(0f))
        // The sector always closes on 12 o'clock: -90 and 270 are the same angle.
        assertEquals(SWEEP_START_DEGREES, maskStartDegrees(0f))
        assertEquals(SWEEP_START_DEGREES + SWEEP_FULL_DEGREES, maskStartDegrees(1f), 1e-3f)
        // The sector starts where the paid-for sweep ends, and the two add up.
        for (progress in listOf(0f, 0.2f, 0.5f, 0.75f, 0.99f, 1f)) {
            assertEquals(
                SWEEP_FULL_DEGREES * progress,
                maskStartDegrees(progress) - SWEEP_START_DEGREES,
                1e-2f,
            )
            assertEquals(
                SWEEP_FULL_DEGREES,
                maskSweepDegrees(progress) + SWEEP_FULL_DEGREES * progress,
                1e-2f,
            )
        }
    }

    @Test
    fun `the sector fraction is clamped and never NaN`() {
        assertEquals(1f, missingFraction(-3f))
        assertEquals(1f, missingFraction(0f))
        assertEquals(0f, missingFraction(9f))
        assertEquals(1f, missingFraction(Float.NaN))
        assertFalse(maskSweepDegrees(Float.NaN).isNaN())
        // A broken row (unknown cost) paints nothing at all.
        val unknown = calculateAffordability(3f, null)
        assertEquals(0f, maskSweepDegrees(unknown.progress))
    }

    @Test
    fun `the sector ink is a dark translucent grey`() {
        val alpha = (AFFORD_MASK_INK ushr 24) and 0xff
        val red = (AFFORD_MASK_INK shr 16) and 0xff
        val green = (AFFORD_MASK_INK shr 8) and 0xff
        val blue = AFFORD_MASK_INK and 0xff
        assertEquals(AFFORD_MASK_ALPHA, alpha)
        // Clearly visible over the grey face, and never a blackout: the art
        // underneath still has to be identifiable.
        assertTrue("alpha=$alpha", alpha in 0x80..0xC0)
        assertTrue(red < 0x20 && green < 0x20 && blue < 0x28)
        // The ink has to be darker than the grey face it is drawn over, or it
        // would read as a highlight instead of as "still missing".
        val greyMid = 127.5f * (GREY_BRIGHTNESS * GREY_CONTRAST) +
            (0.5f - 0.5f * GREY_CONTRAST) * 255f
        assertTrue(red.toFloat() < greyMid && blue.toFloat() < greyMid)
    }

    // ---- the grey recipe -------------------------------------------------

    @Test
    fun `the grey matrix desaturates and keeps the card readable`() {
        val matrix = greyColorMatrix()
        assertEquals(20, matrix.size)
        // Every colour row is the same luma blend, so R, G and B come out equal:
        // saturation is zero by construction.
        for (row in 0 until 3) {
            val base = row * 5
            assertEquals(LUMA_R * greyScale(), matrix[base], 1e-6f)
            assertEquals(LUMA_G * greyScale(), matrix[base + 1], 1e-6f)
            assertEquals(LUMA_B * greyScale(), matrix[base + 2], 1e-6f)
            assertEquals(0f, matrix[base + 3])
            assertEquals(greyOffset(), matrix[base + 4], 1e-4f)
        }
        assertEquals(0f, matrix[15])
        assertEquals(1f, matrix[18])                          // alpha untouched
        // Slightly darker than the original, but nowhere near invisible.
        assertTrue(greyScale() in 0.65f..0.90f)
        assertNotEquals(0f, greyScale())
        assertTrue(greyOffset() > 0f)
    }

    @Test
    fun `grey luma of a mid tone stays visible`() {
        // ColorMatrix runs in 0..255 channel space, which is also what
        // greyOffset() is expressed in: a white pixel must stay bright, a mid
        // grey must stay readable, and grey must be darker than the original.
        fun grey(value: Float): Float = value * greyScale() + greyOffset()
        val white = grey(255f)
        val mid = grey(127.5f)
        assertTrue("white=$white", white in 180f..235f)
        assertTrue("mid=$mid", mid > 90f)
        assertTrue(mid < white)
    }

    private fun greyScale(): Float = GREY_BRIGHTNESS * GREY_CONTRAST
    private fun greyOffset(): Float = (0.5f - 0.5f * GREY_CONTRAST) * 255f

    private fun player(elixir: Float) = ProbePlayer(
        owner = 1,
        accountId = 42L,
        elixir = elixir,
        elixirRaw = (elixir * 10_000).toInt(),
        hand = emptyList(),
        deck = emptyList(),
    )
}

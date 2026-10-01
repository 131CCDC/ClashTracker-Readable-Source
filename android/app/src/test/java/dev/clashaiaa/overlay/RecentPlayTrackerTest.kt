package dev.clashaiaa.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPlayTrackerTest {
    private fun battle(tick: Int, hand: List<Int>) = BattleState(
        inBattle = true,
        stale = false,
        tick = tick,
        players = listOf(
            ProbePlayer(0, 11L, 5f, 50_000, emptyList(), emptyList()),
            ProbePlayer(1, 22L, 5f, 50_000, hand.mapIndexed { slot, id -> HandCard(slot, id, null) }, emptyList()),
        ),
    )

    @Test
    fun `records one unambiguous card leaving the enemy hand`() {
        val tracker = RecentPlayTracker()
        assertEquals(emptyList<Int>(), tracker.update(battle(100, listOf(1, 2, 3, 4)), 0, 11L))
        assertEquals(listOf(1), tracker.update(battle(101, listOf(5, 2, 3, 4)), 0, 11L))
    }

    @Test
    fun `ambiguous jump records nothing`() {
        val tracker = RecentPlayTracker()
        tracker.update(battle(100, listOf(1, 2, 3, 4)), 0, 11L)
        assertEquals(emptyList<Int>(), tracker.update(battle(101, listOf(5, 6, 3, 4)), 0, 11L))
    }
}

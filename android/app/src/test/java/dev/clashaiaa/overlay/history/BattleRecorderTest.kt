package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleRecorderTest {

    private val namer = object : CardNamer {
        override fun card(
            side: CardSide,
            slot: Int,
            cardId: Int,
            isEvolution: Boolean,
            isHero: Boolean,
            cost: Int?,
        ) = BattleCard(side, slot, cardId, "C$cardId", "卡$cardId", isEvolution, isHero, cost)
    }

    private var now = 1_700_000_000_000L
    private val recorder = BattleRecorder(namer) { now }

    private fun card(
        slot: Int,
        cardId: Int,
        activeForm: Int = 0,
        progress: Int = 0,
        cost: Int? = 3,
    ) = FrameCard(slot, cardId, activeForm, progress, cost, false, false)

    private fun frame(
        tick: Int,
        crowns: List<Int> = listOf(0, 0),
        finalized: Boolean = false,
        decks: Map<Int, List<FrameCard>> = mapOf(
            0 to listOf(card(0, 26000021), card(1, 28000011)),
            1 to listOf(card(0, 26000032), card(1, 28000009)),
        ),
        accounts: Map<Int, Long> = mapOf(0 to 100L, 1 to 200L),
        towers: List<FrameTower> = emptyList(),
    ) = BattleFrame(
        inBattle = true,
        stale = false,
        tick = tick,
        monotonicMs = null,
        finalized = finalized,
        resultRaw = 0,
        crowns = crowns,
        players = decks.map { (owner, cards) ->
            FramePlayer(owner, accounts[owner] ?: 0L, 5.0f, cards)
        },
        towers = towers,
    )

    private fun idleFrame() = BattleFrame(
        inBattle = false,
        stale = false,
        tick = -1,
        monotonicMs = null,
        finalized = false,
        resultRaw = null,
        crowns = emptyList(),
        players = emptyList(),
        towers = emptyList(),
    )

    @Test
    fun `battle start writes a crash-safe stub with both decks`() {
        val events = recorder.onFrame(frame(0), localOwner = 0, identitySource = "CONFIGURED")
        assertEquals(1, events.size)
        val started = events[0] as BattleRecorder.Event.Started
        assertEquals(BattleStatus.INCOMPLETE, started.record.status)
        assertEquals(BattleResult.INCOMPLETE, started.record.result)
        assertEquals(2, started.record.myDeck.size)
        assertEquals(2, started.record.enemyDeck.size)
        assertEquals("C26000021", started.record.myDeck.first().name)
        assertEquals("live:1700000000:100:200", started.record.battleUid)
        assertTrue(started.record.fullBattle)
        assertEquals("CONFIGURED", started.record.identitySource)
    }

    @Test
    fun `battle end remains unfinalized but keeps telemetry duration`() {
        recorder.onFrame(frame(0), 0, "CONFIGURED")
        recorder.onFrame(frame(100, crowns = listOf(1, 0)), 0, "CONFIGURED")
        recorder.onFrame(frame(200, crowns = listOf(2, 0), finalized = true), 0, "CONFIGURED")
        val events = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
        val finished = events.single() as BattleRecorder.Event.Finished
        assertEquals(BattleStatus.INCOMPLETE, finished.record.status)
        assertEquals(BattleResult.INCOMPLETE, finished.record.result)
        assertEquals(200, finished.record.durationTicks)
        assertEquals(10.0, finished.record.durationSeconds!!, 1e-9)
        assertEquals(2, finished.record.myCrowns)
        assertEquals(0, finished.record.enemyCrowns)
        assertEquals(1_700_000_010_000L, finished.record.endTime!!)
    }

    @Test
    fun `crowns only ever climb within one battle`() {
        recorder.onFrame(frame(0, crowns = listOf(0, 0)), 0, "CONFIGURED")
        recorder.onFrame(frame(10, crowns = listOf(1, 0)), 0, "CONFIGURED")
        // A truncated entity list can make the probe report a lower count for a frame.
        recorder.onFrame(frame(20, crowns = listOf(0, 0)), 0, "CONFIGURED")
        val finished = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
            .single() as BattleRecorder.Event.Finished
        assertEquals(1, finished.record.myCrowns)
    }

    @Test
    fun `a tick going backwards is a new battle`() {
        recorder.onFrame(frame(0), 0, "CONFIGURED")
        recorder.onFrame(frame(500), 0, "CONFIGURED")
        val events = recorder.onFrame(frame(0), 0, "CONFIGURED")
        assertTrue(events[0] is BattleRecorder.Event.Finished)
        assertTrue(events[1] is BattleRecorder.Event.Started)
    }

    @Test
    fun `a different opponent on the same seat is a new battle`() {
        recorder.onFrame(frame(0), 0, "CONFIGURED")
        val events = recorder.onFrame(
            frame(10, accounts = mapOf(0 to 100L, 1 to 999L)),
            0,
            "CONFIGURED",
        )
        assertTrue(events[0] is BattleRecorder.Event.Finished)
        assertTrue(events[1] is BattleRecorder.Event.Started)
    }

    @Test
    fun `evolution and hero flags come from observed loadout state not from the variant list`() {
        recorder.onFrame(
            frame(
                0,
                decks = mapOf(
                    0 to listOf(card(0, 26000021)),
                    1 to listOf(card(0, 26000043, activeForm = 1), card(1, 26000000, activeForm = 2)),
                ),
            ),
            0,
            "CONFIGURED",
        )
        val finished = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
            .single() as BattleRecorder.Event.Finished
        val enemy = finished.record.enemyDeck.sortedBy { it.slot }
        assertTrue(enemy[0].isEvolution)
        assertFalse(enemy[0].isHero)
        assertTrue(enemy[1].isHero)
        assertEquals("Evo C26000043", enemy[0].label)
    }

    @Test
    fun `evolution progress above zero is evidence too`() {
        recorder.onFrame(
            frame(0, decks = mapOf(0 to listOf(card(0, 26000021)), 1 to listOf(card(0, 26000043, progress = 1)))),
            0,
            "CONFIGURED",
        )
        val finished = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
            .single() as BattleRecorder.Event.Finished
        assertTrue(finished.record.enemyDeck.first().isEvolution)
    }

    @Test
    fun `tower state never becomes an authoritative live result`() {
        val towers = listOf(
            FrameTower(owner = 0, isKing = false, hp = 3000, maxHp = 4000),
            FrameTower(owner = 1, isKing = false, hp = 1000, maxHp = 4000),
        )
        recorder.onFrame(frame(0, crowns = listOf(1, 1), towers = towers), 0, "CONFIGURED")
        val finished = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
            .single() as BattleRecorder.Event.Finished
        assertEquals(BattleResult.INCOMPLETE, finished.record.result)
        assertEquals(BattleStatus.INCOMPLETE, finished.record.status)
    }

    @Test
    fun `zero or tied crowns never become a live draw`() {
        recorder.onFrame(frame(0, crowns = listOf(1, 1)), 0, "CONFIGURED")
        val finished = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
            .single() as BattleRecorder.Event.Finished
        assertEquals(BattleResult.INCOMPLETE, finished.record.result)
        assertEquals(BattleStatus.INCOMPLETE, finished.record.status)
    }

    @Test
    fun `the seat setting decides which crowns are mine`() {
        recorder.onFrame(frame(0, crowns = listOf(0, 2)), localOwner = 1, identitySource = "SEAT_FALLBACK")
        val finished = recorder.onFrame(idleFrame(), 1, "SEAT_FALLBACK")
            .single() as BattleRecorder.Event.Finished
        assertEquals(2, finished.record.myCrowns)
        assertEquals(0, finished.record.enemyCrowns)
        assertEquals(BattleResult.INCOMPLETE, finished.record.result)
        assertEquals("SEAT_FALLBACK", finished.record.identitySource)
    }

    @Test
    fun `attaching mid-battle still records but is not marked as a full battle`() {
        recorder.onFrame(frame(900), 0, "CONFIGURED")
        val started = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
        val finished = started.single() as BattleRecorder.Event.Finished
        assertFalse(finished.record.fullBattle)
        assertEquals(900, finished.record.firstTick)
    }

    @Test
    fun `an idle session is closed without a closing frame`() {
        recorder.onFrame(frame(0), 0, "CONFIGURED")
        assertTrue(recorder.active)
        now += 10_000
        val events = recorder.onIdle()
        assertEquals(1, events.size)
        assertTrue(events[0] is BattleRecorder.Event.Finished)
        assertFalse(recorder.active)
    }

    @Test
    fun `an idle tick inside the window does nothing`() {
        recorder.onFrame(frame(0), 0, "CONFIGURED")
        now += 1_000
        assertTrue(recorder.onIdle().isEmpty())
        assertTrue(recorder.active)
    }

    @Test
    fun `the enemy archetype is classified at record time`() {
        recorder.onFrame(
            frame(
                0,
                decks = mapOf(
                    0 to listOf(card(0, 26000021)),
                    1 to listOf(
                        card(0, 26000032), card(1, 28000009), card(2, 26000010),
                        card(3, 26000030), card(4, 28000011), card(5, 26000049),
                        card(6, 26000011), card(7, 27000004),
                    ),
                ),
            ),
            0,
            "CONFIGURED",
        )
        val started = recorder.onFrame(frame(1), 0, "CONFIGURED")
        assertTrue(started.isEmpty())
        val finished = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
            .single() as BattleRecorder.Event.Finished
        assertEquals("Miner", finished.record.enemyArchetype)
        assertEquals("Poison", finished.record.enemyArchetypeSubtype)
        assertNotNull(finished.record.enemyArchetypeConfidence)
    }

    @Test
    fun `reset drops the session without writing`() {
        recorder.onFrame(frame(0), 0, "CONFIGURED")
        recorder.reset()
        assertFalse(recorder.active)
        assertTrue(recorder.onIdle().isEmpty())
    }

    @Test
    fun `a stale frame closes the battle`() {
        recorder.onFrame(frame(0), 0, "CONFIGURED")
        val stale = frame(10).copy(stale = true)
        val events = recorder.onFrame(stale, 0, "CONFIGURED")
        assertTrue(events.single() is BattleRecorder.Event.Finished)
    }

    @Test
    fun `mode and arena stay unknown for a live capture`() {
        recorder.onFrame(frame(0), 0, "CONFIGURED")
        val finished = recorder.onFrame(idleFrame(), 0, "CONFIGURED")
            .single() as BattleRecorder.Event.Finished
        assertNull(finished.record.mode)
        assertNull(finished.record.arena)
        assertEquals(BattleSource.LIVE_CAPTURE, finished.record.source)
    }
}

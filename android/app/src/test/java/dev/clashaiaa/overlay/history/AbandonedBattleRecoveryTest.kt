package dev.clashaiaa.overlay.history

import org.junit.Assert.*
import org.junit.Test

class AbandonedBattleRecoveryTest {
    private var now = 1_700_000_000_000L
    private val namer = object : CardNamer {
        override fun card(side: CardSide, slot: Int, cardId: Int, isEvolution: Boolean, isHero: Boolean, cost: Int?) =
            BattleCard(side, slot, cardId, "C$cardId", "C$cardId", isEvolution, isHero, cost)
    }
    private fun deck(base: Int) = (0..7).map { FrameCard(it, base + it, 0, 0, 3, false, false) }
    private fun frame(tick: Int) = BattleFrame(true, false, tick, null, false, null, listOf(0, 0),
        listOf(FramePlayer(0, 100, 5f, deck(1)), FramePlayer(1, 200, 5f, deck(11))), emptyList())

    @Test fun `abandon keeps decks and incomplete result`() {
        val recorder = BattleRecorder(namer) { now }
        val start = recorder.onFrame(frame(0), 0, "OBSERVED", true).filterIsInstance<BattleRecorder.Event.Started>().single()
        assertEquals(8, start.record.myDeck.size)
        recorder.onFrame(frame(500), 0, "OBSERVED", true)
        now += 5_000
        val finished = recorder.onIdle().filterIsInstance<BattleRecorder.Event.Finished>().single().record
        assertEquals(BattleResult.INCOMPLETE, finished.result)
        assertEquals(BattleStatus.INCOMPLETE, finished.status)
        assertTrue(finished.needsHistoryReconciliation)
        assertEquals("pending", finished.reconciliationState)
        assertEquals(8, finished.myDeck.size)
        assertEquals(8, finished.enemyDeck.size)
    }

    private fun incomplete(uid: String, time: Long = 1_000_000L) = BattleRecord(
        battleUid = uid, battleTime = time, startTime = time, endTime = time + 25_000,
        durationTicks = 500, durationSeconds = 25.0, myPlayerId = "100", enemyPlayerId = "200",
        result = BattleResult.INCOMPLETE, status = BattleStatus.INCOMPLETE,
        myDeck = TestRecords.deck(1,2,3,4,5,6,7,8, side = CardSide.SELF),
        enemyDeck = TestRecords.deck(11,12,13,14,15,16,17,18), source = BattleSource.LIVE_CAPTURE,
        firstTick = 0, lastTick = 500, fullBattle = true, identitySource = "OBSERVED",
        needsHistoryReconciliation = true, reconciliationState = "pending",
    )
    private fun history(result: BattleResult, uid: String = "history") = incomplete(uid).copy(
        replayId = "R1", result = result, status = BattleStatus.COMPLETE,
        myCrowns = if (result == BattleResult.WIN) 3 else 0,
        enemyCrowns = if (result == BattleResult.LOSS) 3 else 0,
        source = BattleSource.NULLS_HISTORY, identitySource = "GAME_HISTORY",
        durationTicks = 3000, durationSeconds = 150.0, lastTick = null, fullBattle = false,
        needsHistoryReconciliation = false, reconciliationState = "matched",
    )

    @Test fun `authoritative history completes abandoned loss`() {
        val live = incomplete("live")
        val recovered = BattleHistoryReconciler.finalize(history(BattleResult.LOSS), listOf(live), false).record!!
        val report = BattleCanonicalizer.consolidate(listOf(recovered), 100)
        assertEquals(1, report.canonicalCount)
        assertEquals(BattleResult.LOSS, report.records.single().result)
        assertEquals(BattleStatus.COMPLETE, report.records.single().status)
        assertEquals("matched", report.records.single().reconciliationState)
    }

    @Test fun `authoritative history may complete abandoned win`() {
        val live = incomplete("live")
        val recovered = BattleHistoryReconciler.finalize(history(BattleResult.WIN), listOf(live), false).record!!
        val row = BattleCanonicalizer.consolidate(listOf(recovered), 100).records.single()
        assertEquals(BattleResult.WIN, row.result)
        assertFalse(row.needsHistoryReconciliation)
    }

    @Test fun `process restart does not require working session RAM`() {
        BattleWorkingSessions.clear()
        val db = incomplete("db")
        val recovered = BattleHistoryReconciler.finalize(history(BattleResult.LOSS), listOf(db), false).record!!
        assertEquals("db", recovered.battleUid)
        assertEquals(1, BattleCanonicalizer.consolidate(listOf(recovered), 100).canonicalCount)
    }

    @Test fun `same decks rematch with independent start remains separate`() {
        val abandoned = incomplete("first")
        val rematch = incomplete("second", abandoned.battleTime + 180_000).copy(durationTicks = 900, lastTick = 900)
        assertEquals(2, BattleCanonicalizer.consolidate(listOf(abandoned, rematch), 100).canonicalCount)
    }

    @Test fun `short probe outage neither finishes nor splits battle`() {
        val recorder = BattleRecorder(namer) { now }
        assertEquals(1, recorder.onFrame(frame(0), 0, "OBSERVED", true).size)
        now += 3_000
        assertTrue(recorder.onIdle().isEmpty())
        assertTrue(recorder.onFrame(frame(100), 0, "OBSERVED", true).isEmpty())
        assertTrue(recorder.active)
    }

    @Test fun `no authoritative history leaves incomplete untouched`() {
        val row = BattleCanonicalizer.consolidate(listOf(incomplete("only")), 100).records.single()
        assertEquals(BattleResult.INCOMPLETE, row.result)
        assertTrue(row.needsHistoryReconciliation)
    }
}

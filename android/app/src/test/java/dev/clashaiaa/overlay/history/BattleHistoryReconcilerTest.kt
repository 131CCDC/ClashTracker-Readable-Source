package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleHistoryReconcilerTest {
    private fun deck(side: CardSide, start: Int) = (0..7).map { slot ->
        BattleCard(side, slot, start + slot, "", "")
    }

    private fun authoritative(
        battleId: String? = "B-1",
        replayId: String? = "R-1",
    ) = BattleRecord(
        battleUid = "incoming",
        battleId = battleId,
        replayId = replayId,
        battleTime = 1_700_000_000_000,
        myPlayerId = "100",
        enemyPlayerId = "200",
        myCrowns = 2,
        enemyCrowns = 1,
        result = BattleResult.WIN,
        status = BattleStatus.COMPLETE,
        myDeck = deck(CardSide.SELF, 1),
        enemyDeck = deck(CardSide.ENEMY, 101),
        source = BattleSource.NULLS_HISTORY,
    )

    @Test
    fun `official id then replay id define canonical identity`() {
        assertEquals("nulls:B-1", BattleHistoryReconciler.canonicalUid(authoritative()))
        assertEquals(
            "nulls-replay:R-1",
            BattleHistoryReconciler.canonicalUid(authoritative(battleId = null)),
        )
    }

    @Test
    fun `invalid ids or decks cannot finalize`() {
        assertFalse(BattleHistoryReconciler.isFinalized(authoritative().copy(myPlayerId = "0")))
        assertFalse(BattleHistoryReconciler.isFinalized(authoritative().copy(enemyDeck = emptyList())))
        assertFalse(BattleHistoryReconciler.isFinalized(authoritative().copy(result = BattleResult.UNKNOWN)))
        assertFalse(BattleHistoryReconciler.isFinalized(authoritative().copy(status = BattleStatus.INCOMPLETE)))
    }

    @Test
    fun `fallback identity ignores unknown file time`() {
        val record = authoritative(battleId = null, replayId = null)
        val first = BattleHistoryReconciler.canonicalUid(record, includeBattleTime = false)
        val copiedLater = BattleHistoryReconciler.canonicalUid(
            record.copy(battleTime = record.battleTime + 86_400_000),
            includeBattleTime = false,
        )
        assertEquals(first, copiedLater)
    }

    @Test
    fun `matching live session donates timing but never outcome`() {
        val live = authoritative().copy(
            battleUid = "live:one",
            battleId = null,
            replayId = null,
            battleTime = 1_699_999_990_000,
            startTime = 1_699_999_990_000,
            endTime = 1_700_000_150_000,
            durationSeconds = 160.0,
            result = BattleResult.INCOMPLETE,
            status = BattleStatus.INCOMPLETE,
            source = BattleSource.LIVE_CAPTURE,
            firstTick = 0,
            lastTick = 3200,
            identitySource = "CONFIGURED",
            fullBattle = true,
        )
        val outcome = BattleHistoryReconciler.finalize(authoritative(), listOf(live))
        assertEquals("live:one", outcome.matchedLiveUid)
        assertEquals(BattleResult.WIN, outcome.record!!.result)
        assertEquals(BattleStatus.COMPLETE, outcome.record!!.status)
        assertEquals("GAME_HISTORY", outcome.record!!.identitySource)
        assertTrue(outcome.record!!.fullBattle)
    }

    @Test
    fun `live capture alone is never finalized`() {
        val outcome = BattleHistoryReconciler.finalize(
            authoritative().copy(source = BattleSource.LIVE_CAPTURE),
        )
        assertNull(outcome.record)
        assertEquals("not_game_history", outcome.rejection)
    }

    @Test
    fun `validated native result finalizes without Nulls metadata`() {
        val native = BattleRecord(
            battleUid = "live:one",
            battleTime = 1_700_000_000_000,
            result = BattleResult.UNKNOWN,
            status = BattleStatus.COMPLETE,
            source = BattleSource.NATIVE_RESULT,
            winnerOwner = 1,
            nativeResultRaw = 1,
            nativeResultValidated = true,
        )
        assertTrue(BattleHistoryReconciler.isFinalized(native))
    }

    @Test
    fun `invalid native result is rejected`() {
        val native = BattleRecord(
            battleUid = "live:one",
            battleTime = 1_700_000_000_000,
            status = BattleStatus.COMPLETE,
            source = BattleSource.NATIVE_RESULT,
            winnerOwner = 1,
            nativeResultRaw = 2,
            nativeResultValidated = true,
        )
        assertFalse(BattleHistoryReconciler.isFinalized(native))
        assertFalse(BattleHistoryReconciler.isFinalized(native.copy(nativeResultValidated = false)))
    }

    @Test
    fun `Nulls enrichment keeps the matched native uid`() {
        val native = authoritative().copy(
            battleUid = "live:native",
            battleId = null,
            replayId = null,
            source = BattleSource.NATIVE_RESULT,
            winnerOwner = 0,
            nativeResultRaw = 0,
            nativeResultValidated = true,
        )
        val outcome = BattleHistoryReconciler.finalize(authoritative(), listOf(native))
        assertEquals("live:native", outcome.record!!.battleUid)
        assertEquals("live:native", outcome.matchedLiveUid)
        assertEquals(BattleSource.NULLS_HISTORY, outcome.record!!.source)
    }
}

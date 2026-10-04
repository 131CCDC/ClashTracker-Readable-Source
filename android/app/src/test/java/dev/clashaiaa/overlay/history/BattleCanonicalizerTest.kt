package dev.clashaiaa.overlay.history

import org.junit.Assert.*
import org.junit.Test

class BattleCanonicalizerTest {
    private val self = 100L
    private val myDeck = TestRecords.deck(1,2,3,4,5,6,7,8, side = CardSide.SELF)
    private val enemyDeck = TestRecords.deck(11,12,13,14,15,16,17,18)

    private fun completed(uid: String, time: Long = 1_000_000L, ticks: Int = 3652) = BattleRecord(
        battleUid = uid, battleTime = time, startTime = time, endTime = time + ticks * 50L,
        durationTicks = ticks, durationSeconds = ticks / 20.0,
        myPlayerId = "100", enemyPlayerId = "200", myCrowns = 3, enemyCrowns = 0,
        result = BattleResult.WIN, status = BattleStatus.COMPLETE,
        myDeck = myDeck, enemyDeck = enemyDeck, source = BattleSource.NATIVE_RESULT,
        firstTick = ticks, lastTick = ticks, identitySource = "OBSERVED",
        nativeResultValidated = true, winnerOwner = 0, nativeResultRaw = 0,
    )

    @Test fun `five second duplicate becomes one`() {
        val report = BattleCanonicalizer.consolidate(listOf(completed("a"), completed("b", 1_005_000L)), self)
        assertEquals(1, report.canonicalCount)
        assertEquals(1, report.mergedDuplicates)
    }

    @Test fun `seventeen completed polls remain one`() {
        val rows = (0 until 17).map { completed("p$it", 1_000_000L + it * 5_000L) }
        assertEquals(1, BattleCanonicalizer.consolidate(rows, self).canonicalCount)
    }

    @Test fun `real rematch remains two`() {
        val first = completed("one").copy(firstTick = 0, lastTick = 3652, fullBattle = true)
        val second = completed("two", 1_250_000L, 2900).copy(
            firstTick = 0, lastTick = 2900, fullBattle = true, myCrowns = 1, enemyCrowns = 2, result = BattleResult.LOSS,
        )
        assertEquals(2, BattleCanonicalizer.consolidate(listOf(first, second), self).canonicalCount)
    }

    @Test fun `canonical self on player B swaps all sides`() {
        val reversed = completed("r").copy(
            myPlayerId = "200", enemyPlayerId = "100", myCrowns = 0, enemyCrowns = 3,
            myDeck = enemyDeck.map { it.copy(side = CardSide.SELF) },
            enemyDeck = myDeck.map { it.copy(side = CardSide.ENEMY) }, result = BattleResult.LOSS,
        )
        val normalized = BattleCanonicalizer.normalizeBattleSides(reversed, self)
        assertEquals("100", normalized.myPlayerId)
        assertEquals(3, normalized.myCrowns)
        assertEquals(BattleResult.WIN, normalized.result)
        assertEquals(myDeck.map { it.cardId }, normalized.myDeck.map { it.cardId })
        assertTrue(normalized.personalRecordEligible)
    }

    @Test fun `seat fallback with absent self is excluded`() {
        val bad = completed("bad").copy(myPlayerId = "300", enemyPlayerId = "400", identitySource = "SEAT_FALLBACK")
        val normalized = BattleCanonicalizer.normalizeBattleSides(bad, self)
        assertFalse(normalized.personalRecordEligible)
        assertEquals(BattleResult.UNKNOWN, normalized.result)
    }

    @Test fun `unknown is never promoted to win`() {
        val row = BattleCanonicalizer.normalizeBattleSides(completed("u").copy(result = BattleResult.UNKNOWN), self)
        assertEquals(BattleResult.UNKNOWN, row.result)
    }

    @Test fun `live record is enriched by replay instead of appended`() {
        val live = completed("live")
        val history = completed("history").copy(
            replayId = "R1", source = BattleSource.NULLS_HISTORY, identitySource = "GAME_HISTORY",
            mode = "Ladder", arena = "Arena",
        )
        val report = BattleCanonicalizer.consolidate(listOf(live, history), self)
        assertEquals(1, report.canonicalCount)
        assertEquals("R1", report.records.single().replayId)
        assertEquals(BattleSource.NULLS_HISTORY, report.records.single().source)
    }

    @Test fun `restart observation remains one`() {
        val before = completed("before")
        val after = completed("after", before.battleTime + 8 * 60_000L)
        assertEquals(1, BattleCanonicalizer.consolidate(listOf(before, after), self).canonicalCount)
    }
}

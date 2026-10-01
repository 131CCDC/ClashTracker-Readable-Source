package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleMergeTest {

    private fun stub() = BattleRecord(
        battleUid = "live:1:100:200",
        battleTime = 1_000,
        startTime = 1_000,
        myCrowns = 0,
        enemyCrowns = 0,
        result = BattleResult.INCOMPLETE,
        status = BattleStatus.INCOMPLETE,
        source = BattleSource.LIVE_CAPTURE,
        firstTick = 0,
        lastTick = 0,
        myDeck = listOf(TestRecords.card(26000021, 0, CardSide.SELF, "HogRider")),
        enemyDeck = listOf(TestRecords.card(26000032, 0, CardSide.ENEMY, "Miner")),
        fullBattle = true,
    )

    @Test
    fun `the end update completes the stub it was created from`() {
        val completed = stub().copy(
            result = BattleResult.WIN,
            status = BattleStatus.COMPLETE,
            myCrowns = 2,
            enemyCrowns = 1,
            durationSeconds = 157.0,
            endTime = 158_000,
            lastTick = 3140,
        )
        val merged = mergeBattleRecords(stub(), completed)
        assertEquals(BattleResult.WIN, merged.result)
        assertEquals(BattleStatus.COMPLETE, merged.status)
        assertEquals(2, merged.myCrowns)
        assertEquals(157.0, merged.durationSeconds!!, 1e-9)
        assertEquals(3140, merged.lastTick)
    }

    @Test
    fun `a history import fills the gaps the capture could not attest`() {
        val stored = stub().copy(
            result = BattleResult.WIN,
            status = BattleStatus.COMPLETE,
            myCrowns = 2,
            enemyCrowns = 1,
            durationSeconds = 157.0,
        )
        val imported = BattleRecord(
            battleUid = stored.battleUid,
            battleTime = stored.battleTime,
            battleId = "B7",
            replayId = "R7",
            mode = "Ladder",
            arena = "Legendary",
            myPlayerName = "AAICR",
            enemyPlayerName = "AAICR2",
            trophyChange = 31,
            result = BattleResult.WIN,
            status = BattleStatus.COMPLETE,
            source = BattleSource.NULLS_HISTORY,
        )
        val merged = mergeBattleRecords(stored, imported)
        assertEquals("B7", merged.battleId)
        assertEquals("R7", merged.replayId)
        assertEquals("Ladder", merged.mode)
        assertEquals("Legendary", merged.arena)
        assertEquals("AAICR2", merged.enemyPlayerName)
        assertEquals(31, merged.trophyChange)
        // The live capture's own facts survive.
        assertEquals(157.0, merged.durationSeconds!!, 1e-9)
        assertEquals(2, merged.myCrowns)
        assertEquals(1, merged.myDeck.size)
    }

    @Test
    fun `an import never erases a decided outcome with an unknown one`() {
        val stored = stub().copy(result = BattleResult.WIN, status = BattleStatus.COMPLETE)
        val imported = BattleRecord(
            battleUid = stored.battleUid,
            battleTime = stored.battleTime,
            result = BattleResult.UNKNOWN,
            status = BattleStatus.INCOMPLETE,
            source = BattleSource.NULLS_HISTORY,
        )
        val merged = mergeBattleRecords(stored, imported)
        assertEquals(BattleResult.WIN, merged.result)
        assertEquals(BattleStatus.COMPLETE, merged.status)
    }

    @Test
    fun `full-battle flag is only ever raised`() {
        val partial = stub().copy(fullBattle = false)
        val later = stub().copy(fullBattle = false)
        assertFalse(mergeBattleRecords(partial, later).fullBattle)
        assertTrue(mergeBattleRecords(partial, stub().copy(fullBattle = true)).fullBattle)
    }

    @Test
    fun `an empty incoming deck does not clear a captured one`() {
        val stored = stub()
        val imported = BattleRecord(
            battleUid = stored.battleUid,
            battleTime = stored.battleTime,
            source = BattleSource.NULLS_HISTORY,
        )
        val merged = mergeBattleRecords(stored, imported)
        assertEquals(1, merged.myDeck.size)
        assertEquals(1, merged.enemyDeck.size)
    }

    @Test
    fun `Nulls enrichment preserves native result evidence`() {
        val stored = stub().copy(
            result = BattleResult.WIN,
            status = BattleStatus.COMPLETE,
            source = BattleSource.NATIVE_RESULT,
            winnerOwner = 0,
            nativeResultRaw = 0,
            nativeResultValidated = true,
        )
        val imported = authoritativeEnrichment(stored.battleUid, stored.battleTime)
        val merged = mergeBattleRecords(stored, imported)
        assertEquals(0, merged.winnerOwner)
        assertEquals(0, merged.nativeResultRaw)
        assertTrue(merged.nativeResultValidated)
    }

    private fun authoritativeEnrichment(uid: String, time: Long) = BattleRecord(
        battleUid = uid,
        battleTime = time,
        battleId = "B8",
        result = BattleResult.WIN,
        status = BattleStatus.COMPLETE,
        source = BattleSource.NULLS_HISTORY,
    )
}

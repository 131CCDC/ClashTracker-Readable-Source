package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NullsHistoryImporterTest {

    @Test
    fun `a server battle id becomes the primary key`() {
        val entry = NullsBattleEntry(battleId = "ABC123", battleTimeMs = 1_700_000_000_000)
        assertEquals("nulls:ABC123", NullsHistoryImporter.battleUid(entry, 1_700_000_000_000))
    }

    @Test
    fun `the fallback key is stable across repeated imports`() {
        val entry = NullsBattleEntry(
            battleTimeMs = 1_700_000_000_000,
            enemyPlayerId = "200",
            myPlayerId = "100",
            myCards = listOf(1, 2, 3, 4, 5, 6, 7, 8),
            enemyCards = listOf(9, 10, 11, 12, 13, 14, 15, 16),
            myCrowns = 2,
            enemyCrowns = 1,
        )
        val first = NullsHistoryImporter.battleUid(entry, 1_700_000_000_000)
        val second = NullsHistoryImporter.battleUid(entry, 1_700_000_000_000)
        assertEquals(first, second)
        assertTrue(first.startsWith("nulls-fb:"))
    }

    @Test
    fun `the fallback key separates two different battles`() {
        val base = NullsBattleEntry(
            battleTimeMs = 1_700_000_000_000,
            enemyPlayerId = "200",
            myCards = listOf(1, 2, 3, 4, 5, 6, 7, 8),
            enemyCards = listOf(9, 10, 11, 12, 13, 14, 15, 16),
            myCrowns = 2,
            enemyCrowns = 1,
        )
        val differentTime = NullsHistoryImporter.battleUid(
            base.copy(battleTimeMs = 1_700_000_100_000),
            1_700_000_100_000,
        )
        val differentOpponent = NullsHistoryImporter.battleUid(base.copy(enemyPlayerId = "201"), 1_700_000_000_000)
        val differentDeck = NullsHistoryImporter.battleUid(base.copy(enemyCards = listOf(9, 10, 11, 12, 13, 14, 15, 17)), 1_700_000_000_000)
        val baseUid = NullsHistoryImporter.battleUid(base, 1_700_000_000_000)
        assertNotEquals(baseUid, differentTime)
        assertNotEquals(baseUid, differentOpponent)
        assertNotEquals(baseUid, differentDeck)
    }

    @Test
    fun `deck order does not change the fallback key`() {
        val one = NullsBattleEntry(
            battleTimeMs = 1_700_000_000_000,
            enemyCards = listOf(9, 10, 11, 12),
        )
        val two = one.copy(enemyCards = listOf(12, 11, 10, 9))
        assertEquals(
            NullsHistoryImporter.battleUid(one, 1_700_000_000_000),
            NullsHistoryImporter.battleUid(two, 1_700_000_000_000),
        )
    }

    @Test
    fun `a payload envelope and a bare array both parse`() {
        val entry = """{"battle_id":"B1","battle_time":1700000000,"result":"win","my_crowns":3,"enemy_crowns":1,"enemy_cards":[1,2,3]}"""
        val envelope = NullsHistoryImporter.parsePayload("""{"schema":"nulls-history.v1","battles":[$entry]}""")
        val bare = NullsHistoryImporter.parsePayload("[$entry]")
        assertEquals(1, envelope.size)
        assertEquals(1, bare.size)
        assertEquals("B1", envelope[0].battleId)
        assertEquals(BattleResult.WIN, envelope[0].result)
        assertEquals(3, envelope[0].myCrowns)
        assertEquals(listOf(1, 2, 3), envelope[0].enemyCards)
    }

    @Test
    fun `epoch seconds are widened to millis`() {
        val entries = NullsHistoryImporter.parsePayload("""[{"battle_time":1700000000}]""")
        assertEquals(1_700_000_000_000L, entries[0].battleTimeMs)
    }

    @Test
    fun `a malformed row is rejected instead of becoming formal history`() {
        val entries = NullsHistoryImporter.parsePayload("""[{"result":"win","my_crowns":2,"enemy_crowns":1}]""")
        val record = NullsHistoryImporter.toRecord(entries[0], null, 1_700_000_000_000)
        assertNull(record)
    }

    @Test
    fun `an entry with its own time keeps it`() {
        val entries = NullsHistoryImporter.parsePayload("""[{"battle_time":1700000000,"result":"win"}]""")
        val record = NullsHistoryImporter.toRecord(entries[0], null, 1_700_000_999_000)
        assertNull(record)
    }

    @Test
    fun `an imported row keeps the source and classifies the deck`() {
        val entry = NullsBattleEntry(
            battleId = "B2",
            battleTimeMs = 1_700_000_000_000,
            myPlayerId = "100",
            enemyPlayerId = "200",
            myCards = listOf(26000000, 26000001, 26000002, 26000003, 26000004, 26000005, 26000006, 26000007),
            enemyCards = listOf(26000032, 28000009, 26000010, 26000030, 28000011, 26000049, 26000011, 27000004),
            enemyEvos = listOf(26000032),
            result = BattleResult.WIN,
            myCrowns = 2,
            enemyCrowns = 0,
            durationSeconds = 180.0,
        )
        val record = NullsHistoryImporter.toRecord(entry)!!
        assertEquals(BattleSource.NULLS_HISTORY, record.source)
        assertEquals(BattleStatus.COMPLETE, record.status)
        assertEquals("Miner", record.enemyArchetype)
        assertEquals("Poison", record.enemyArchetypeSubtype)
        assertEquals(8, record.enemyDeck.size)
        assertTrue(record.enemyDeck.first { it.cardId == 26000032 }.isEvolution)
        assertEquals(180.0, record.durationSeconds!!, 1e-9)
    }

    @Test
    fun `an undecided import is kept out of formal history`() {
        val entry = NullsBattleEntry(battleTimeMs = 1_700_000_000_000, enemyCards = listOf(1, 2, 3))
        val record = NullsHistoryImporter.toRecord(entry)
        assertNull(record)
    }

    @Test
    fun `the documented live-capture gaps are named`() {
        assertTrue(NullsHistoryImporter.LIVE_CAPTURE_GAPS.contains("mode"))
        assertTrue(NullsHistoryImporter.LIVE_CAPTURE_GAPS.contains("player names"))
    }
}

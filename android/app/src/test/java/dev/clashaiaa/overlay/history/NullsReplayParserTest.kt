package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Null's Royale battle-log replay parser.
 *
 * The fixture was a captured replay payload; it is omitted from this source
 * snapshot because captured game and account data is not redistributed. Every
 * expectation below was read out of that capture, so a change in the parser
 * that breaks the format fails here instead of silently importing nothing.
 * Supply a compatible payload as `resources/nulls_replay.json` to run.
 */
class NullsReplayParserTest {

    private val raw: String by lazy {
        javaClass.getResourceAsStream("/nulls_replay.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    private val localAccount = 900000001L

    @Test
    fun `the fixture is recognised as a replay payload`() {
        assertTrue(NullsReplayParser.looksLikeReplay(raw))
    }

    @Test
    fun `both decks come out complete and in slot order`() {
        val entry = NullsReplayParser.parse(raw, localAccount)!!
        assertEquals(
            listOf(26000043, 26000011, 26000102, 26000080, 28000016, 26000063, 26000009, 28000012),
            entry.myCards,
        )
        assertEquals(
            listOf(27000013, 26000000, 26000062, 26000095, 27000004, 28000012, 28000011, 26000010),
            entry.enemyCards,
        )
    }

    @Test
    fun `the local side is chosen by account, not by position`() {
        val asLocal = NullsReplayParser.parse(raw, localAccount)!!
        assertEquals("900000001", asLocal.myPlayerId)
        assertEquals("900000002", asLocal.enemyPlayerId)

        // Claiming the other account flips the two sides and nothing else.
        val asOpponent = NullsReplayParser.parse(raw, 900000002L)!!
        assertEquals("900000002", asOpponent.myPlayerId)
        assertEquals(asLocal.myCards, asOpponent.enemyCards)
        assertEquals(asLocal.enemyCards, asOpponent.myCards)
    }

    @Test
    fun `player names and trophies are present, which the live capture cannot supply`() {
        val entry = NullsReplayParser.parse(raw, localAccount)!!
        assertEquals("LocalExample", entry.myPlayerName)
        assertEquals("EnemyExample", entry.enemyPlayerName)
        assertEquals(5876, entry.startingTrophies)
    }

    @Test
    fun `crowns are counted from the tower-destroy commands`() {
        val entry = NullsReplayParser.parse(raw, localAccount)!!
        assertEquals(2, entry.myCrowns)
        assertEquals(1, entry.enemyCrowns)
        assertEquals(BattleResult.WIN, entry.result)
    }

    @Test
    fun `duration comes from endTick at 50 ms per tick`() {
        val entry = NullsReplayParser.parse(raw, localAccount)!!
        assertEquals(161.25, entry.durationSeconds!!, 1e-9)
    }

    @Test
    fun `evolutions are the slots that carry an evolution level`() {
        val entry = NullsReplayParser.parse(raw, localAccount)!!
        assertEquals(listOf(26000043, 26000011, 26000102), entry.myEvos)
        assertEquals(listOf(27000013, 26000000, 26000062), entry.enemyEvos)
    }

    @Test
    fun `the replay seed is the stable identity`() {
        val entry = NullsReplayParser.parse(raw, localAccount)!!
        assertEquals("1790545405", entry.replayId)
        assertNull(entry.battleId)
    }

    @Test
    fun `the command timeline parses`() {
        val plays = NullsReplayParser.plays(raw)
        assertEquals(26, plays.size)
        assertEquals(Triple(212, 900000001L, 26000043), plays.first())
        assertEquals(Triple(3041, 900000001L, 26000011), plays.last())
        assertTrue(plays.all { it.first > 0 && it.third > 0 })
    }

    @Test
    fun `the importer routes a replay payload and keeps it idempotent`() {
        val entries = NullsHistoryImporter.parsePayload(raw, localAccount)
        assertEquals(1, entries.size)
        val record = NullsHistoryImporter.toRecord(entries[0], null, 1_700_000_000_000)!!
        assertEquals("nulls-replay:1790545405", record.battleUid)
        assertEquals(BattleSource.NULLS_HISTORY, record.source)
        assertEquals(BattleResult.WIN, record.result)
        assertEquals(8, record.myDeck.size)
        assertEquals(8, record.enemyDeck.size)
        // The replay carries no wall clock, so the fallback time is used and the
        // row is still decided rather than parked as incomplete.
        assertEquals(1_700_000_000_000L, record.battleTime)
        assertEquals(BattleStatus.COMPLETE, record.status)
    }

    @Test
    fun `a replay without a seed still produces a record`() {
        val withoutSeed = raw.replace("\"rndSeed\":1790545405", "\"rndSeed\":0")
        val entries = NullsHistoryImporter.parsePayload(withoutSeed, localAccount)
        assertEquals(1, entries.size)
        assertNull(entries[0].replayId)
        val record = NullsHistoryImporter.toRecord(entries[0], null, 1_700_000_000_000)!!
        assertTrue(record.battleUid.startsWith("nulls-fb:"))
    }

    @Test
    fun `garbage is refused rather than half-parsed`() {
        assertNull(NullsReplayParser.parse("not json", localAccount))
        assertNull(NullsReplayParser.parse("{}", localAccount))
        assertNotNull(NullsReplayParser.parse(raw, localAccount))
    }
}

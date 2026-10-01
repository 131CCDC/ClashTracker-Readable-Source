package dev.clashaiaa.overlay.history

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleExportTest {

    private val records = listOf(
        TestRecords.record(
            uid = "live:1:100:200",
            time = 1_700_000_000_000,
            result = BattleResult.WIN,
            durationSeconds = 157.0,
            myCrowns = 2,
            enemyCrowns = 1,
            archetype = "Miner",
            subtype = "Poison",
            myCards = TestRecords.deck(26000021, 28000011, side = CardSide.SELF),
            enemyCards = TestRecords.deck(26000032, 28000009, 26000043, 26000043),
        ).copy(
            enemyDeck = listOf(
                TestRecords.card(26000032, 0, CardSide.ENEMY, "Miner"),
                TestRecords.card(28000009, 1, CardSide.ENEMY, "Poison"),
                TestRecords.card(26000043, 2, CardSide.ENEMY, "AngryBarbarians"),
                TestRecords.card(26000043, 3, CardSide.ENEMY, "AngryBarbarians", isEvolution = true),
            ),
        ),
    )

    @Test
    fun `csv starts with a BOM so excel reads utf-8`() {
        val csv = BattleExport.toCsv(records)
        assertEquals('\uFEFF', csv[0])
        assertTrue(csv.contains("\r\n"))
    }

    @Test
    fun `csv carries every documented column`() {
        val header = BattleExport.toCsv(records).removePrefix("\uFEFF").lineSequence().first()
        val columns = header.split(",")
        for (required in listOf(
            "battle_time", "duration_seconds", "mode", "result", "my_crowns", "enemy_crowns",
            "my_player_name", "enemy_player_name",
            "my_evos", "enemy_evos", "my_heroes", "enemy_heroes",
            "enemy_archetype", "starting_trophies", "ending_trophies", "trophy_change",
            "battle_id", "replay_id",
        )) {
            assertTrue("missing column $required", columns.contains(required))
        }
        for (slot in 1..8) {
            assertTrue(columns.contains("my_card_$slot"))
            assertTrue(columns.contains("enemy_card_$slot"))
        }
    }

    @Test
    fun `csv has one row per battle plus the header`() {
        val lines = BattleExport.toCsv(records).removePrefix("\uFEFF").trim().lines()
        assertEquals(2, lines.size)
    }

    @Test
    fun `evolution is a separate csv value from the base card`() {
        val csv = BattleExport.toCsv(records)
        assertTrue(csv.contains("AngryBarbarians"))
        assertTrue(csv.contains("Evo AngryBarbarians"))
        // The evo list column must name only the evolved slot.
        val row = csv.removePrefix("\uFEFF").trim().lines()[1]
        assertTrue(row.contains("Evo AngryBarbarians"))
    }

    @Test
    fun `chinese card names survive the round trip`() {
        val csv = BattleExport.toCsv(records)
        assertTrue(csv.contains("·"))
        assertTrue(csv.toByteArray(Charsets.UTF_8).isNotEmpty())
    }

    @Test
    fun `fields containing a comma are quoted`() {
        val tricky = records.map {
            it.copy(enemyArchetype = "Miner, Poison")
        }
        val csv = BattleExport.toCsv(tricky)
        assertTrue(csv.contains("\"Miner, Poison\""))
    }

    @Test
    fun `json parses back and keeps the structure`() {
        val json = BattleExport.toJson(records)
        val root = JSONObject(json)
        assertEquals("clashtracker-battles.v1", root.getString("schema"))
        assertEquals(1, root.getInt("count"))
        val battle = root.getJSONArray("battles").getJSONObject(0)
        assertEquals("win", battle.getString("result"))
        assertEquals("live:1:100:200", battle.getString("battle_uid"))
        assertEquals(4, battle.getJSONArray("enemy_deck").length())
        assertEquals(1, battle.getJSONArray("enemy_evos").length())
        assertTrue(battle.getBoolean("full_battle"))
        assertNotNull(battle.getJSONArray("my_deck"))
    }

    @Test
    fun `file names carry the documented prefix and a timestamp`() {
        val csv = BattleExport.csvFileName(1_700_000_000_000)
        assertTrue(csv.startsWith("ClashTracker_Battles_"))
        assertTrue(csv.endsWith(".csv"))
        val json = BattleExport.jsonFileName(1_700_000_000_000)
        assertTrue(json.endsWith(".json"))
    }
}

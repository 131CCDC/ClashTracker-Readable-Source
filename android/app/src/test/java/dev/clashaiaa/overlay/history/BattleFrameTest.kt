package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame parser is exercised against a representative probe response, so a
 * probe upgrade that changes the wire shape fails here instead of silently
 * writing empty decks to the database.
 */
class BattleFrameTest {

    private val captured = """
    {
      "schema": "nulls-live.v3",
      "in_battle": true,
      "tick": 0,
      "monotonic_ms": 1234567,
      "battle_result": {"validated": true, "finalized": false, "world_result_raw": 0},
      "players": [
        {
          "owner": 0,
          "accountId": 900000004,
          "elixir": 6.0,
          "elixir_raw": 60000,
          "hand": [{"slot": 0, "card_id": 26000084, "name": "ElectroSpirit"}],
          "cycle": [26000010, 27000008],
          "deck": [27000006, 26000000, 26000001, 28000011, 26000010, 28000000, 26000084, 27000008],
          "card_runtime": [
            {"deck_slot": 0, "card_id": 27000006, "active_form": 0, "selected_cost": 4,
             "evolution_progress": 0, "variants": [{"data_id": 13000102, "form_code": 1, "cycle_required": 2}]},
            {"deck_slot": 1, "card_id": 26000000, "active_form": 2, "selected_cost": 3,
             "evolution_progress": 0, "variants": [{"data_id": 13000000, "form_code": 1, "cycle_required": 2},
                                                    {"data_id": 203000000, "form_code": 2, "cycle_required": null}]}
          ],
          "ability_runtime": []
        },
        {
          "owner": 1,
          "accountId": 900000003,
          "elixir": 3.5,
          "elixir_raw": 35000,
          "hand": [],
          "cycle": [],
          "deck": [26000032, 28000009],
          "card_runtime": [
            {"deck_slot": 0, "card_id": 26000032, "active_form": 1, "selected_cost": 3,
             "evolution_progress": 2, "variants": []}
          ],
          "ability_runtime": []
        }
      ],
      "entities": [
        {"id": 5000000, "owner": 0, "x": 9000, "y": 3000, "card_id": -1, "hp": 7728, "max_hp": 7728,
         "native_data_name": "KingTower"},
        {"id": 5000001, "owner": 0, "x": 3500, "y": 6500, "card_id": -1, "hp": 2400, "max_hp": 4858,
         "native_data_name": "PrincessTower"},
        {"id": 5000003, "owner": 1, "x": 9000, "y": 29000, "card_id": -1, "hp": 7728, "max_hp": 7728,
         "native_data_name": "KingTower"},
        {"id": 9000001, "owner": 1, "x": 9000, "y": 15000, "card_id": 26000021, "hp": 800, "max_hp": 1400,
         "native_data_name": "HogRider"}
      ],
      "crowns": [1, 0],
      "entities_complete": true,
      "capture_complete": true
    }
    """.trimIndent()

    @Test
    fun `a captured frame parses into both decks`() {
        val frame = BattleFrame.parse(captured)
        assertNotNull(frame)
        assertEquals(0, frame!!.tick)
        assertEquals(1234567L, frame.monotonicMs)
        assertFalse(frame.finalized)
        assertEquals(listOf(1, 0), frame.crowns)
        assertEquals(2, frame.players.size)
        assertEquals(900000004L, frame.player(0)!!.accountId)
        assertEquals(2, frame.player(1)!!.deck.size)
    }

    @Test
    fun `deck rows carry the native form flags`() {
        val frame = BattleFrame.parse(captured)!!
        val mine = frame.player(0)!!.deck
        assertEquals(27000006, mine[0].cardId)
        assertEquals(4, mine[0].selectedCost)
        assertTrue("Tesla lists an evolution variant", mine[0].hasEvolutionVariant)
        assertEquals(2, mine[1].activeForm)
        assertTrue(mine[1].hasHeroVariant)
        val enemy = frame.player(1)!!.deck
        assertEquals(1, enemy[0].activeForm)
        assertEquals(2, enemy[0].evolutionProgress)
    }

    @Test
    fun `towers are separated from units`() {
        val frame = BattleFrame.parse(captured)!!
        assertEquals(3, frame.towers.size)
        assertTrue(frame.towers.any { it.owner == 0 && it.isKing })
        assertTrue(frame.towers.any { it.owner == 1 && it.isKing })
        val princess = frame.towers.first { !it.isKing }
        assertEquals(2400, princess.hp)
        assertEquals(4858, princess.maxHp)
        assertEquals(2400.0 / 4858.0, princess.fraction!!, 1e-9)
    }

    @Test
    fun `an idle response parses to a non-battle frame`() {
        val frame = BattleFrame.parse("""{"in_battle":false}""")!!
        assertFalse(frame.inBattle)
        assertTrue(frame.players.isEmpty())
    }

    @Test
    fun `a stale response is flagged`() {
        val frame = BattleFrame.parse("""{"in_battle":false,"status":"stale"}""")!!
        assertTrue(frame.stale)
    }

    @Test
    fun `garbage is rejected instead of half-read`() {
        assertNull(BattleFrame.parse("not json at all"))
    }

    @Test
    fun `a frame missing battle_result does not crash`() {
        val frame = BattleFrame.parse("""{"in_battle":true,"tick":5,"players":[],"crowns":[]}""")!!
        assertFalse(frame.finalized)
        assertNull(frame.resultRaw)
        assertTrue(frame.crowns.isEmpty())
    }

    @Test
    fun `finalized and the raw result code are read when present`() {
        val frame = BattleFrame.parse(
            """{"in_battle":true,"tick":100,"battle_result":{"validated":true,"finalized":true,"world_result_raw":1},"players":[],"crowns":[2,1]}""",
        )!!
        assertTrue(frame.finalized)
        assertEquals(1, frame.resultRaw)
        assertEquals(listOf(2, 1), frame.crowns)
    }
}

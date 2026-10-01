package dev.clashaiaa.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeJsonTest {

    /** Trimmed but structurally identical to a recorded `nulls-live.v3` frame. */
    private val inBattleFrame = """
        {"schema":"nulls-live.v3","in_battle":true,"tick":4127,
         "battle_result":{"validated":true,"finalized":false,"world_result_raw":-1},
         "hook_diagnostics":{"release_calls":0,"release_caller":0,"release_result":0,"release_owner_id":0},
         "players":[
           {"owner":0,"accountId":8811223344,"elixir":4.60,"elixir_raw":46000,
            "hand":[{"slot":0,"card_id":26000021,"name":"HogRider"},
                    {"slot":1,"card_id":26000014,"name":"Musketeer"},
                    {"slot":2,"card_id":28000000,"name":"Cannon"},
                    {"slot":3,"card_id":26000010,"name":"Skeletons"}],
            "cycle":[26000030,27000000,26000038,27000011],
            "deck":[26000021,26000014,28000000,26000010,26000030,27000000,26000038,27000011]},
           {"owner":1,"accountId":9900556677,"elixir":7.35,"elixir_raw":73500,
            "hand":[{"slot":0,"card_id":26000016,"name":"Prince"},
                    {"slot":1,"card_id":26000015,"name":"BabyDragon"},
                    {"slot":2,"card_id":27000000,"name":"Fireball"},
                    {"slot":3,"card_id":0,"name":""}],
            "cycle":[],"deck":[26000016,26000015,27000000,0]}
         ],
         "entities":[],"entities_complete":true,"capture_complete":true,
         "local_owner":null,"elixir":4.60,"elixir_raw":46000,"crowns":[0,0]}
    """.trimIndent()

    @Test
    fun `parses opponent elixir and four hand slots`() {
        val state = ProbeJson.parse(inBattleFrame)
        assertTrue(state.inBattle)
        assertEquals(4127, state.tick)
        assertEquals(2, state.players.size)

        val opponent = state.opponent(localOwner = 0, localAccountId = 0L)
        assertNotNull(opponent)
        assertEquals(1, opponent!!.owner)
        assertEquals(7.35f, opponent.elixir, 0.001f)
        assertEquals(4, opponent.hand.size)
        assertEquals(listOf(26000016, 26000015, 27000000, 0), opponent.hand.map { it.cardId })
        assertEquals("Prince", opponent.hand[0].name)
        assertEquals(4, opponent.deck.size)
        assertEquals(emptyList<Int>(), opponent.cycle)
    }

    @Test
    fun `parses native cycle in published order`() {
        val state = ProbeJson.parse(inBattleFrame)
        val me = state.player(0)!!
        assertEquals(listOf(26000030, 27000000, 26000038, 27000011), me.cycle)
    }

    @Test
    fun `account id wins over the seat setting`() {
        val state = ProbeJson.parse(inBattleFrame)
        val opponent = state.opponent(localOwner = 0, localAccountId = 9900556677L)
        assertNotNull(opponent)
        assertEquals(0, opponent!!.owner)
        assertEquals(4.60f, opponent.elixir, 0.001f)
    }

    @Test
    fun `idle frame reports no battle and no players`() {
        val state = ProbeJson.parse("{\"in_battle\":false}\n")
        assertFalse(state.inBattle)
        assertTrue(state.players.isEmpty())
    }

    @Test
    fun `stale frame is flagged and carries no live data`() {
        val state = ProbeJson.parse("{\"in_battle\":false,\"status\":\"stale\"}\n")
        assertFalse(state.inBattle)
        assertTrue(state.stale)
    }

    @Test
    fun `missing hand entries become empty slots instead of crashing`() {
        val state = ProbeJson.parse(
            "{\"in_battle\":true,\"tick\":9,\"players\":[" +
                "{\"owner\":1,\"elixir\":3.0,\"hand\":[{\"slot\":3,\"card_id\":99}]}]}"
        )
        val opponent = state.opponent(localOwner = 0, localAccountId = 0L)
        assertNotNull(opponent)
        assertEquals(listOf(0, 0, 0, 99), opponent!!.hand.map { it.cardId })
        assertNull(opponent.hand[0].name)
    }

    @Test
    fun `parses the device-local input edges, ignoring non play-card commands`() {
        val state = ProbeJson.parse(localEvidenceFrame)
        assertTrue(state.localInputSupported)
        // Only command_type 86 (0x56, the play-card class) is a card play.
        assertEquals(listOf(100), state.localInputTicks)
    }

    @Test
    fun `a probe without the input observer is reported as unsupported`() {
        val unsupported = localEvidenceFrame
            .replace("\"hook_ready\":true", "\"hook_ready\":false")
        val state = ProbeJson.parse(unsupported)
        assertFalse(state.localInputSupported)
        assertTrue(state.localInputTicks.isEmpty())

        val absent = ProbeJson.parse(inBattleFrame)
        assertFalse(absent.localInputSupported)
        assertTrue(absent.localInputTicks.isEmpty())
    }

    @Test
    fun `parses entity ids and seats for the local-seat vote`() {
        val state = ProbeJson.parse(localEvidenceFrame)
        assertEquals(2, state.entities.size)
        assertEquals(listOf(7001, 7002), state.entities.map { it.id })
        assertEquals(listOf(1, 0), state.entities.map { it.owner })
        assertEquals(listOf(26000021, 26000014), state.entities.map { it.cardId })

        val noIds = ProbeJson.parse(
            "{\"in_battle\":true,\"tick\":9,\"players\":[],\"entities\":[" +
                "{\"id\":0,\"owner\":0,\"card_id\":1},{\"owner\":1,\"card_id\":2}]}"
        )
        assertTrue(noIds.entities.isEmpty())
    }

    private val localEvidenceFrame = """
        {"schema":"nulls-live.v3","in_battle":true,"tick":300,
         "players":[
           {"owner":0,"accountId":11,"elixir":4.5,"hand":[]},
           {"owner":1,"accountId":22,"elixir":6.0,"hand":[]}
         ],
         "entities":[
           {"id":7001,"owner":1,"x":9000,"y":21000,"card_id":26000021},
           {"id":7002,"owner":0,"x":9000,"y":3000,"card_id":26000014}
         ],
         "client_input_runtime":{"hook_ready":true,"events":[
           {"sequence":41,"tick":100,"input_tick":99,"command_type":86,"monotonic_ms":1},
           {"sequence":42,"tick":260,"input_tick":259,"command_type":19,"monotonic_ms":2}
         ]},
         "command_queue_runtime":{"validated":true,"commands":[]}}
    """.trimIndent()

    /** Trimmed from a recorded frame: native roles, costs and the ability row. */
    private val runtimeFrame = """
        {"schema":"nulls-live.v3","in_battle":true,"tick":900,
         "players":[
           {"owner":0,"accountId":11,"elixir":4.5,"hand":[]},
           {"owner":1,"accountId":22,"elixir":6.0,
            "hand":[{"slot":0,"card_id":26000030,"name":"IceGolemite"},
                    {"slot":1,"card_id":26000038,"name":"Skeletons"}],
            "cycle":[26000030],
            "deck":[26000030,26000038,26000021,26000014,28000000,26000010,27000000,27000011],
            "card_runtime":[
              {"deck_slot":0,"card_id":26000030,"variants_known":true,"active_form":2,
               "selected_cost":2,"role_active":null,"evolution_progress":0,
               "variants":[{"data_id":13000030,"form_code":1,"cycle_required":2}]},
              {"deck_slot":1,"card_id":26000038,"variants_known":true,"active_form":0,
               "selected_cost":1,"role_active":null,"evolution_progress":1,
               "variants":[{"data_id":13000038,"form_code":1,"cycle_required":2}]}
            ],
            "ability_runtime":[
              {"controller_slot":1,"known":true,"empty":false,"ability_name":"IceGolemiteHero_Ability",
               "button_state":2,"cooldown_ms":0,"configured_cooldown_ms":0,"charges":-1,
               "max_charges":1,"members":[]},
              {"controller_slot":2,"known":true,"empty":true,"ability_name":"","button_state":0,
               "cooldown_ms":0,"configured_cooldown_ms":0,"charges":0,"max_charges":0,"members":[]}
            ]}
         ],
         "entities":[]}
    """.trimIndent()

    @Test
    fun `parses native roles, attested costs and the hero ability row`() {
        val opponent = ProbeJson.parse(runtimeFrame).player(1)!!
        assertEquals(2, opponent.hand[0].cost)
        assertEquals(1, opponent.hand[1].cost)
        assertTrue(opponent.hand[0].role?.isHero == true)
        assertEquals(2, opponent.hand[1].role?.evolutionRequired)
        assertEquals(1, opponent.hand[1].role?.evolutionProgress)
        assertEquals("EVO 1/2", CardBadges.of(opponent.hand[1].role, opponent.hero)?.evoLong)
        assertEquals("★ READY", CardBadges.of(opponent.hand[0].role, opponent.hero)?.hero)
        assertEquals("IceGolemiteHero_Ability", opponent.hero?.name)
        assertEquals(true, opponent.hero?.available)
    }

    @Test
    fun `a malformed runtime block degrades instead of throwing`() {
        val state = ProbeJson.parse(
            "{\"in_battle\":true,\"tick\":9,\"players\":[{\"owner\":1,\"elixir\":3.0," +
                "\"hand\":[{\"slot\":0,\"card_id\":99}]," +
                "\"card_runtime\":[{\"card_id\":\"x\"},{\"card_id\":99,\"active_form\":99," +
                "\"selected_cost\":99,\"evolution_progress\":-4}]," +
                "\"ability_runtime\":[{\"known\":true,\"empty\":false,\"ability_name\":\"\"}]}]}"
        )
        val player = state.player(1)!!
        assertEquals(null, player.hand[0].cost)
        assertEquals(0, player.hand[0].role?.activeForm)
        assertEquals(null, player.hero)
    }
}

package dev.clashaiaa.overlay

import org.json.JSONObject

/**
 * Parser for the existing Clashaiaa native probe JSON (`nulls-live.v3`).
 *
 * The wire shape is produced by `probe/nulls_probe.cpp`; nothing here
 * recomputes game state. Missing or malformed fields degrade to "unknown"
 * instead of throwing, so a probe upgrade can never crash the client.
 */
object ProbeJson {

    /** Port compiled into the Clashaiaa probe (`kPort` in nulls_probe.cpp). */
    const val DEFAULT_PORT = 26888

    private const val TEAM_SIZE = 2
    private const val HAND_SLOTS = 4

    /**
     * `kPlayCardType` in `probe/command_queue.inc`: the attested "play a card
     * at a position" command class. Only those edges are local card plays.
     */
    private const val PLAY_CARD_COMMAND_TYPE = 0x56

    /** `available` in `tracker/probe_client.py`: the bridge's usable-button rule. */
    private val AVAILABLE_BUTTON_STATES = setOf(2, 4)

    fun parse(raw: String): BattleState {
        val root = JSONObject(raw)
        val inBattle = root.optBoolean("in_battle", false)
        val stale = root.optString("status", "") == "stale"
        if (!inBattle) {
            return BattleState(inBattle = false, stale = stale, tick = -1, players = emptyList())
        }

        val players = ArrayList<ProbePlayer>(TEAM_SIZE)
        val array = root.optJSONArray("players")
        if (array != null) {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                players += parsePlayer(entry, i)
            }
        }
        return BattleState(
            inBattle = true,
            stale = stale,
            tick = root.optInt("tick", -1),
            players = players,
            localInputTicks = parseLocalInputTicks(root),
            localInputSupported = localInputSupported(root),
            entities = parseEntities(root),
        )
    }

    /**
     * `hook_ready` is a static result of the probe's own inline-hook install,
     * so it distinguishes "this build cannot report local input" (and the client
     * has to fall back to the seat setting) from "this battle has not produced
     * a local card play yet" (and the client must show nothing rather than
     * guess a seat).
     */
    private fun localInputSupported(root: JSONObject): Boolean {
        val runtime = root.optJSONObject("client_input_runtime") ?: return false
        return runtime.optBoolean("hook_ready", false)
    }

    private fun parseLocalInputTicks(root: JSONObject): List<Int> {
        val runtime = root.optJSONObject("client_input_runtime") ?: return emptyList()
        // Without a working observer the array is empty anyway; refusing it here
        // keeps "no edges" and "no support" from ever being confused.
        if (!runtime.optBoolean("hook_ready", false)) return emptyList()
        val rows = runtime.optJSONArray("events") ?: return emptyList()
        val ticks = ArrayList<Int>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optInt("command_type", -1) != PLAY_CARD_COMMAND_TYPE) continue
            val tick = row.optInt("tick", -1)
            if (tick >= 0) ticks += tick
        }
        return ticks
    }

    private fun parseEntities(root: JSONObject): List<ProbeEntity> {
        val rows = root.optJSONArray("entities") ?: return emptyList()
        val entities = ArrayList<ProbeEntity>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optInt("id", 0)
            if (id <= 0) continue
            entities += ProbeEntity(
                id = id,
                owner = row.optInt("owner", -1),
                cardId = row.optInt("card_id", 0),
            )
        }
        return entities
    }

    private fun parsePlayer(entry: JSONObject, fallbackOwner: Int): ProbePlayer {
        val roles = parseRoles(entry)
        val costs = parseCosts(entry)
        val slots = arrayOfNulls<HandCard>(HAND_SLOTS)
        val hand = entry.optJSONArray("hand")
        if (hand != null) {
            for (i in 0 until hand.length()) {
                val card = hand.optJSONObject(i) ?: continue
                val slot = card.optInt("slot", -1)
                if (slot < 0 || slot >= HAND_SLOTS) continue
                val name = card.optString("name", "").takeIf { it.isNotBlank() }
                val cardId = card.optInt("card_id", 0)
                slots[slot] = HandCard(
                    slot = slot,
                    cardId = cardId,
                    name = name,
                    cost = costs[cardId],
                    role = roles[cardId],
                )
            }
        }
        val orderedHand = (0 until HAND_SLOTS).map { slots[it] ?: HandCard(it, 0, null) }

        val deck = ArrayList<Int>()
        val deckArray = entry.optJSONArray("deck")
        if (deckArray != null) {
            for (i in 0 until deckArray.length()) deck += deckArray.optInt(i, 0)
        }

        val cycle = ArrayList<Int>()
        val cycleArray = entry.optJSONArray("cycle")
        if (cycleArray != null) {
            for (i in 0 until cycleArray.length()) {
                val cardId = cycleArray.optInt(i, 0)
                if (cardId > 0) cycle += cardId
            }
        }

        val elixir = entry.optDouble("elixir", 0.0).toFloat().coerceIn(0f, 10f)
        return ProbePlayer(
            owner = entry.optInt("owner", fallbackOwner),
            accountId = entry.optLong("accountId", 0L),
            elixir = elixir,
            elixirRaw = entry.optInt("elixir_raw", (elixir * 10000).toInt()),
            hand = orderedHand,
            deck = deck,
            cycle = cycle,
            roles = roles,
            hero = parseHeroAbility(entry),
        )
    }

    /**
     * `card_runtime[]`: one row per deck slot with the native evolution/hero
     * role. Keyed by card id, which is how the hand and the cycle are matched to
     * their marks (mirrors `tracker/probe_client.py`).
     */
    private fun parseRoles(entry: JSONObject): Map<Int, CardRole> {
        val rows = entry.optJSONArray("card_runtime") ?: return emptyMap()
        val roles = HashMap<Int, CardRole>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val cardId = row.optInt("card_id", 0)
            if (cardId <= 0) continue
            val form = row.optInt("active_form", 0).takeIf { it in 0..15 } ?: 0
            var required: Int? = null
            val variants = row.optJSONArray("variants")
            if (variants != null) {
                for (variantIndex in 0 until variants.length()) {
                    val variant = variants.optJSONObject(variantIndex) ?: continue
                    if (variant.optInt("form_code", -1) != 1) continue
                    val cycle = variant.optInt("cycle_required", -1)
                    if (cycle > 0 && cycle < 100) required = cycle
                    break
                }
            }
            val progress = row.optInt("evolution_progress", -1).takeIf { it >= 0 }
            roles[cardId] = CardRole(
                activeForm = form,
                evolutionRequired = required,
                evolutionProgress = progress,
            )
        }
        return roles
    }

    private fun parseCosts(entry: JSONObject): Map<Int, Int> {
        val rows = entry.optJSONArray("card_runtime") ?: return emptyMap()
        val costs = HashMap<Int, Int>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val cardId = row.optInt("card_id", 0)
            val cost = row.optInt("selected_cost", -1)
            if (cardId > 0 && cost in 0..15) costs[cardId] = cost
        }
        return costs
    }

    /** The first attested, non-empty `ability_runtime` row, as the HUD needs one. */
    private fun parseHeroAbility(entry: JSONObject): HeroAbility? {
        val rows = entry.optJSONArray("ability_runtime") ?: return null
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (!row.optBoolean("known", false) || row.optBoolean("empty", true)) continue
            val name = row.optString("ability_name", "")
            if (name.isBlank()) continue
            val cooldownMs = row.optInt("cooldown_ms", -1)
            val charges = row.optInt("charges", -2).takeIf { it >= 0 }
            val maxCharges = row.optInt("max_charges", -2).takeIf { it > 0 }
            return HeroAbility(
                name = name,
                available = row.optInt("button_state", -1) in AVAILABLE_BUTTON_STATES,
                cooldownSeconds = if (cooldownMs > 0) maxOf(1, (cooldownMs + 500) / 1000) else null,
                charges = charges,
                maxCharges = maxCharges,
            )
        }
        return null
    }
}

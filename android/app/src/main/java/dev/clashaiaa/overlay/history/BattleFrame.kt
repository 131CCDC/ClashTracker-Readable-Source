package dev.clashaiaa.overlay.history

import org.json.JSONObject

/** One deck slot as the probe reports it for the current battle. */
data class FrameCard(
    val slot: Int,
    val cardId: Int,
    val activeForm: Int,
    val evolutionProgress: Int,
    val selectedCost: Int?,
    /** The slot lists a form-1 variant in the game data (an evolution exists). */
    val hasEvolutionVariant: Boolean,
    /** The slot lists a form-2 variant in the game data (a hero form exists). */
    val hasHeroVariant: Boolean,
)

data class FramePlayer(
    val owner: Int,
    val accountId: Long,
    val elixir: Float,
    val deck: List<FrameCard>,
)

/** One tower, reduced to what the overtime tiebreak needs. */
data class FrameTower(
    val owner: Int,
    val isKing: Boolean,
    val hp: Int,
    val maxHp: Int,
) {
    /** Remaining fraction, `null` when the tower has no attested max HP. */
    val fraction: Double? get() = if (maxHp > 0) hp.toDouble() / maxHp else null
}

/**
 * The subset of one `nulls-live.v3` frame the history recorder needs.
 *
 * Parsed here rather than through the HUD's `ProbeJson` so a change to what
 * the HUD reads can never change what gets written to the database, and the
 * other way round. Every field is optional: a probe that stops publishing one
 * degrades that column to null instead of dropping the battle.
 */
data class BattleFrame(
    val inBattle: Boolean,
    val stale: Boolean,
    val tick: Int,
    val monotonicMs: Long?,
    val finalized: Boolean,
    val resultRaw: Int?,
    /** `[owner0, owner1]` crowns as the probe computed them from the towers. */
    val crowns: List<Int>,
    val players: List<FramePlayer>,
    val towers: List<FrameTower>,
    /** Native validation bit; finalization is authoritative only when both are true. */
    val resultValidated: Boolean = false,
) {
    fun player(owner: Int): FramePlayer? = players.firstOrNull { it.owner == owner }

    companion object {
        /** Parse one probe response line; null when it is not a usable frame. */
        fun parse(raw: String): BattleFrame? = try {
            val root = JSONObject(raw)
            val inBattle = root.optBoolean("in_battle", false)
            if (!inBattle) {
                BattleFrame(
                    inBattle = false,
                    stale = root.optString("status", "") == "stale",
                    tick = root.optInt("tick", -1),
                    monotonicMs = null,
                    finalized = false,
                    resultRaw = null,
                    crowns = emptyList(),
                    players = emptyList(),
                    towers = emptyList(),
                    resultValidated = false,
                )
            } else {
                val battleResult = root.optJSONObject("battle_result")
                BattleFrame(
                    inBattle = true,
                    stale = root.optString("status", "") == "stale",
                    tick = root.optInt("tick", -1),
                    monotonicMs = root.optLong("monotonic_ms", -1L).takeIf { it >= 0 },
                    finalized = battleResult?.optBoolean("finalized", false) == true,
                    resultRaw = battleResult?.optInt("world_result_raw", Int.MIN_VALUE)
                        ?.takeIf { it != Int.MIN_VALUE },
                    crowns = parseCrowns(root),
                    players = parsePlayers(root),
                    towers = parseTowers(root),
                    resultValidated = battleResult?.optBoolean("validated", false) == true,
                )
            }
        } catch (ignored: Exception) {
            null
        }

        private fun parseCrowns(root: JSONObject): List<Int> {
            val array = root.optJSONArray("crowns") ?: return emptyList()
            val crowns = ArrayList<Int>(array.length())
            for (index in 0 until array.length()) crowns += array.optInt(index, 0)
            return crowns
        }

        private fun parsePlayers(root: JSONObject): List<FramePlayer> {
            val array = root.optJSONArray("players") ?: return emptyList()
            val players = ArrayList<FramePlayer>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                players += FramePlayer(
                    owner = entry.optInt("owner", index),
                    accountId = entry.optLong("accountId", 0L),
                    elixir = entry.optDouble("elixir", 0.0).toFloat(),
                    deck = parseDeck(entry),
                )
            }
            return players
        }

        /**
         * One row per deck slot.
         *
         * `deck[]` is the authoritative slot list and `card_runtime[]` only
         * enriches it: the probe publishes one runtime row per slot in practice,
         * but a truncated runtime array must never cost the record a card, so
         * the deck drives the slots and a missing runtime row simply leaves the
         * form flags at their defaults.
         *
         * `variants[]` is the game data's form list, not the loadout: in the
         * live corpus a deck whose owner can equip at most two evolutions listed
         * five form-1 variants, so presence alone cannot mean "equipped". The
         * flags here only record that a form *exists*; the recorder decides
         * "this battle used it" from the observed `active_form` /
         * `evolution_progress`, which are loadout state.
         */
        private fun parseDeck(entry: JSONObject): List<FrameCard> {
            val runtimeBySlot = HashMap<Int, JSONObject>()
            val runtime = entry.optJSONArray("card_runtime")
            if (runtime != null) {
                for (index in 0 until runtime.length()) {
                    val row = runtime.optJSONObject(index) ?: continue
                    runtimeBySlot[row.optInt("deck_slot", index)] = row
                }
            }

            val slots = LinkedHashMap<Int, FrameCard>()
            val deck = entry.optJSONArray("deck")
            if (deck != null) {
                for (slot in 0 until deck.length()) {
                    val cardId = deck.optInt(slot, 0)
                    if (cardId <= 0) continue
                    slots[slot] = frameCard(slot, cardId, runtimeBySlot[slot])
                }
            }
            // A runtime row for a slot the deck array did not name is still a
            // card this side played; keep it rather than dropping it.
            for ((slot, row) in runtimeBySlot) {
                if (slots.containsKey(slot)) continue
                val cardId = row.optInt("card_id", 0)
                if (cardId <= 0) continue
                slots[slot] = frameCard(slot, cardId, row)
            }
            return slots.values.sortedBy { it.slot }
        }

        private fun frameCard(slot: Int, cardId: Int, runtime: JSONObject?): FrameCard {
            var hasEvolution = false
            var hasHero = false
            val variants = runtime?.optJSONArray("variants")
            if (variants != null) {
                for (variantIndex in 0 until variants.length()) {
                    val variant = variants.optJSONObject(variantIndex) ?: continue
                    when (variant.optInt("form_code", -1)) {
                        1 -> hasEvolution = true
                        2 -> hasHero = true
                    }
                }
            }
            val cost = runtime?.optInt("selected_cost", -1) ?: -1
            return FrameCard(
                slot = slot,
                cardId = cardId,
                activeForm = runtime?.optInt("active_form", 0) ?: 0,
                evolutionProgress = runtime?.optInt("evolution_progress", 0) ?: 0,
                selectedCost = cost.takeIf { it in 0..15 },
                hasEvolutionVariant = hasEvolution,
                hasHeroVariant = hasHero,
            )
        }

        private fun parseTowers(root: JSONObject): List<FrameTower> {
            val array = root.optJSONArray("entities") ?: return emptyList()
            val towers = ArrayList<FrameTower>(8)
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                if (row.optInt("card_id", 0) != -1) continue
                val name = row.optString("native_data_name", "")
                val isKing = name.contains("King")
                if (!isKing && !name.contains("Tower") && !name.contains("Cannoneer") &&
                    !name.contains("Duchess") && !name.contains("Chef")
                ) {
                    continue
                }
                towers += FrameTower(
                    owner = row.optInt("owner", -1),
                    isKing = isKing,
                    hp = row.optInt("hp", 0),
                    maxHp = row.optInt("max_hp", 0),
                )
            }
            return towers
        }
    }
}

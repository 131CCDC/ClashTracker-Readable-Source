package dev.clashaiaa.overlay.history

import android.content.Context
import org.json.JSONObject

/**
 * Turns a deck slot into a named [BattleCard].
 *
 * An interface so the recorder has no Android dependency and every rule about
 * how a slot is labelled is testable on the JVM without a device.
 */
interface CardNamer {
    fun card(
        side: CardSide,
        slot: Int,
        cardId: Int,
        isEvolution: Boolean,
        isHero: Boolean,
        cost: Int?,
    ): BattleCard
}

/**
 * Card id -> English internal name and Chinese display name.
 *
 * The table is the same generated `assets/cards/cards.json` the HUD already
 * ships, so nothing is downloaded and the names cannot drift between the HUD
 * and the history module.
 *
 * The generator's `zh_cn` column is only trustworthy for base cards: 69 of the
 * 109 evolution rows in the shipped table carry a placeholder Chinese name.
 * Evolutions therefore take their Chinese label from their *base* card
 * (`form_of`), which is generated from the game's own text table and is
 * correct, and get the `·进化` suffix added on top.
 */
class CardNames(context: Context) : CardNamer {

    private class Entry(
        val id: Int,
        val english: String,
        val zh: String,
        val kind: String,
        val base: String,
    )

    private val byId: Map<Int, Entry> by lazy { load(context) }

    /** English internal name, e.g. `MegaKnight`. */
    fun english(cardId: Int): String = byId[cardId]?.english ?: "Unknown($cardId)"

    /** Chinese display name for a base card, or the derived evolution label. */
    fun chinese(cardId: Int): String {
        val entry = byId[cardId] ?: return "未知($cardId)"
        if (entry.kind == "evolution") {
            val baseEntry = byId.values.firstOrNull { it.english == entry.base }
            val baseZh = baseEntry?.zh
            return if (baseZh.isNullOrBlank()) entry.english else "$baseZh·进化"
        }
        return entry.zh
    }

    fun elixir(cardId: Int): Int? = elixirById[cardId]

    private val elixirById: Map<Int, Int> by lazy {
        buildMap {
            for (entry in byId.values) {
                val cost = costs[entry.id]
                if (cost != null && cost in 0..15) put(entry.id, cost)
            }
        }
    }

    private var costs: Map<Int, Int> = emptyMap()

    /** Names for one deck slot, with the native variant flags applied. */
    override fun card(
        side: CardSide,
        slot: Int,
        cardId: Int,
        isEvolution: Boolean,
        isHero: Boolean,
        cost: Int?,
    ): BattleCard = BattleCard(
        side = side,
        slot = slot,
        cardId = cardId,
        name = english(cardId),
        nameZh = chinese(cardId),
        isEvolution = isEvolution,
        isHero = isHero,
        cost = cost,
    )

    private fun load(context: Context): Map<Int, Entry> = runCatching {
        val raw = context.assets.open("cards.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(raw)
        val rows = root.optJSONObject("by_id") ?: return@runCatching emptyMap()
        val table = HashMap<Int, Entry>(rows.length())
        val costTable = HashMap<Int, Int>(rows.length())
        val keys = rows.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toIntOrNull() ?: continue
            val row = rows.optJSONObject(key) ?: continue
            val internal = row.optString("internal_name", "").trim()
            val zh = row.optString("zh_cn", "").trim()
            val kind = row.optString("kind", "").trim()
            val base = row.optString("form_of", "").trim()
            val cost = row.optInt("elixir", -1)
            if (cost in 0..15) costTable[id] = cost
            table[id] = Entry(
                id = id,
                english = internal.ifBlank { "Unknown($id)" },
                zh = zh.ifBlank { internal.ifBlank { "未知($id)" } },
                kind = kind,
                base = base,
            )
        }
        costs = costTable
        table
    }.getOrDefault(emptyMap())
}

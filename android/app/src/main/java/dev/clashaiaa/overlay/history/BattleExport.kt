package dev.clashaiaa.overlay.history

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * CSV and JSON writers for the battle history.
 *
 * Both are pure string builders, so the exact bytes the tablet writes are the
 * exact bytes a test can assert on. The CSV is written UTF-8 **with a BOM**:
 * without it Excel on a Chinese Windows install reads the file as GBK and the
 * card names come out as mojibake, which is the one failure this export exists
 * to avoid.
 */
object BattleExport {

    private const val BOM = "\uFEFF"

    fun fileName(extension: String, nowMs: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return "ClashTracker_Battles_${format.format(Date(nowMs))}.$extension"
    }

    /** `ClashTracker_Battles_YYYYMMDD_HHMMSS.csv` */
    fun csvFileName(nowMs: Long = System.currentTimeMillis()): String = fileName("csv", nowMs)

    fun jsonFileName(nowMs: Long = System.currentTimeMillis()): String = fileName("json", nowMs)

    // ---------------------------------------------------------------- CSV --

    private val CSV_HEADER = buildList {
        add("battle_time")
        add("duration_seconds")
        add("mode")
        add("result")
        add("my_crowns")
        add("enemy_crowns")
        add("my_player_name")
        add("enemy_player_name")
        for (slot in 1..8) add("my_card_$slot")
        for (slot in 1..8) add("enemy_card_$slot")
        add("my_evos")
        add("enemy_evos")
        add("my_heroes")
        add("enemy_heroes")
        add("enemy_archetype")
        add("starting_trophies")
        add("ending_trophies")
        add("trophy_change")
        add("battle_id")
        add("replay_id")
        // Additive columns; the block above is the documented contract.
        add("battle_uid")
        add("status")
        add("source")
        add("battle_time_iso")
        add("enemy_archetype_subtype")
        add("enemy_archetype_confidence")
        add("my_player_id")
        add("enemy_player_id")
        add("identity_source")
        add("full_battle")
        for (slot in 1..8) add("my_card_${slot}_zh")
        for (slot in 1..8) add("enemy_card_${slot}_zh")
    }

    /** UTF-8 CSV with a BOM and CRLF rows, newest battle last. */
    fun toCsv(records: List<BattleRecord>): String {
        val builder = StringBuilder(BOM)
        builder.append(CSV_HEADER.joinToString(","))
        builder.append("\r\n")
        for (record in records.sortedBy { it.battleTime }) {
            val row = ArrayList<String>(CSV_HEADER.size)
            row += record.battleTime.toString()
            row += record.durationSeconds?.let { formatDecimal(it) } ?: ""
            row += record.mode ?: ""
            row += record.result.wire
            row += record.myCrowns?.toString() ?: ""
            row += record.enemyCrowns?.toString() ?: ""
            row += record.myPlayerName ?: ""
            row += record.enemyPlayerName ?: ""
            for (slot in 0 until 8) row += record.cardLabel(CardSide.SELF, slot, zh = false)
            for (slot in 0 until 8) row += record.cardLabel(CardSide.ENEMY, slot, zh = false)
            row += record.flaggedCards(CardSide.SELF) { it.isEvolution }
            row += record.flaggedCards(CardSide.ENEMY) { it.isEvolution }
            row += record.flaggedCards(CardSide.SELF) { it.isHero }
            row += record.flaggedCards(CardSide.ENEMY) { it.isHero }
            row += record.enemyArchetype ?: ""
            row += record.startingTrophies?.toString() ?: ""
            row += record.endingTrophies?.toString() ?: ""
            row += record.trophyChange?.toString() ?: ""
            row += record.battleId ?: ""
            row += record.replayId ?: ""
            row += record.battleUid
            row += record.status.wire
            row += record.source.wire
            row += isoTime(record.battleTime)
            row += record.enemyArchetypeSubtype ?: ""
            row += record.enemyArchetypeConfidence?.let { formatDecimal(it) } ?: ""
            row += record.myPlayerId ?: ""
            row += record.enemyPlayerId ?: ""
            row += record.identitySource ?: ""
            row += if (record.fullBattle) "1" else "0"
            for (slot in 0 until 8) row += record.cardLabel(CardSide.SELF, slot, zh = true)
            for (slot in 0 until 8) row += record.cardLabel(CardSide.ENEMY, slot, zh = true)
            builder.append(row.joinToString(",") { escape(it) })
            builder.append("\r\n")
        }
        return builder.toString()
    }

    // --------------------------------------------------------------- JSON --

    /** Full structured export; the `raw` object is the frame-level evidence when present. */
    fun toJson(records: List<BattleRecord>): String {
        val root = JSONObject()
        root.put("schema", "clashtracker-battles.v1")
        root.put("exported_at", System.currentTimeMillis())
        root.put("count", records.size)
        val array = JSONArray()
        for (record in records.sortedBy { it.battleTime }) array.put(recordJson(record))
        root.put("battles", array)
        return root.toString(2)
    }

    private fun recordJson(record: BattleRecord): JSONObject = JSONObject().apply {
        put("battle_uid", record.battleUid)
        put("battle_id", record.battleId)
        put("replay_id", record.replayId)
        put("battle_time", record.battleTime)
        put("battle_time_iso", isoTime(record.battleTime))
        put("start_time", record.startTime)
        put("end_time", record.endTime)
        put("duration_ticks", record.durationTicks)
        put("duration_seconds", record.durationSeconds)
        put("mode", record.mode)
        put("arena", record.arena)
        put("my_player_id", record.myPlayerId)
        put("my_player_name", record.myPlayerName)
        put("enemy_player_id", record.enemyPlayerId)
        put("enemy_player_name", record.enemyPlayerName)
        put("my_crowns", record.myCrowns)
        put("enemy_crowns", record.enemyCrowns)
        put("result", record.result.wire)
        put("status", record.status.wire)
        put("source", record.source.wire)
        put("starting_trophies", record.startingTrophies)
        put("ending_trophies", record.endingTrophies)
        put("trophy_change", record.trophyChange)
        put("my_deck", deckJson(record.myDeck))
        put("enemy_deck", deckJson(record.enemyDeck))
        put("my_evos", flagJson(record.myDeck) { it.isEvolution })
        put("enemy_evos", flagJson(record.enemyDeck) { it.isEvolution })
        put("my_heroes", flagJson(record.myDeck) { it.isHero })
        put("enemy_heroes", flagJson(record.enemyDeck) { it.isHero })
        put("enemy_archetype", record.enemyArchetype)
        put("enemy_archetype_subtype", record.enemyArchetypeSubtype)
        put("enemy_archetype_confidence", record.enemyArchetypeConfidence)
        put("identity_source", record.identitySource)
        put("first_tick", record.firstTick)
        put("last_tick", record.lastTick)
        put("full_battle", record.fullBattle)
        record.rawJson?.let { raw ->
            put("raw_probe_frame", runCatching { JSONObject(raw) }.getOrNull())
        }
    }

    private fun deckJson(cards: List<BattleCard>): JSONArray {
        val array = JSONArray()
        for (card in cards.sortedBy { it.slot }) {
            array.put(
                JSONObject().apply {
                    put("slot", card.slot)
                    put("card_id", card.cardId)
                    put("name", card.name)
                    put("name_zh", card.nameZh)
                    put("is_evolution", card.isEvolution)
                    put("is_hero", card.isHero)
                    put("cost", card.cost)
                },
            )
        }
        return array
    }

    private fun flagJson(cards: List<BattleCard>, predicate: (BattleCard) -> Boolean): JSONArray {
        val array = JSONArray()
        for (card in cards.sortedBy { it.slot }) if (predicate(card)) array.put(card.cardId)
        return array
    }

    // -------------------------------------------------------------- utils --

    private fun BattleRecord.cardLabel(side: CardSide, slot: Int, zh: Boolean): String {
        val card = cards(side).firstOrNull { it.slot == slot } ?: return ""
        return if (zh) card.labelZh else card.label
    }

    private fun BattleRecord.flaggedCards(
        side: CardSide,
        predicate: (BattleCard) -> Boolean,
    ): String = cards(side)
        .filter(predicate)
        .sortedBy { it.slot }
        .joinToString("|") { it.label }

    private fun escape(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun formatDecimal(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    private fun isoTime(epochMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(epochMs))
    }
}

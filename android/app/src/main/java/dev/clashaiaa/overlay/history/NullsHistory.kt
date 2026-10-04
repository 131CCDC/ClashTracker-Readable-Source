package dev.clashaiaa.overlay.history

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One battle as Null's own battle-log interface reports it.
 *
 * Every field is optional on purpose: the point of the importer is to keep
 * whatever the server still holds, not to reject a row because one column the
 * interface does not publish is missing.
 */
data class NullsBattleEntry(
    val battleId: String? = null,
    val replayId: String? = null,
    val battleTimeMs: Long? = null,
    val mode: String? = null,
    val arena: String? = null,
    val myPlayerId: String? = null,
    val myPlayerName: String? = null,
    val enemyPlayerId: String? = null,
    val enemyPlayerName: String? = null,
    val myCards: List<Int> = emptyList(),
    val enemyCards: List<Int> = emptyList(),
    val myEvos: List<Int> = emptyList(),
    val enemyEvos: List<Int> = emptyList(),
    val myHeroes: List<Int> = emptyList(),
    val enemyHeroes: List<Int> = emptyList(),
    val myCrowns: Int? = null,
    val enemyCrowns: Int? = null,
    val result: BattleResult = BattleResult.UNKNOWN,
    val durationSeconds: Double? = null,
    val startingTrophies: Int? = null,
    val endingTrophies: Int? = null,
    val trophyChange: Int? = null,
    val raw: String? = null,
)

/** What a sync pass found, so the UI can report it instead of claiming success. */
data class SyncReport(
    val source: String,
    val found: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val consumedLiveUids: Set<String> = emptySet(),
    val diagnostics: List<String> = emptyList(),
    val unavailableFields: List<String> = emptyList(),
    val error: String? = null,
) {
    val summary: String
        get() = buildString {
            append("来源: $source\n")
            append("找到 $found 条 · 新增 $imported · 重复 $duplicates · 补全 $updated · 跳过 $skipped\n")
            if (unavailableFields.isNotEmpty()) {
                append("Nulls 历史接口本身不提供的字段: ${unavailableFields.joinToString(", ")}\n")
            }
            if (error != null) append("错误: $error")
        }
}

/**
 * Recovers history from Null's battle log and merges it into the local store.
 *
 * The importer itself is source-agnostic and fully testable: it takes entries,
 * derives a stable identity for each one, and upserts. Two rules make the merge
 * safe to run repeatedly:
 *
 *  * official battle id wins, then replay id, then a composite key;
 *  * only a complete, structurally valid game-owned row may cross into storage;
 *  * live capture can donate timing after a conservative match, never outcome.
 */
object NullsHistoryImporter {

    /** Fields the live probe cannot attest and only a history import can supply. */
    val LIVE_CAPTURE_GAPS = listOf("mode", "arena", "player names", "battle_id", "replay_id", "trophies")

    fun import(
        dao: BattleDao,
        entries: List<NullsBattleEntry>,
        source: String,
        namer: CardNamer? = null,
        fallbackTimeMs: Long = System.currentTimeMillis(),
        pending: List<BattleRecord> = emptyList(),
        canonicalSelfId: Long = 0L,
    ): SyncReport {
        var imported = 0
        var duplicates = 0
        var updated = 0
        var skipped = 0
        val consumed = LinkedHashSet<String>()
        val diagnostics = ArrayList<String>()
        for (entry in entries) {
            val outcome = toOutcome(entry, namer, fallbackTimeMs, pending)
            val record = outcome.record
            if (record == null) {
                skipped++
                diagnostics += outcome.rejection ?: "rejected"
                continue
            }
            val normalized = BattleCanonicalizer.normalizeBattleSides(record, canonicalSelfId)
            val existing = dao.all().firstOrNull { BattleCanonicalizer.sameBattle(it, normalized) }
            dao.upsert(normalized, canonicalSelfId)
            if (existing == null) {
                imported++
            } else {
                // The row already existed: count it as a duplicate, and as an
                // update when this pass actually added a field it lacked.
                duplicates++
                if (fillsGap(existing, record)) updated++
            }
            outcome.matchedLiveUid?.let { consumed += it }
        }
        return SyncReport(
            source = source,
            found = entries.size,
            imported = imported,
            duplicates = duplicates,
            updated = updated,
            skipped = skipped,
            consumedLiveUids = consumed,
            diagnostics = diagnostics.distinct().take(8),
        )
    }

    /** True when [fresh] carries a value the stored row was missing. */
    private fun fillsGap(stored: BattleRecord, fresh: BattleRecord): Boolean =
        (stored.mode == null && fresh.mode != null) ||
            (stored.arena == null && fresh.arena != null) ||
            (stored.battleId == null && fresh.battleId != null) ||
            (stored.replayId == null && fresh.replayId != null) ||
            (stored.durationSeconds == null && fresh.durationSeconds != null) ||
            (stored.myPlayerName == null && fresh.myPlayerName != null) ||
            (stored.enemyPlayerName == null && fresh.enemyPlayerName != null) ||
            (stored.trophyChange == null && fresh.trophyChange != null) ||
            (stored.myDeck.isEmpty() && fresh.myDeck.isNotEmpty()) ||
            (stored.enemyDeck.isEmpty() && fresh.enemyDeck.isNotEmpty()) ||
            (stored.result == BattleResult.INCOMPLETE && fresh.result != BattleResult.INCOMPLETE)

    /**
     * Builds a row from an imported entry.
     *
     * `battleTimeMs` is optional in an entry: a replay payload carries no wall
     * clock at all. Rather than dropping such a battle, the import time is used
     * and the row is marked so nobody reads that timestamp as the battle's own.
     */
    fun toRecord(
        entry: NullsBattleEntry,
        namer: CardNamer? = null,
        fallbackTimeMs: Long = System.currentTimeMillis(),
        pending: List<BattleRecord> = emptyList(),
    ): BattleRecord? = toOutcome(entry, namer, fallbackTimeMs, pending).record

    fun toOutcome(
        entry: NullsBattleEntry,
        namer: CardNamer? = null,
        fallbackTimeMs: Long = System.currentTimeMillis(),
        pending: List<BattleRecord> = emptyList(),
    ): BattleHistoryReconciler.Outcome {
        val authoritativeTimeKnown = entry.battleTimeMs != null
        val time = entry.battleTimeMs ?: fallbackTimeMs
        val archetype = ArchetypeClassifier.classify(entry.enemyCards)
        val candidate = BattleRecord(
            battleUid = battleUid(entry, time),
            battleId = entry.battleId,
            replayId = entry.replayId,
            battleTime = time,
            startTime = time,
            endTime = entry.durationSeconds?.let { time + (it * 1000).toLong() },
            durationSeconds = entry.durationSeconds,
            durationTicks = entry.durationSeconds?.let { (it * 20).toInt() },
            mode = entry.mode,
            arena = entry.arena,
            myPlayerId = entry.myPlayerId,
            myPlayerName = entry.myPlayerName,
            enemyPlayerId = entry.enemyPlayerId,
            enemyPlayerName = entry.enemyPlayerName,
            myCrowns = entry.myCrowns,
            enemyCrowns = entry.enemyCrowns,
            result = entry.result,
            status = if (entry.result == BattleResult.UNKNOWN) {
                BattleStatus.INCOMPLETE
            } else {
                BattleStatus.COMPLETE
            },
            startingTrophies = entry.startingTrophies,
            endingTrophies = entry.endingTrophies,
            trophyChange = entry.trophyChange,
            myDeck = deckOf(entry.myCards, entry.myEvos, entry.myHeroes, CardSide.SELF, namer),
            enemyDeck = deckOf(entry.enemyCards, entry.enemyEvos, entry.enemyHeroes, CardSide.ENEMY, namer),
            enemyArchetype = archetype.archetype,
            enemyArchetypeSubtype = archetype.subtype,
            enemyArchetypeConfidence = archetype.confidence,
            source = BattleSource.NULLS_HISTORY,
            rawJson = entry.raw,
        )
        return BattleHistoryReconciler.finalize(
            candidate,
            pending = pending,
            authoritativeTimeKnown = authoritativeTimeKnown,
        )
    }

    private fun deckOf(
        cards: List<Int>,
        evolutions: List<Int>,
        heroes: List<Int>,
        side: CardSide,
        namer: CardNamer?,
    ): List<BattleCard> = cards.mapIndexed { index, cardId ->
        namer?.card(
            side = side,
            slot = index,
            cardId = cardId,
            isEvolution = evolutions.contains(cardId),
            isHero = heroes.contains(cardId),
            cost = null,
        ) ?: BattleCard(
            side = side,
            slot = index,
            cardId = cardId,
            name = "",
            nameZh = "",
            isEvolution = evolutions.contains(cardId),
            isHero = heroes.contains(cardId),
        )
    }

    /**
     * `nulls:<battle id>` when the listing publishes one, `nulls-replay:<seed>`
     * for a replay payload (its `rndSeed` is the only stable identity it
     * carries), otherwise a hash of the complete fallback identity. Time is
     * merely one component; unlike the old live uid it is never used alone.
     */
    fun battleUid(entry: NullsBattleEntry, timeMs: Long): String {
        val battleId = entry.battleId?.takeIf { it.isNotBlank() }
        if (battleId != null) return "nulls:$battleId"
        val replayId = entry.replayId?.takeIf { it.isNotBlank() }
        if (replayId != null) return "nulls-replay:$replayId"
        val candidate = BattleRecord(
            battleUid = "pending",
            battleId = entry.battleId,
            replayId = entry.replayId,
            battleTime = entry.battleTimeMs ?: timeMs,
            mode = entry.mode,
            arena = entry.arena,
            myPlayerId = entry.myPlayerId,
            enemyPlayerId = entry.enemyPlayerId,
            myCrowns = entry.myCrowns,
            enemyCrowns = entry.enemyCrowns,
            result = entry.result,
            myDeck = deckOf(entry.myCards, entry.myEvos, entry.myHeroes, CardSide.SELF, null),
            enemyDeck = deckOf(entry.enemyCards, entry.enemyEvos, entry.enemyHeroes, CardSide.ENEMY, null),
            source = BattleSource.NULLS_HISTORY,
        )
        return BattleHistoryReconciler.canonicalUid(
            candidate,
            includeBattleTime = entry.battleTimeMs != null,
        )
    }

    // ---------------------------------------------------------- payload I/O --

    /**
     * Parse a battle-log payload.
     *
     * Accepts three shapes, because the raw evidence from a capture is exactly
     * what should be importable without a converter step:
     *
     *  * the probe envelope `{"schema":"nulls-history.v1","battles":[...]}`
     *  * a bare array of battle-log rows
     *  * a **replay payload** (`{"battle":{...},"cmd":[...]}`), which is what
     *    the client actually fetches when a battle-log entry is opened
     */
    fun parsePayload(text: String, localAccountId: Long = 0L): List<NullsBattleEntry> {
        if (NullsReplayParser.looksLikeReplay(text)) {
            return listOfNotNull(NullsReplayParser.parse(text, localAccountId))
        }
        val trimmed = text.trim()
        val array: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("battles") ?: JSONArray()
        }
        val entries = ArrayList<NullsBattleEntry>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            entries += parseEntry(row)
        }
        return entries
    }

    private fun parseEntry(row: JSONObject): NullsBattleEntry {
        val result = when (row.optString("result", "").lowercase()) {
            "win", "victory" -> BattleResult.WIN
            "loss", "defeat" -> BattleResult.LOSS
            "draw" -> BattleResult.DRAW
            else -> BattleResult.UNKNOWN
        }
        return NullsBattleEntry(
            battleId = row.opt("battle_id").asStringOrNull(),
            replayId = row.opt("replay_id").asStringOrNull(),
            battleTimeMs = row.opt("battle_time").asEpochMillis(),
            mode = row.opt("mode").asStringOrNull(),
            arena = row.opt("arena").asStringOrNull(),
            myPlayerId = row.opt("my_player_id").asStringOrNull(),
            myPlayerName = row.opt("my_player_name").asStringOrNull(),
            enemyPlayerId = row.opt("enemy_player_id").asStringOrNull(),
            enemyPlayerName = row.opt("enemy_player_name").asStringOrNull(),
            myCards = row.optJSONArray("my_cards").asIntList(),
            enemyCards = row.optJSONArray("enemy_cards").asIntList(),
            myEvos = row.optJSONArray("my_evos").asIntList(),
            enemyEvos = row.optJSONArray("enemy_evos").asIntList(),
            myHeroes = row.optJSONArray("my_heroes").asIntList(),
            enemyHeroes = row.optJSONArray("enemy_heroes").asIntList(),
            myCrowns = row.opt("my_crowns").asIntOrNull(),
            enemyCrowns = row.opt("enemy_crowns").asIntOrNull(),
            result = result,
            durationSeconds = row.opt("duration_seconds").asDoubleOrNull(),
            startingTrophies = row.opt("starting_trophies").asIntOrNull(),
            endingTrophies = row.opt("ending_trophies").asIntOrNull(),
            trophyChange = row.opt("trophy_change").asIntOrNull(),
            raw = row.toString(),
        )
    }

    /** Reads a payload file that was pulled off the device, e.g. into `exports/`. */
    fun parsePayloadFile(file: File, localAccountId: Long = 0L): List<NullsBattleEntry> =
        if (file.isFile) parsePayload(file.readText(Charsets.UTF_8), localAccountId) else emptyList()
}

private fun Any?.asStringOrNull(): String? = when (this) {
    null -> null
    is String -> takeIf { it.isNotBlank() }
    else -> toString()
}

private fun Any?.asIntOrNull(): Int? = when (this) {
    is Number -> toInt()
    is String -> toIntOrNull()
    else -> null
}

private fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull()
    else -> null
}

/** Accepts epoch seconds, epoch millis, or an ISO-ish numeric string. */
private fun Any?.asEpochMillis(): Long? {
    val value = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    } ?: return null
    return if (value < 100_000_000_000L) value * 1000 else value
}

private fun JSONArray?.asIntList(): List<Int> {
    if (this == null) return emptyList()
    val list = ArrayList<Int>(length())
    for (index in 0 until length()) {
        val value = opt(index)
        when (value) {
            is Number -> list += value.toInt()
            is String -> value.toIntOrNull()?.let { list += it }
        }
    }
    return list
}

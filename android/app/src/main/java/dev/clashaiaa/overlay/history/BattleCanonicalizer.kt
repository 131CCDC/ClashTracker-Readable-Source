package dev.clashaiaa.overlay.history

import kotlin.math.abs

data class ConsolidationReport(
    val records: List<BattleRecord>,
    val rawCount: Int,
    val canonicalCount: Int,
    val mergedDuplicates: Int,
    val excludedInvalidIdentity: Int,
    val unknown: Int,
)

/** Pure boundary for identity, side orientation and same-battle detection. */
object BattleCanonicalizer {
    fun normalizeBattleSides(record: BattleRecord, canonicalSelfId: Long): BattleRecord {
        val self = canonicalSelfId.takeIf { it > 0 }?.toString()
        val me = cleanId(record.myPlayerId)
        val enemy = cleanId(record.enemyPlayerId)
        val oriented = when {
            self != null && me == self -> record
            self != null && enemy == self -> record.copy(
                myPlayerId = record.enemyPlayerId,
                myPlayerName = record.enemyPlayerName,
                enemyPlayerId = record.myPlayerId,
                enemyPlayerName = record.myPlayerName,
                myCrowns = record.enemyCrowns,
                enemyCrowns = record.myCrowns,
                myDeck = record.enemyDeck.map { it.copy(side = CardSide.SELF) },
                enemyDeck = record.myDeck.map { it.copy(side = CardSide.ENEMY) },
                result = invert(record.result),
                startingTrophies = null,
                endingTrophies = null,
                trophyChange = null,
            )
            else -> record.copy(result = BattleResult.UNKNOWN)
        }
        val eligible = self != null && cleanId(oriented.myPlayerId) == self
        val fingerprint = fingerprint(oriented)
        val stableId = stableId(oriented)
        val sources = (record.mergedSources + record.source.wire).filter { it.isNotBlank() }.distinct()
        return oriented.copy(
            canonicalBattleId = stableId ?: oriented.canonicalBattleId
                ?: "canonical:$fingerprint:${oriented.battleTime / 1000L}",
            provisionalBattleId = oriented.provisionalBattleId ?: oriented.battleUid,
            battleFingerprint = fingerprint,
            identityConfidence = if (eligible) "canonical" else "invalid",
            resultSource = resultSource(oriented),
            resultConfidence = if (eligible && oriented.decided && reliableResult(oriented)) "authoritative" else "unknown",
            personalRecordEligible = eligible,
            mergedSources = sources,
            result = if (eligible) oriented.result else BattleResult.UNKNOWN,
        )
    }

    fun fingerprint(record: BattleRecord): String {
        val duration = record.durationTicks ?: record.lastTick ?: record.durationSeconds?.let { (it * 20).toInt() } ?: -1
        val material = listOf(
            cleanId(record.myPlayerId) ?: "?",
            cleanId(record.enemyPlayerId) ?: "?",
            deckSignature(record.myDeck),
            deckSignature(record.enemyDeck),
            (record.myCrowns ?: -1).toString(),
            (record.enemyCrowns ?: -1).toString(),
            duration.toString(),
            record.mode.normalized(),
            record.arena.normalized(),
        ).joinToString("|")
        return fnv1a(material)
    }

    fun sameBattle(left: BattleRecord, right: BattleRecord): Boolean {
        if (left.battleUid == right.battleUid) return true
        if (stableId(left) != null && stableId(left) == stableId(right)) return true
        if (cleanId(left.myPlayerId) != cleanId(right.myPlayerId) ||
            cleanId(left.enemyPlayerId) != cleanId(right.enemyPlayerId)
        ) return false
        if (!decksCompatible(left.myDeck, right.myDeck) || !decksCompatible(left.enemyDeck, right.enemyDeck)) return false
        if (!nullableEqual(left.myCrowns, right.myCrowns) || !nullableEqual(left.enemyCrowns, right.enemyCrowns)) return false
        if (!compatible(left.mode, right.mode) || !compatible(left.arena, right.arena)) return false
        if (!resultsCompatible(left.result, right.result)) return false
        val leftTicks = left.durationTicks ?: left.lastTick ?: left.durationSeconds?.let { (it * 20).toInt() }
        val rightTicks = right.durationTicks ?: right.lastTick ?: right.durationSeconds?.let { (it * 20).toInt() }
        if (leftTicks != null && rightTicks != null && abs(leftTicks - rightTicks) > MAX_TICK_DELTA) return false

        val leftEndOnly = endOnly(left)
        val rightEndOnly = endOnly(right)
        if (leftEndOnly || rightEndOnly) {
            // A final frame can survive for minutes and be re-read after process restart.
            return leftTicks != null && rightTicks != null &&
                abs(leftTicks - rightTicks) <= MAX_TICK_DELTA &&
                abs(left.battleTime - right.battleTime) <= END_FRAME_WINDOW_MS
        }
        val leftStart = left.startTime ?: left.battleTime
        val rightStart = right.startTime ?: right.battleTime
        return abs(leftStart - rightStart) <= START_WINDOW_MS
    }

    fun consolidate(records: List<BattleRecord>, canonicalSelfId: Long): ConsolidationReport {
        val canonical = ArrayList<BattleRecord>()
        for (raw in records.sortedBy { it.battleTime }) {
            val candidate = normalizeBattleSides(raw, canonicalSelfId)
            val index = canonical.indexOfFirst { sameBattle(it, candidate) }
            if (index < 0) {
                canonical += candidate
            } else {
                canonical[index] = mergeCanonical(canonical[index], candidate)
            }
        }
        return ConsolidationReport(
            records = canonical,
            rawCount = records.size,
            canonicalCount = canonical.size,
            mergedDuplicates = records.size - canonical.size,
            excludedInvalidIdentity = canonical.count { !it.personalRecordEligible },
            unknown = canonical.count { it.result == BattleResult.UNKNOWN },
        )
    }

    fun mergeCanonical(old: BattleRecord, fresh: BattleRecord): BattleRecord {
        val richer = if (quality(fresh) >= quality(old)) fresh else old
        val other = if (richer === fresh) old else fresh
        return normalizeMetadata(
            mergeBattleRecords(other, richer).copy(
                battleUid = old.battleUid,
                canonicalBattleId = stableId(fresh) ?: stableId(old) ?: old.canonicalBattleId ?: fresh.canonicalBattleId,
                provisionalBattleId = old.provisionalBattleId ?: old.battleUid,
                personalRecordEligible = old.personalRecordEligible || fresh.personalRecordEligible,
                mergedSources = (old.mergedSources + fresh.mergedSources + old.source.wire + fresh.source.wire).distinct(),
            ),
        )
    }

    private fun normalizeMetadata(record: BattleRecord): BattleRecord = record.copy(
        battleFingerprint = fingerprint(record),
        resultSource = resultSource(record),
        resultConfidence = if (record.personalRecordEligible && record.decided && reliableResult(record)) "authoritative" else "unknown",
    )

    private fun stableId(record: BattleRecord): String? = when {
        !record.battleId.isNullOrBlank() -> "nulls:${record.battleId}"
        !record.replayId.isNullOrBlank() -> "nulls-replay:${record.replayId}"
        record.canonicalBattleId?.startsWith("nulls:") == true ||
            record.canonicalBattleId?.startsWith("nulls-replay:") == true -> record.canonicalBattleId
        else -> null
    }

    private fun quality(record: BattleRecord): Int = when {
        record.source == BattleSource.NULLS_HISTORY || stableId(record) != null -> 400
        record.fullBattle && record.identitySource != "SEAT_FALLBACK" -> 300
        record.source == BattleSource.NATIVE_RESULT && record.nativeResultValidated -> 200
        else -> 100
    }

    private fun reliableResult(record: BattleRecord): Boolean =
        record.source == BattleSource.NULLS_HISTORY ||
            (record.source == BattleSource.NATIVE_RESULT && record.nativeResultValidated)

    private fun resultSource(record: BattleRecord): String = when {
        record.source == BattleSource.NULLS_HISTORY -> "game_history"
        record.source == BattleSource.NATIVE_RESULT && record.nativeResultValidated -> "native_result"
        else -> "unknown"
    }

    private fun endOnly(record: BattleRecord): Boolean =
        !record.fullBattle && record.firstTick != null && record.lastTick != null &&
            abs(record.firstTick - record.lastTick) <= MAX_TICK_DELTA

    private fun decksCompatible(a: List<BattleCard>, b: List<BattleCard>): Boolean =
        a.isEmpty() || b.isEmpty() || deckSignature(a) == deckSignature(b)

    private fun deckSignature(deck: List<BattleCard>): String = deck.map { it.cardId }.filter { it > 0 }.sorted().joinToString(",")
    private fun nullableEqual(a: Int?, b: Int?): Boolean = a == null || b == null || a == b
    private fun compatible(a: String?, b: String?): Boolean = a.isNullOrBlank() || b.isNullOrBlank() || a.equals(b, true)
    private fun resultsCompatible(a: BattleResult, b: BattleResult): Boolean =
        a == b || a in UNDECIDED || b in UNDECIDED
    private fun invert(result: BattleResult): BattleResult = when (result) {
        BattleResult.WIN -> BattleResult.LOSS
        BattleResult.LOSS -> BattleResult.WIN
        else -> result
    }
    private fun cleanId(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "null" }
    private fun String?.normalized(): String = this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "?"
    private fun fnv1a(value: String): String {
        var hash = 0xcbf29ce484222325uL
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff).toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    private val UNDECIDED = setOf(BattleResult.UNKNOWN, BattleResult.INCOMPLETE)
    private const val MAX_TICK_DELTA = 100
    private const val START_WINDOW_MS = 20_000L
    private const val END_FRAME_WINDOW_MS = 15 * 60_000L
}

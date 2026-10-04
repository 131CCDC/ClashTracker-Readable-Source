package dev.clashaiaa.overlay.history

import kotlin.math.roundToInt

/** One duration histogram row. */
data class DurationBucket(
    val label: String,
    val labelZh: String,
    val minSeconds: Double,
    val maxSeconds: Double,
    val games: Int,
    val wins: Int,
) {
    val losses: Int get() = games - wins
    val winRate: Double? get() = if (games > 0) wins.toDouble() / games else null
}

/** One row of the "against this archetype" table. */
data class MatchupRow(
    val archetype: String,
    val subtype: String?,
    val games: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
) {
    val display: String get() = if (subtype.isNullOrBlank()) archetype else "$archetype $subtype"
    val decided: Int get() = wins + losses + draws

    /** `null` when nothing decided, so a 0-game row cannot print "0 %". */
    val winRate: Double? get() = if (decided > 0) wins.toDouble() / decided else null

    /** Sample size, so a 3-game row is never shown like a 40-game one. */
    val sample: Int get() = decided
}

/** One row of the "against this card" table. */
data class CardRow(
    val cardId: Int,
    val name: String,
    val nameZh: String,
    val isEvolution: Boolean,
    val isHero: Boolean,
    val appearances: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
) {
    val label: String
        get() = when {
            isHero && isEvolution -> "Hero Evo $name"
            isHero -> "Hero $name"
            isEvolution -> "Evo $name"
            else -> name
        }

    val labelZh: String
        get() = when {
            isHero && isEvolution -> "$nameZh·英雄进化"
            isHero -> "$nameZh·英雄"
            isEvolution -> "$nameZh·进化"
            else -> nameZh
        }

    val decided: Int get() = wins + losses + draws
    val winRate: Double? get() = if (decided > 0) wins.toDouble() / decided else null
}

/** Everything the "Loss Analysis" section answers, and nothing more. */
data class LossAnalysis(
    /** Archetypes with the lowest win rate, above the minimum sample. */
    val worstMatchups: List<MatchupRow>,
    /** Enemy cards with the lowest win rate, above the minimum sample. */
    val worstCards: List<CardRow>,
    val averageDurationSeconds: Double?,
    val medianDurationSeconds: Double?,
    /** `crowns scored by the opponent` -> number of losses. */
    val crownDistribution: List<Pair<Int, Int>>,
)

/** One computed snapshot of the whole history, or of a filtered slice of it. */
data class BattleStats(
    val total: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val incomplete: Int,
    val decided: Int,
    val winRate: Double?,
    val averageDurationSeconds: Double?,
    val medianDurationSeconds: Double?,
    val p25DurationSeconds: Double?,
    val p75DurationSeconds: Double?,
    val p90DurationSeconds: Double?,
    val averageCrowns: Double?,
    val averageEnemyCrowns: Double?,
    val durationBuckets: List<DurationBucket>,
    val matchups: List<MatchupRow>,
    val cards: List<CardRow>,
    val lossAnalysis: LossAnalysis,
    val longest: List<BattleRecord>,
    val unknown: Int = 0,
    val excludedInvalidIdentity: Int = 0,
) {
    companion object {
        val EMPTY = BattleStats(
            total = 0, wins = 0, losses = 0, draws = 0, incomplete = 0, decided = 0,
            winRate = null, averageDurationSeconds = null, medianDurationSeconds = null,
            p25DurationSeconds = null, p75DurationSeconds = null, p90DurationSeconds = null,
            averageCrowns = null, averageEnemyCrowns = null,
            durationBuckets = emptyList(), matchups = emptyList(), cards = emptyList(),
            lossAnalysis = LossAnalysis(emptyList(), emptyList(), null, null, emptyList()),
            longest = emptyList(),
        )
    }
}

/**
 * Pure aggregation over a list of records. No Android types, so every rule here
 * is covered by plain JVM unit tests.
 *
 * Two deliberate statistical choices:
 *
 *  * **win rate excludes undecided rows.** A crash-safe `incomplete` stub is not
 *    a loss, and counting it as one would make the number wrong in the
 *    pessimistic direction.
 *  * **small samples are kept visible but never ranked as if they were solid.**
 *    Every row carries its `sample`, and the "worst" tables only rank rows at or
 *    above [MIN_SAMPLE_FOR_RANKING].
 */
object BattleStatsCalculator {

    /** Below this many decided games a row is shown but not ranked. */
    const val MIN_SAMPLE_FOR_RANKING = 5

    fun compute(records: List<BattleRecord>, excludedInvalidIdentity: Int = 0): BattleStats {
        if (records.isEmpty()) return BattleStats.EMPTY

        var wins = 0
        var losses = 0
        var draws = 0
        var incomplete = 0
        var unknown = 0
        val durations = ArrayList<Double>(records.size)
        var crownSum = 0
        var crownCount = 0
        var enemyCrownSum = 0
        var enemyCrownCount = 0
        val matchups = HashMap<String, IntArray>()
        val cards = HashMap<String, CardAccumulator>()
        var lossDurationSum = 0.0
        var lossDurationCount = 0
        val lossCrowns = HashMap<Int, Int>()

        for (record in records) {
            when (record.result) {
                BattleResult.WIN -> wins++
                BattleResult.LOSS -> losses++
                BattleResult.DRAW -> draws++
                BattleResult.INCOMPLETE -> incomplete++
                BattleResult.UNKNOWN -> unknown++
            }
            val decided = record.decided
            record.durationSeconds?.let { durations += it }
            record.myCrowns?.let { crownSum += it; crownCount++ }
            record.enemyCrowns?.let { enemyCrownSum += it; enemyCrownCount++ }

            if (decided) {
                val key = matchupKey(record)
                val row = matchups.getOrPut(key) { IntArray(4) }
                row[0]++
                when (record.result) {
                    BattleResult.WIN -> row[1]++
                    BattleResult.LOSS -> row[2]++
                    BattleResult.DRAW -> row[3]++
                    else -> Unit
                }
            }

            for (card in record.enemyDeck) {
                val key = cardKey(card)
                val accumulator = cards.getOrPut(key) { CardAccumulator(card) }
                accumulator.appearances++
                if (decided) {
                    when (record.result) {
                        BattleResult.WIN -> accumulator.wins++
                        BattleResult.LOSS -> accumulator.losses++
                        BattleResult.DRAW -> accumulator.draws++
                        else -> Unit
                    }
                }
            }

            if (record.result == BattleResult.LOSS) {
                record.durationSeconds?.let { lossDurationSum += it; lossDurationCount++ }
                val crowns = record.enemyCrowns
                if (crowns != null) lossCrowns[crowns] = (lossCrowns[crowns] ?: 0) + 1
            }
        }

        val sortedDurations = durations.sorted()
        val decidedTotal = wins + losses + draws
        val matchupRows = matchups.map { (key, counts) ->
            val parts = key.split('|', limit = 2)
            MatchupRow(
                archetype = parts[0],
                subtype = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "-" },
                games = counts[0],
                wins = counts[1],
                losses = counts[2],
                draws = counts[3],
            )
        }
        val cardRows = cards.values.map { it.toRow() }

        return BattleStats(
            total = records.size,
            wins = wins,
            losses = losses,
            draws = draws,
            incomplete = incomplete,
            decided = decidedTotal,
            winRate = if (decidedTotal > 0) wins.toDouble() / decidedTotal else null,
            averageDurationSeconds = sortedDurations.takeIf { it.isNotEmpty() }?.average(),
            medianDurationSeconds = percentile(sortedDurations, 50.0),
            p25DurationSeconds = percentile(sortedDurations, 25.0),
            p75DurationSeconds = percentile(sortedDurations, 75.0),
            p90DurationSeconds = percentile(sortedDurations, 90.0),
            averageCrowns = if (crownCount > 0) crownSum.toDouble() / crownCount else null,
            averageEnemyCrowns = if (enemyCrownCount > 0) enemyCrownSum.toDouble() / enemyCrownCount else null,
            durationBuckets = buckets(records),
            matchups = matchupRows,
            cards = cardRows,
            lossAnalysis = LossAnalysis(
                worstMatchups = matchupRows
                    .filter { it.decided >= MIN_SAMPLE_FOR_RANKING }
                    .sortedWith(compareBy({ it.winRate ?: 0.0 }, { -it.decided }))
                    .take(10),
                worstCards = cardRows
                    .filter { it.decided >= MIN_SAMPLE_FOR_RANKING }
                    .sortedWith(compareBy({ it.winRate ?: 0.0 }, { -it.decided }))
                    .take(10),
                averageDurationSeconds = if (lossDurationCount > 0) {
                    lossDurationSum / lossDurationCount
                } else {
                    null
                },
                medianDurationSeconds = medianOf(
                    records.filter { it.result == BattleResult.LOSS }
                        .mapNotNull { it.durationSeconds },
                ),
                crownDistribution = lossCrowns.entries
                    .sortedBy { it.key }
                    .map { it.key to it.value },
            ),
            longest = records
                .filter { it.durationSeconds != null }
                .sortedByDescending { it.durationSeconds }
                .take(10),
            unknown = unknown,
            excludedInvalidIdentity = excludedInvalidIdentity,
        )
    }

    private class CardAccumulator(card: BattleCard) {
        val cardId = card.cardId
        val name = card.name
        val nameZh = card.nameZh
        val isEvolution = card.isEvolution
        val isHero = card.isHero
        var appearances = 0
        var wins = 0
        var losses = 0
        var draws = 0

        fun toRow() = CardRow(
            cardId = cardId,
            name = name,
            nameZh = nameZh,
            isEvolution = isEvolution,
            isHero = isHero,
            appearances = appearances,
            wins = wins,
            losses = losses,
            draws = draws,
        )
    }

    /** Evolution and hero forms are separate rows, never folded into the base card. */
    private fun cardKey(card: BattleCard): String =
        "${card.cardId}|${if (card.isEvolution) 1 else 0}|${if (card.isHero) 1 else 0}"

    private fun matchupKey(record: BattleRecord): String =
        "${record.enemyArchetype ?: "Unknown"}|${record.enemyArchetypeSubtype ?: "-"}"

    /** Linear-interpolated percentile of an already sorted list. */
    fun percentile(sorted: List<Double>, percent: Double): Double? {
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted[0]
        val rank = (percent / 100.0) * (sorted.size - 1)
        val low = rank.toInt()
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        val weight = rank - low
        return sorted[low] * (1 - weight) + sorted[high] * weight
    }

    private fun medianOf(values: List<Double>): Double? = percentile(values.sorted(), 50.0)

    private class BucketSpec(val label: String, val labelZh: String, val min: Double, val max: Double)

    private val BUCKETS = listOf(
        BucketSpec("< 30s", "< 30 秒", 0.0, 30.0),
        BucketSpec("30-60s", "30–60 秒", 30.0, 60.0),
        BucketSpec("1-2 min", "1–2 分钟", 60.0, 120.0),
        BucketSpec("2-3 min", "2–3 分钟", 120.0, 180.0),
        BucketSpec("3-4 min", "3–4 分钟", 180.0, 240.0),
        BucketSpec("4-5 min", "4–5 分钟", 240.0, 300.0),
        BucketSpec("5 min+", "5 分钟以上", 300.0, Double.MAX_VALUE),
    )

    private fun buckets(records: List<BattleRecord>): List<DurationBucket> {
        val games = IntArray(BUCKETS.size)
        val wins = IntArray(BUCKETS.size)
        for (record in records) {
            val duration = record.durationSeconds ?: continue
            val index = BUCKETS.indexOfFirst { duration >= it.min && duration < it.max }
            if (index < 0) continue
            games[index]++
            if (record.result == BattleResult.WIN) wins[index]++
        }
        return BUCKETS.mapIndexed { index, spec ->
            DurationBucket(spec.label, spec.labelZh, spec.min, spec.max, games[index], wins[index])
        }
    }
}

/** `"87.8%"`, or `"n/a"` when nothing decided. */
fun Double?.asPercent(): String =
    if (this == null) "n/a" else "${(this * 1000).roundToInt() / 10.0}%"

/** `"2:37"`, or `"--:--"` when the duration is unknown. */
fun Double?.asClock(): String {
    if (this == null) return "--:--"
    val total = this.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

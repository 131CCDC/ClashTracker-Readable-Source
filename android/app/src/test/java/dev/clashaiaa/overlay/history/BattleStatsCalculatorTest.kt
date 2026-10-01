package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleStatsCalculatorTest {

    @Test
    fun `win rate excludes undecided rows`() {
        val stats = BattleStatsCalculator.compute(
            listOf(
                TestRecords.record("a", 1, BattleResult.WIN),
                TestRecords.record("b", 2, BattleResult.LOSS),
                TestRecords.record("c", 3, BattleResult.INCOMPLETE, status = BattleStatus.INCOMPLETE),
            ),
        )
        assertEquals(3, stats.total)
        assertEquals(1, stats.wins)
        assertEquals(1, stats.losses)
        assertEquals(1, stats.incomplete)
        assertEquals(2, stats.decided)
        assertEquals(0.5, stats.winRate!!, 1e-9)
    }

    @Test
    fun `empty history has no invented numbers`() {
        val stats = BattleStatsCalculator.compute(emptyList())
        assertEquals(0, stats.total)
        assertNull(stats.winRate)
        assertNull(stats.averageDurationSeconds)
        assertNull(stats.medianDurationSeconds)
    }

    @Test
    fun `percentiles interpolate and handle a single sample`() {
        assertEquals(5.0, BattleStatsCalculator.percentile(listOf(5.0), 50.0)!!, 1e-9)
        val values = (1..10).map { it.toDouble() }
        assertEquals(5.5, BattleStatsCalculator.percentile(values, 50.0)!!, 1e-9)
        assertEquals(3.25, BattleStatsCalculator.percentile(values, 25.0)!!, 1e-9)
        assertEquals(7.75, BattleStatsCalculator.percentile(values, 75.0)!!, 1e-9)
    }

    @Test
    fun `duration buckets cover the whole range and do not overlap`() {
        val stats = BattleStatsCalculator.compute(
            listOf(
                TestRecords.record("a", 1, BattleResult.WIN, durationSeconds = 20.0),
                TestRecords.record("b", 2, BattleResult.WIN, durationSeconds = 45.0),
                TestRecords.record("c", 3, BattleResult.LOSS, durationSeconds = 90.0),
                TestRecords.record("d", 4, BattleResult.WIN, durationSeconds = 150.0),
                TestRecords.record("e", 5, BattleResult.WIN, durationSeconds = 210.0),
                TestRecords.record("f", 6, BattleResult.WIN, durationSeconds = 270.0),
                TestRecords.record("g", 7, BattleResult.LOSS, durationSeconds = 330.0),
            ),
        )
        assertEquals(7, stats.durationBuckets.sumOf { it.games })
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1), stats.durationBuckets.map { it.games })
        assertEquals("5 min+", stats.durationBuckets.last().label)
        assertEquals("< 30s", stats.durationBuckets.first().label)
    }

    @Test
    fun `a battle exactly on a bucket edge lands in the later bucket once`() {
        val stats = BattleStatsCalculator.compute(
            listOf(TestRecords.record("a", 1, BattleResult.WIN, durationSeconds = 60.0)),
        )
        assertEquals(1, stats.durationBuckets.sumOf { it.games })
        assertEquals(1, stats.durationBuckets.first { it.label == "1-2 min" }.games)
    }

    @Test
    fun `matchups group by archetype and subtype`() {
        val stats = BattleStatsCalculator.compute(
            listOf(
                TestRecords.record("a", 1, BattleResult.WIN, archetype = "Miner", subtype = "Poison"),
                TestRecords.record("b", 2, BattleResult.WIN, archetype = "Miner", subtype = "Poison"),
                TestRecords.record("c", 3, BattleResult.LOSS, archetype = "Miner", subtype = "Rocket"),
            ),
        )
        val poison = stats.matchups.first { it.display == "Miner Poison" }
        assertEquals(2, poison.games)
        assertEquals(2, poison.wins)
        assertEquals(1.0, poison.winRate!!, 1e-9)
        assertEquals(2, stats.matchups.size)
    }

    @Test
    fun `worst matchups only rank rows above the sample floor`() {
        val records = ArrayList<BattleRecord>()
        // 1-9 against Hog across 10 games: solid sample, must be ranked.
        repeat(1) { records += TestRecords.record("hog-w$it", it.toLong(), BattleResult.WIN, archetype = "Hog", subtype = "Cycle") }
        repeat(9) { records += TestRecords.record("hog-l$it", 100L + it, BattleResult.LOSS, archetype = "Hog", subtype = "Cycle") }
        // 0-2 against Golem: tiny sample, must NOT be ranked.
        repeat(2) { records += TestRecords.record("golem-l$it", 200L + it, BattleResult.LOSS, archetype = "Golem", subtype = "Beatdown") }

        val stats = BattleStatsCalculator.compute(records)
        val worst = stats.lossAnalysis.worstMatchups
        assertTrue(worst.any { it.display == "Hog Cycle" })
        assertTrue("a 2-game row must not be ranked", worst.none { it.display == "Golem Beatdown" })
    }

    @Test
    fun `cards count appearances and separate evolution from base`() {
        val base = TestRecords.card(26000043, 0, name = "AngryBarbarians")
        val evo = TestRecords.card(26000043, 1, name = "AngryBarbarians", isEvolution = true)
        val stats = BattleStatsCalculator.compute(
            listOf(
                TestRecords.record("a", 1, BattleResult.WIN, enemyCards = listOf(base)),
                TestRecords.record("b", 2, BattleResult.LOSS, enemyCards = listOf(evo)),
                TestRecords.record("c", 3, BattleResult.WIN, enemyCards = listOf(evo)),
            ),
        )
        assertEquals(2, stats.cards.size)
        val baseRow = stats.cards.first { !it.isEvolution }
        val evoRow = stats.cards.first { it.isEvolution }
        assertEquals(1, baseRow.appearances)
        assertEquals("AngryBarbarians", baseRow.label)
        assertEquals(2, evoRow.appearances)
        assertEquals("Evo AngryBarbarians", evoRow.label)
        assertEquals(0.5, evoRow.winRate!!, 1e-9)
    }

    @Test
    fun `loss analysis reports duration and crown distribution`() {
        val stats = BattleStatsCalculator.compute(
            listOf(
                TestRecords.record("a", 1, BattleResult.LOSS, durationSeconds = 100.0, enemyCrowns = 3),
                TestRecords.record("b", 2, BattleResult.LOSS, durationSeconds = 200.0, enemyCrowns = 1),
                TestRecords.record("c", 3, BattleResult.LOSS, durationSeconds = 300.0, enemyCrowns = 1),
            ),
        )
        assertEquals(200.0, stats.lossAnalysis.averageDurationSeconds!!, 1e-9)
        assertEquals(200.0, stats.lossAnalysis.medianDurationSeconds!!, 1e-9)
        assertEquals(listOf(1 to 2, 3 to 1), stats.lossAnalysis.crownDistribution)
    }

    @Test
    fun `longest battles are ordered by duration`() {
        val stats = BattleStatsCalculator.compute(
            listOf(
                TestRecords.record("a", 1, BattleResult.WIN, durationSeconds = 30.0),
                TestRecords.record("b", 2, BattleResult.WIN, durationSeconds = 300.0),
                TestRecords.record("c", 3, BattleResult.WIN, durationSeconds = 150.0),
            ),
        )
        assertEquals(listOf(300.0, 150.0, 30.0), stats.longest.map { it.durationSeconds })
    }

    @Test
    fun `average crowns ignores rows with no crown data`() {
        val stats = BattleStatsCalculator.compute(
            listOf(
                TestRecords.record("a", 1, BattleResult.WIN, myCrowns = 3),
                TestRecords.record("b", 2, BattleResult.WIN, myCrowns = null),
            ),
        )
        assertEquals(3.0, stats.averageCrowns!!, 1e-9)
    }

    @Test
    fun `formatters are stable`() {
        assertEquals("n/a", (null as Double?).asPercent())
        assertEquals("87.8%", 0.878.asPercent())
        assertEquals("2:37", 157.0.asClock())
        assertEquals("--:--", (null as Double?).asClock())
    }
}

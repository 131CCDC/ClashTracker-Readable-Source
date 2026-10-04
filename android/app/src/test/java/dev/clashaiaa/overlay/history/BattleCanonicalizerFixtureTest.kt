package dev.clashaiaa.overlay.history

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional private regression; the captured export is never committed. */
class BattleCanonicalizerFixtureTest {
    @Test fun `private export consolidates without changing the fixture`() {
        val path = System.getenv("CLASHTRACKER_HISTORY_FIXTURE").orEmpty()
        val self = System.getenv("CLASHTRACKER_CANONICAL_SELF")?.toLongOrNull() ?: 0L
        assumeTrue(path.isNotBlank() && self > 0 && File(path).isFile)
        val root = JSONObject(File(path).readText(Charsets.UTF_8))
        val input = root.getJSONArray("battles").let { array ->
            (0 until array.length()).map { index -> parse(array.getJSONObject(index)) }
        }
        val report = BattleCanonicalizer.consolidate(input, self)
        println("fixture raw=${report.rawCount} canonical=${report.canonicalCount} " +
            "merged=${report.mergedDuplicates} excluded=${report.excludedInvalidIdentity} unknown=${report.unknown}")
        assertEquals(137, report.rawCount)
        assertTrue(report.canonicalCount < report.rawCount)
        assertEquals(1, report.records.count {
            it.provisionalBattleId in setOf(
                "live:1791031940:71281011:67439390",
                "live:1791031945:71281011:67439390",
            ) || it.mergedSources.isNotEmpty() && it.myPlayerId == "71281011" && it.enemyPlayerId == "67439390" && it.durationTicks == 3652
        })
    }

    private fun parse(row: JSONObject): BattleRecord {
        fun deck(name: String, side: CardSide): List<BattleCard> {
            val array = row.optJSONArray(name) ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val card = array.optJSONObject(i) ?: return@mapNotNull null
                BattleCard(side, card.optInt("slot", i), card.optInt("card_id"),
                    card.optString("name"), card.optString("name_zh"),
                    card.optBoolean("is_evolution"), card.optBoolean("is_hero"),
                    card.optInt("cost", -1).takeIf { it >= 0 })
            }
        }
        fun nullableString(name: String) = row.optString(name, "").takeIf { it.isNotBlank() && it != "null" }
        fun nullableInt(name: String) = if (row.isNull(name) || !row.has(name)) null else row.optInt(name)
        fun nullableLong(name: String) = if (row.isNull(name) || !row.has(name)) null else row.optLong(name)
        fun nullableDouble(name: String) = if (row.isNull(name) || !row.has(name)) null else row.optDouble(name)
        return BattleRecord(
            battleUid = row.getString("battle_uid"), battleId = nullableString("battle_id"),
            replayId = nullableString("replay_id"), battleTime = row.getLong("battle_time"),
            startTime = nullableLong("start_time"), endTime = nullableLong("end_time"),
            durationTicks = nullableInt("duration_ticks"), durationSeconds = nullableDouble("duration_seconds"),
            mode = nullableString("mode"), arena = nullableString("arena"),
            myPlayerId = nullableString("my_player_id"), enemyPlayerId = nullableString("enemy_player_id"),
            myPlayerName = nullableString("my_player_name"), enemyPlayerName = nullableString("enemy_player_name"),
            myCrowns = nullableInt("my_crowns"), enemyCrowns = nullableInt("enemy_crowns"),
            result = BattleResult.fromWire(nullableString("result")),
            status = BattleStatus.fromWire(nullableString("status")), source = BattleSource.fromWire(nullableString("source")),
            myDeck = deck("my_deck", CardSide.SELF), enemyDeck = deck("enemy_deck", CardSide.ENEMY),
            firstTick = nullableInt("first_tick"), lastTick = nullableInt("last_tick"),
            identitySource = nullableString("identity_source"), fullBattle = row.optBoolean("full_battle"),
            nativeResultValidated = row.optBoolean("native_result_validated", row.optString("source") == "native_result"),
        )
    }
}

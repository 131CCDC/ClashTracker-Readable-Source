package dev.clashaiaa.overlay.history

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for the payload the client hands to its own game-state manager when a
 * battle-log entry is opened (message `0x6c3a` -> `setReplayData`).
 *
 * The payload is self-describing JSON. A real capture is 27 184 bytes and looks
 * like this (abridged):
 *
 * ```json
 * {"battle":{"gmt":1,"gamemode":72000006,
 *            "deck0":{"hdr":"BD01","sp":[{"d":27000013,"l":10,"el":1}, ...8 slots]},
 *            "deck1":{"hdr":"BD01","sp":[{...}, ...8 slots]},
 *            "avatar0":{"accountID.lo":900000002,"name":"EnemyExample","scr":6000,...},
 *            "avatar1":{"accountID.lo":900000001,"name":"LocalExample","scr":5876,...},
 *            "arena":54000013,"location":15000077},
 *  "endTick":3225,
 *  "cmd":[{"ct":86,"c":{"t":212,"idLo":900000001,"sel":{"os":26000043}}}, ...],
 *  "evt":[...],
 *  "rndSeed":1790545405,"time":-1}
 * ```
 *
 * Field meanings were established from the capture itself, not assumed:
 *
 *  * `deck1` / `avatar1` are the **local** side. Two independent checks agree:
 *    `avatar1`'s account is the one this tablet has learned, and the first
 *    play-card command issued by that account names a card in `deck1`.
 *  * `cmd[].ct` is the command class: `86` (`0x56`) is play-card and carries
 *    `c.sel.os`, the card id; `2` is a **tower destroyed** command and carries
 *    `c.cgid`, the tower's global id. The issuer is `c.idLo`, so crowns are
 *    counted per side from the command stream rather than guessed from the
 *    event list.
 *  * `endTick` is the last tick in the recording, and one tick is 50 ms.
 *  * The payload carries no wall-clock time (`time` is -1), so the battle is
 *    identified by its two accounts plus the deck pair.
 */
object NullsReplayParser {

    private const val TICK_MS = 50
    private const val PLAY_CARD_COMMAND = 86
    private const val TOWER_DESTROYED_COMMAND = 2

    /** True when this payload is a replay rather than a battle-log listing. */
    fun looksLikeReplay(raw: String): Boolean {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("{")) return false
        return trimmed.contains("\"battle\"") && trimmed.contains("\"deck0\"")
    }

    /**
     * @param localAccountId the account this device plays, when it is known.
     *   The replay marks the recording client as side 1, so this only has to
     *   confirm which side is local; when it is zero the side-1 convention is
     *   used and the caller can see that from the returned entry.
     */
    fun parse(raw: String, localAccountId: Long = 0L): NullsBattleEntry? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val battle = root.optJSONObject("battle") ?: return null

        val avatar0 = battle.optJSONObject("avatar0")
        val avatar1 = battle.optJSONObject("avatar1")
        val account0 = accountIdOf(avatar0)
        val account1 = accountIdOf(avatar1)

        val localIsSide1 = when {
            localAccountId == 0L -> true
            account1 == localAccountId -> true
            account0 == localAccountId -> false
            else -> true
        }
        val myAvatar = if (localIsSide1) avatar1 else avatar0
        val enemyAvatar = if (localIsSide1) avatar0 else avatar1
        val myDeck = if (localIsSide1) battle.optJSONObject("deck1") else battle.optJSONObject("deck0")
        val enemyDeck = if (localIsSide1) battle.optJSONObject("deck0") else battle.optJSONObject("deck1")
        val myAccount = if (localIsSide1) account1 else account0
        val enemyAccount = if (localIsSide1) account0 else account1

        val crowns = crownsFromCommands(root.optJSONArray("cmd"), myAccount, enemyAccount)
        val endTick = root.optInt("endTick", -1).takeIf { it > 0 }
        val result = when {
            crowns.first > crowns.second -> BattleResult.WIN
            crowns.first < crowns.second -> BattleResult.LOSS
            crowns.first == 0 && crowns.second == 0 -> BattleResult.UNKNOWN
            else -> BattleResult.DRAW
        }

        return NullsBattleEntry(
            battleId = null,
            replayId = root.optInt("rndSeed", 0).takeIf { it != 0 }?.toString(),
            battleTimeMs = null,
            mode = battle.optInt("gamemode", 0).takeIf { it != 0 }?.toString(),
            arena = battle.optInt("arena", 0).takeIf { it != 0 }?.toString(),
            myPlayerId = myAccount.takeIf { it != 0L }?.toString(),
            myPlayerName = nameOf(myAvatar),
            enemyPlayerId = enemyAccount.takeIf { it != 0L }?.toString(),
            enemyPlayerName = nameOf(enemyAvatar),
            myCards = cardsOf(myDeck),
            enemyCards = cardsOf(enemyDeck),
            myEvos = evolutionsOf(myDeck),
            enemyEvos = evolutionsOf(enemyDeck),
            myHeroes = emptyList(),
            enemyHeroes = emptyList(),
            myCrowns = crowns.first,
            enemyCrowns = crowns.second,
            result = result,
            durationSeconds = endTick?.let { it * TICK_MS / 1000.0 },
            startingTrophies = trophiesOf(myAvatar),
            endingTrophies = null,
            trophyChange = null,
            raw = raw,
        )
    }

    /**
     * The replay flattens the account id into two dotted keys
     * (`"accountID.lo": 900000001`), not a nested object. Both spellings are
     * accepted so a future serialiser change does not silently zero the id.
     */
    private fun accountIdOf(avatar: JSONObject?): Long {
        if (avatar == null) return 0L
        val flatLow = avatar.optLong("accountID.lo", 0L)
        if (flatLow != 0L) {
            val flatHigh = avatar.optLong("accountID.hi", 0L)
            return if (flatHigh != 0L) (flatHigh shl 32) or flatLow else flatLow
        }
        val nested = avatar.optJSONObject("accountID") ?: return 0L
        val high = nested.optLong("hi", 0L)
        val low = nested.optLong("lo", 0L)
        return if (high != 0L) (high shl 32) or low else low
    }

    private fun nameOf(avatar: JSONObject?): String? =
        avatar?.optString("name", "")?.takeIf { it.isNotBlank() }

    private fun trophiesOf(avatar: JSONObject?): Int? =
        avatar?.optInt("scr", -1)?.takeIf { it >= 0 }

    /** `sp[]` is the eight deck slots; `d` is the card id. */
    private fun cardsOf(deck: JSONObject?): List<Int> {
        val slots = deck?.optJSONArray("sp") ?: return emptyList()
        val cards = ArrayList<Int>(slots.length())
        for (index in 0 until slots.length()) {
            val entry = slots.optJSONObject(index) ?: continue
            val cardId = entry.optInt("d", 0)
            if (cardId > 0) cards += cardId
        }
        return cards
    }

    /**
     * `el` is the evolution level and is simply absent on a slot without one,
     * which is how the replay itself distinguishes the two forms.
     */
    private fun evolutionsOf(deck: JSONObject?): List<Int> {
        val slots = deck?.optJSONArray("sp") ?: return emptyList()
        val evolved = ArrayList<Int>(slots.length())
        for (index in 0 until slots.length()) {
            val entry = slots[index] as? JSONObject ?: continue
            if (entry.optInt("el", 0) > 0) {
                val cardId = entry.optInt("d", 0)
                if (cardId > 0) evolved += cardId
            }
        }
        return evolved
    }

    /**
     * Crowns are counted from the command stream: a `ct == 2` command is a tower
     * being destroyed and its issuer is the side that scored it. Counting the
     * issuer rather than reading a result field is what makes this independent
     * of the event list, whose encoding is not yet established.
     */
    private fun crownsFromCommands(commands: JSONArray?, myAccount: Long, enemyAccount: Long): Pair<Int, Int> {
        if (commands == null) return 0 to 0
        var mine = 0
        var theirs = 0
        for (index in 0 until commands.length()) {
            val command = commands.optJSONObject(index) ?: continue
            if (command.optInt("ct", -1) != TOWER_DESTROYED_COMMAND) continue
            val body = command.optJSONObject("c") ?: continue
            val issuer = body.optLong("idLo", 0L)
            when {
                myAccount != 0L && issuer == myAccount -> mine++
                enemyAccount != 0L && issuer == enemyAccount -> theirs++
            }
        }
        return mine to theirs
    }

    /** Every play-card command, in order, as (tick, issuer, card id). */
    fun plays(raw: String): List<Triple<Int, Long, Int>> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val commands = root.optJSONArray("cmd") ?: return emptyList()
        val plays = ArrayList<Triple<Int, Long, Int>>(commands.length())
        for (index in 0 until commands.length()) {
            val command = commands.optJSONObject(index) ?: continue
            if (command.optInt("ct", -1) != PLAY_CARD_COMMAND) continue
            val body = command.optJSONObject("c") ?: continue
            val cardId = body.optJSONObject("sel")?.optInt("os", 0) ?: 0
            if (cardId <= 0) continue
            plays += Triple(body.optInt("t", -1), body.optLong("idLo", 0L), cardId)
        }
        return plays
    }
}

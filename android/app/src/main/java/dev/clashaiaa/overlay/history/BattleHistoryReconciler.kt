package dev.clashaiaa.overlay.history

/**
 * The only boundary allowed to turn game-owned history into a finalized row.
 * Live capture contributes timing/telemetry after a conservative match, but
 * never identity, decks, crowns, or outcome.
 */
object BattleHistoryReconciler {

    data class Outcome(
        val record: BattleRecord? = null,
        val matchedLiveUid: String? = null,
        val rejection: String? = null,
    )

    fun finalize(
        authoritative: BattleRecord,
        pending: List<BattleRecord> = emptyList(),
        authoritativeTimeKnown: Boolean = true,
    ): Outcome {
        val canonical = authoritative.copy(
            battleUid = canonicalUid(authoritative, includeBattleTime = authoritativeTimeKnown),
        )
        val rejection = rejectionReason(canonical)
        if (rejection != null) return Outcome(rejection = rejection)

        val live = pending
            .asSequence()
            .map { it to matchScore(canonical, it, authoritativeTimeKnown) }
            .filter { it.second >= MIN_MATCH_SCORE }
            .maxWithOrNull(compareBy<Pair<BattleRecord, Int>> { it.second }.thenBy { it.first.battleTime })
            ?.first

        val finalized = canonical.copy(
            battleTime = if (!authoritativeTimeKnown && live != null) live.battleTime else canonical.battleTime,
            startTime = live?.startTime ?: canonical.startTime,
            endTime = when {
                canonical.durationSeconds != null && live?.startTime != null ->
                    live.startTime + (canonical.durationSeconds * 1000).toLong()
                else -> live?.endTime ?: canonical.endTime
            },
            durationTicks = canonical.durationTicks ?: live?.durationTicks,
            durationSeconds = canonical.durationSeconds ?: live?.durationSeconds,
            status = BattleStatus.COMPLETE,
            source = BattleSource.NULLS_HISTORY,
            firstTick = live?.firstTick,
            lastTick = live?.lastTick,
            identitySource = "GAME_HISTORY",
            fullBattle = live?.fullBattle ?: false,
        )
        return Outcome(record = finalized, matchedLiveUid = live?.battleUid)
    }

    fun isFinalized(record: BattleRecord): Boolean = rejectionReason(record) == null

    fun canonicalUid(record: BattleRecord, includeBattleTime: Boolean = true): String {
        cleanId(record.battleId)?.let { return "nulls:$it" }
        cleanId(record.replayId)?.let { return "nulls-replay:$it" }
        val material = buildString {
            append(cleanId(record.myPlayerId) ?: "?")
            append('|').append(cleanId(record.enemyPlayerId) ?: "?")
            append('|').append(deckSignature(record.myDeck))
            append('|').append(deckSignature(record.enemyDeck))
            append('|').append(record.myCrowns ?: -1)
            append('|').append(record.enemyCrowns ?: -1)
            append('|').append(record.result.wire)
            append('|').append(record.mode?.trim()?.lowercase() ?: "?")
            append('|').append(record.arena?.trim()?.lowercase() ?: "?")
            // Time is one part of a rich fallback, never the identity by itself.
            append('|').append(if (includeBattleTime) record.battleTime / 10_000L else "?")
        }
        return "nulls-fb:${fnv1a(material)}"
    }

    private fun rejectionReason(record: BattleRecord): String? {
        if (record.source != BattleSource.NULLS_HISTORY) return "not_game_history"
        if (record.status != BattleStatus.COMPLETE) return "status_not_complete"
        if (!record.decided) return "outcome_not_final"
        val me = cleanId(record.myPlayerId) ?: return "my_player_id_missing"
        val enemy = cleanId(record.enemyPlayerId) ?: return "enemy_player_id_missing"
        if (me == enemy) return "player_ids_equal"
        if (!validDeck(record.myDeck, CardSide.SELF)) return "my_deck_invalid"
        if (!validDeck(record.enemyDeck, CardSide.ENEMY)) return "enemy_deck_invalid"
        if (record.myCrowns == null || record.enemyCrowns == null ||
            record.myCrowns < 0 || record.enemyCrowns < 0
        ) return "crowns_invalid"
        return null
    }

    private fun validDeck(deck: List<BattleCard>, side: CardSide): Boolean =
        deck.size == 8 &&
            deck.all { it.side == side && it.slot in 0..7 && it.cardId > 0 } &&
            deck.map { it.slot }.toSet().size == 8 &&
            deck.map { it.cardId }.toSet().size == 8

    private fun matchScore(auth: BattleRecord, live: BattleRecord, authTimeKnown: Boolean): Int {
        if (live.source != BattleSource.LIVE_CAPTURE) return Int.MIN_VALUE
        var score = 0
        val authMe = cleanId(auth.myPlayerId)
        val authEnemy = cleanId(auth.enemyPlayerId)
        val liveMe = cleanId(live.myPlayerId)
        val liveEnemy = cleanId(live.enemyPlayerId)
        val idsMatch = authMe != null && authEnemy != null && authMe == liveMe && authEnemy == liveEnemy
        if (idsMatch) score += 100

        val myDeckMatches = deckSignature(auth.myDeck) == deckSignature(live.myDeck)
        val enemyDeckMatches = deckSignature(auth.enemyDeck) == deckSignature(live.enemyDeck)
        if (myDeckMatches) score += 25
        if (enemyDeckMatches) score += 25

        val durationDelta = if (auth.durationSeconds != null && live.durationSeconds != null) {
            kotlin.math.abs(auth.durationSeconds - live.durationSeconds)
        } else null
        if (durationDelta != null && durationDelta <= 15.0) score += 10

        if (authTimeKnown && kotlin.math.abs(auth.battleTime - live.battleTime) <= 5 * 60_000L) score += 10
        // Do not match on time or opponent alone. Two full decks are strong
        // enough when the live identity was unresolved.
        return if (idsMatch || (myDeckMatches && enemyDeckMatches)) score else Int.MIN_VALUE
    }

    private fun cleanId(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "0" && !it.equals("null", true) && !it.equals("unknown", true) }

    private fun deckSignature(deck: List<BattleCard>): String = deck
        .map { it.cardId }
        .filter { it > 0 }
        .sorted()
        .joinToString(",")

    private fun fnv1a(value: String): String {
        var hash = 0xcbf29ce484222325uL
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF).toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    private const val MIN_MATCH_SCORE = 50
}

/** Bounded process-local telemetry. It is deliberately not a database. */
object BattleWorkingSessions {
    private const val MAX_COMPLETED = 8
    private var active: BattleRecord? = null
    private val completed = LinkedHashMap<String, BattleRecord>()

    @Synchronized
    fun observe(event: BattleRecorder.Event) {
        when (event) {
            is BattleRecorder.Event.Started -> active = event.record
            is BattleRecorder.Event.Finished -> {
                if (active?.battleUid == event.record.battleUid) active = null
                completed[event.record.battleUid] = event.record
                while (completed.size > MAX_COMPLETED) {
                    completed.remove(completed.keys.first())
                }
            }
        }
    }

    @Synchronized
    fun snapshot(): List<BattleRecord> = completed.values.toList()

    @Synchronized
    fun consume(uid: String?) {
        if (uid != null) completed.remove(uid)
    }

    @Synchronized
    fun clear() {
        active = null
        completed.clear()
    }
}

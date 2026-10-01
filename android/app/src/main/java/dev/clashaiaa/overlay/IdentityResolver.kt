package dev.clashaiaa.overlay

/** How the local account was decided. Only the first four are proof. */
enum class IdentitySource {
    /** The account id typed into the settings, present in this battle. */
    CONFIGURED,

    /** Confirmed from this device's own local-input evidence. */
    OBSERVED,

    /** Confirmed in an earlier session and cached on the device. */
    LEARNED,

    /** The cached local deck matched exactly one of the two players. */
    DECK,

    /**
     * Nothing proved the identity yet, so the panel falls back to the configured
     * seat. It is drawn from the first frame and labelled, never silent.
     */
    SEAT_FALLBACK,

    UNKNOWN,
}

/** The local player of the current battle. */
data class LocalIdentity(
    val accountId: Long = 0L,
    val owner: Int = -1,
    val source: IdentitySource = IdentitySource.UNKNOWN,
    /** False when the installed probe cannot report local input at all. */
    val autoDetectAvailable: Boolean = true,
) {
    /** True only when the identity came from evidence, not from a guess. */
    val verified: Boolean
        get() = source == IdentitySource.CONFIGURED ||
            source == IdentitySource.OBSERVED ||
            source == IdentitySource.LEARNED ||
            source == IdentitySource.DECK

    /** Whether the opponent columns may be rendered at all. */
    val usable: Boolean
        get() = owner in 0..1 && (verified || source == IdentitySource.SEAT_FALLBACK)

    val label: String
        get() = when (source) {
            IdentitySource.CONFIGURED, IdentitySource.OBSERVED, IdentitySource.LEARNED ->
                "本机 $accountId"
            IdentitySource.DECK -> "本机 $accountId（按卡组）"
            IdentitySource.SEAT_FALLBACK ->
                if (autoDetectAvailable) "未确认·按座位 $owner" else "未确认·按座位 $owner·探针无自动识别"
            IdentitySource.UNKNOWN -> "身份未确认"
        }

    companion object {
        val UNKNOWN = LocalIdentity()
    }
}

/**
 * Which account is *this* device in the current battle?
 *
 * `players[].owner` is a world seat index, not a view-relative one, and the
 * server decides it per match. In the captured live corpus
 * (`cmd_audit/ghost-runs/run2/frames-A.jsonl`) the same tablet sat at seat 1 for
 * 5344 battle frames and at seat 0 for 2666 frames of a single session, so any
 * "the local player is seat 0" rule displays the local hand as the opponent's
 * roughly half of the time.
 *
 * The Windows HUD never had this problem because it is told the account id once
 * (`settings.local.json` -> `account_id`) and the probe publishes both seats'
 * `accountId` every frame. This class does the same, and adds device-local
 * evidence so the id does not have to be typed:
 *
 *  - `client_input_runtime.events[]` — one edge per play-card command submitted
 *    through this client's own UI, with the game tick at submission.
 *  - the GHOST feed — decoded wire commands with `issuer` and `server_tick`. A
 *    local command is echoed with an unchanged `server_tick`; on the live corpus
 *    63 of 63 local commands matched an edge tick exactly and 0 of 7 opponent
 *    commands did.
 *  - the deploy a local command causes, 20–24 ticks after its edge (live corpus:
 *    84 local versus 10 opponent spawns in that window), one vote per edge.
 *
 * Both signals come from the *same* play, so when they name the same account the
 * identity locks immediately on that first play; a single signal needs its own
 * stricter threshold (2 exact matches, or 3 seat votes with a 2x margin) before
 * it is cached for later battles.
 *
 * The local deck is remembered once the identity is proven, so a later battle —
 * or a different account on the same tablet — is identified from the first frame
 * by matching the deck. `self_hint` is deliberately unused: the live audit found
 * both clients recording the same first issuer.
 */
class IdentityResolver(
    private val notifyLearned: (Long) -> Unit = {},
    private val notifyLearnedDeck: (String) -> Unit = {},
) {
    /** Account id typed into the settings; wins when it is in this battle. */
    var configuredAccountId: Long = 0L

    /** Seat typed into the settings; the provisional fallback until proven. */
    var configuredOwner: Int = 0

    /** A previously confirmed local account, cached on this device. */
    var learnedAccountId: Long = 0L
        private set

    /** The cached local deck as a sorted card-id signature. */
    var learnedDeckSignature: String = ""
        private set

    /** True once the installed probe has reported a working input observer. */
    var probeSupportsLocalInput: Boolean = false
        private set

    private var sessionAccountId = 0L
    private var lastPlayers: List<ProbePlayer> = emptyList()
    private var lastTick = -1
    private var battleActive = false

    /** Ticks at which this device submitted a play-card command. */
    private val edges = LinkedHashSet<Int>()

    /** For an edge tick, the issuers of the GHOST events that carry it. */
    private val tickIssuers = HashMap<Int, MutableSet<Long>>()

    /** For an edge tick, the seats of the objects that appeared right after it. */
    private val edgeSpawnOwners = HashMap<Int, MutableSet<Int>>()

    private val seenEntityIds = HashSet<Int>()

    /**
     * Highest object id this battle has produced in each id range. The probe's
     * entity list is intermittently truncated, so an object can vanish for a
     * frame and come back looking new; ids only ever climb, so anything at or
     * below the mark is not a new spawn.
     */
    private val entityIdHighWater = HashMap<Int, Int>()

    fun seedLearned(accountId: Long) {
        if (accountId > 0L) learnedAccountId = accountId
    }

    fun seedLearnedDeck(signature: String) {
        if (signature.isNotBlank()) learnedDeckSignature = signature
    }

    /** Feed one `nulls-live.v3` snapshot. */
    fun onFrame(state: BattleState) {
        if (!state.inBattle || state.stale || state.tick < 0) {
            endBattle()
            return
        }
        probeSupportsLocalInput = probeSupportsLocalInput || state.localInputSupported
        if (!battleActive || state.tick < lastTick) startBattle()
        battleActive = true
        lastTick = state.tick
        lastPlayers = state.players

        edges.addAll(state.localInputTicks)
        pruneEdges(state.tick)
        voteOnSpawns(state)
        evaluate()
        rememberDeck()
    }

    /** Feed one GHOST snapshot (only needed for the exact-tick join). */
    fun onGhost(feed: GhostFeed) {
        if (!feed.inBattle || feed.tick < 0) return
        for (event in feed.events) {
            val tick = event.serverTick
            if (tick < 0 || event.issuerAccountId == 0L) continue
            if (!edges.contains(tick)) continue
            // The event must belong to this battle: a live record is published
            // before its execute tick and expires shortly after it.
            val age = feed.tick - tick
            if (age < 0 || age > GHOST_EVENT_MAX_AGE_TICKS) continue
            if (feed.ownerOf(event.issuerAccountId) == null) continue
            tickIssuers.getOrPut(tick) { HashSet() }.add(event.issuerAccountId)
        }
        evaluate()
    }

    /** The identity to render with; requires the current battle for the seat. */
    fun identity(state: BattleState?): LocalIdentity {
        val players = state?.players ?: emptyList()
        fun playerOf(account: Long): ProbePlayer? =
            players.firstOrNull { account != 0L && sameAccount(it.accountId, account) }

        playerOf(configuredAccountId)?.let {
            return LocalIdentity(it.accountId, it.owner, IdentitySource.CONFIGURED, probeSupportsLocalInput)
        }
        playerOf(sessionAccountId)?.let {
            return LocalIdentity(it.accountId, it.owner, IdentitySource.OBSERVED, probeSupportsLocalInput)
        }
        if (learnedAccountId != sessionAccountId) {
            playerOf(learnedAccountId)?.let {
                return LocalIdentity(it.accountId, it.owner, IdentitySource.LEARNED, probeSupportsLocalInput)
            }
        }
        deckSeat(players)?.let { seat ->
            val account = players.firstOrNull { it.owner == seat }?.accountId ?: 0L
            if (account != 0L) {
                return LocalIdentity(account, seat, IdentitySource.DECK, probeSupportsLocalInput)
            }
        }
        // Nothing proved it yet: fall back to the configured seat so the panel
        // shows live data from the first frame, labelled as unconfirmed.
        val owner = configuredOwner.coerceIn(0, 1)
        if (players.any { it.owner == owner }) {
            return LocalIdentity(0L, owner, IdentitySource.SEAT_FALLBACK, probeSupportsLocalInput)
        }
        return LocalIdentity(autoDetectAvailable = probeSupportsLocalInput)
    }

    /** Forget the session's evidence; the cached account and deck stay. */
    fun reset() {
        endBattle()
        sessionAccountId = 0L
    }

    private fun startBattle() {
        edges.clear()
        tickIssuers.clear()
        edgeSpawnOwners.clear()
        seenEntityIds.clear()
        entityIdHighWater.clear()
        lastTick = -1
    }

    private fun endBattle() {
        if (!battleActive && edges.isEmpty()) return
        battleActive = false
        startBattle()
    }

    private fun pruneEdges(tick: Int) {
        val oldest = tick - EDGE_WINDOW_TICKS
        val iterator = edges.iterator()
        while (iterator.hasNext()) {
            val edge = iterator.next()
            if (edge >= oldest) break
            iterator.remove()
            tickIssuers.remove(edge)
            // edgeSpawnOwners is the battle's vote ledger, not a match window:
            // votes must outlive the tick they came from or a long battle could
            // never accumulate three of them.
        }
    }

    private fun voteOnSpawns(state: BattleState) {
        for (entity in state.entities) {
            if (entity.owner !in 0..1) continue
            // Towers and other non-card objects carry card id -1; they are not
            // deployments, and on the live capture they were the only source of
            // a false seat vote.
            if (entity.cardId <= 0) continue
            if (!seenEntityIds.add(entity.id)) continue
            val range = entity.id / ENTITY_ID_RANGE
            val highWater = entityIdHighWater[range] ?: 0
            if (entity.id <= highWater) continue
            entityIdHighWater[range] = entity.id
            for (tick in edges) {
                val lag = state.tick - tick
                if (lag < SPAWN_LAG_MIN_TICKS || lag > SPAWN_LAG_MAX_TICKS) continue
                edgeSpawnOwners.getOrPut(tick) { HashSet() }.add(entity.owner)
            }
        }
    }

    /**
     * Lock the session identity as soon as the evidence allows, and cache the
     * account once a single signal is strict on its own.
     */
    private fun evaluate() {
        val ghost = ghostVerdict()
        val seat = seatVerdict()
        if (sessionAccountId == 0L) {
            val agreed = ghost.accountId != 0L && ghost.accountId == seat.accountId
            sessionAccountId = when {
                agreed -> ghost.accountId
                ghost.strict -> ghost.accountId
                seat.strict -> seat.accountId
                else -> 0L
            }
        }
        if (sessionAccountId == 0L || learnedAccountId == sessionAccountId) return
        val confirmedStrictly = (ghost.strict && ghost.accountId == sessionAccountId) ||
            (seat.strict && seat.accountId == sessionAccountId)
        if (confirmedStrictly) {
            learnedAccountId = sessionAccountId
            notifyLearned(sessionAccountId)
        }
    }

    /** Store the local seat's deck once the identity is proven, for later battles. */
    private fun rememberDeck() {
        val proven = sessionAccountId.takeIf { it != 0L } ?: return
        val seat = lastPlayers.firstOrNull { sameAccount(it.accountId, proven) }?.owner ?: return
        val signature = deckSignature(lastPlayers.firstOrNull { it.owner == seat }) ?: return
        if (signature == learnedDeckSignature) return
        learnedDeckSignature = signature
        notifyLearnedDeck(signature)
    }

    /** The one seat whose deck matches the cached signature, if it is unique. */
    private fun deckSeat(players: List<ProbePlayer>): Int? {
        if (learnedDeckSignature.isEmpty() || players.size < 2) return null
        val matches = players.filter { deckSignature(it) == learnedDeckSignature }
        if (matches.size != 1) return null
        return matches.single().owner.takeIf { it in 0..1 }
    }

    private fun deckSignature(player: ProbePlayer?): String? {
        val ids = player?.deck?.filter { it > 0 }?.sorted() ?: return null
        if (ids.size < DECK_MIN_CARDS) return null
        return ids.joinToString(",")
    }

    private class Verdict(val accountId: Long, val votes: Int, val strict: Boolean) {
        companion object {
            val NONE = Verdict(0L, 0, false)
        }
    }

    /** One vote per edge, and only when that tick carried exactly one issuer. */
    private fun ghostVerdict(): Verdict {
        val votes = HashMap<Long, Int>()
        for ((tick, issuers) in tickIssuers) {
            if (!edges.contains(tick) || issuers.size != 1) continue
            votes.merge(issuers.first(), 1, Int::plus)
        }
        val best = votes.maxByOrNull { it.value } ?: return Verdict.NONE
        val runnerUp = votes.filterKeys { it != best.key }.values.maxOrNull() ?: 0
        val strict = best.value >= GHOST_VOTES_TO_CONFIRM && best.value > runnerUp
        return Verdict(best.key, best.value, strict)
    }

    /** One vote per edge, and only when that edge saw exactly one seat deploy. */
    private fun seatVerdict(): Verdict {
        val votes = HashMap<Int, Int>()
        for (owners in edgeSpawnOwners.values) {
            if (owners.size != 1) continue
            votes.merge(owners.first(), 1, Int::plus)
        }
        val best = votes.maxByOrNull { it.value } ?: return Verdict.NONE
        val runnerUp = votes.filterKeys { it != best.key }.values.maxOrNull() ?: 0
        val strict = best.value >= SEAT_VOTES_TO_CONFIRM && best.value >= 2 * runnerUp
        val account = lastPlayers.firstOrNull { it.owner == best.key }?.accountId ?: 0L
        return Verdict(account, best.value, strict && account != 0L)
    }

    /**
     * The GHOST feed prints the issuer as a 32-bit value while `players[]`
     * prints the full 64-bit account id, so compare the low half as well.
     */
    private fun sameAccount(left: Long, right: Long): Boolean =
        left == right || (left and 0xFFFFFFFFL) == right || left == (right and 0xFFFFFFFFL)

    private companion object {
        /** 6 s of edges; the probe reports its ring within a 100-tick window. */
        const val EDGE_WINDOW_TICKS = 120

        /** `exec_tick = server_tick + 20`, so the deploy lands in this window. */
        const val SPAWN_LAG_MIN_TICKS = 20
        const val SPAWN_LAG_MAX_TICKS = 24

        /** Exact tick equality measured clean; two independent plays confirm. */
        const val GHOST_VOTES_TO_CONFIRM = 2

        /** Three card plays with a 2x margin over the other seat. */
        const val SEAT_VOTES_TO_CONFIRM = 3

        /** A live ghost record is older than this only across a battle change. */
        const val GHOST_EVENT_MAX_AGE_TICKS = 60

        /** Object ids come from per-class counters a million apart. */
        const val ENTITY_ID_RANGE = 1_000_000

        /** Fewer known cards than this is not a deck worth matching. */
        const val DECK_MIN_CARDS = 6
    }
}

package dev.clashaiaa.overlay.history

/**
 * Builds one transient working session out of the live probe stream.
 *
 * The state machine is deliberately small and total:
 *
 * ```
 * BattleStart -> BattleRunning -> NativeFinalized -> BattleEnd
 * ```
 *
 * Nothing here touches the database. The service persists the crash-safe start
 * row and the one-shot native finalization on its history executor.
 *
 * Everything the probe cannot attest stays `null`. In particular the live
 * stream carries no game mode, no arena and no player names, so those columns
 * are filled only by a later Null's history import.
 */
class BattleRecorder(
    private val names: CardNamer,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed class Event {
        /** First frame of a battle: create/replace the in-memory working session. */
        data class Started(val record: BattleRecord) : Event()

        /** Validated game-owned native result; emitted exactly once per battle. */
        data class Finalized(val record: BattleRecord) : Event()

        /** Capture ended: diagnostic only; it never finalizes a database row. */
        data class Finished(val record: BattleRecord) : Event()
    }

    private class Slot(
        var cardId: Int,
        var evolution: Boolean,
        var hero: Boolean,
        var cost: Int?,
    )

    private class Live(
        val uid: String,
        val startWallMs: Long,
        var myOwner: Int,
        var identitySource: String,
        var identityVerified: Boolean,
        val accounts: MutableMap<Int, Long>,
        val slots: MutableMap<Int, MutableMap<Int, Slot>>,
        var firstTick: Int,
        var lastTick: Int,
        var myCrowns: Int,
        var enemyCrowns: Int,
        var decidedTick: Int?,
        var towers: List<FrameTower>,
        var lastFrameWallMs: Long,
        var lastRaw: String?,
        var winnerOwner: Int? = null,
        var nativeResultRaw: Int? = null,
        var nativeResultValidated: Boolean = false,
        var finalizedEmitted: Boolean = false,
    )

    private var live: Live? = null
    private var finalizedFrameKey: String? = null

    /** The battle currently being recorded, if any. */
    val active: Boolean get() = live != null

    /** Stable uid of the active battle, used to attach semantic event rows. */
    val activeBattleUid: String? get() = live?.uid

    /** Accounts in the active frame, used to keep pre-frame Ghost rows battle-local. */
    val activeAccountIds: Set<Long>
        get() = live?.accounts?.values?.filterTo(LinkedHashSet()) { it != 0L }.orEmpty()

    /**
     * Feed one probe frame.
     *
     * @param localOwner the seat this device plays, already resolved by the
     *   HUD's own identity evidence.
     * @param identitySource how that seat was decided; stored so a guessed seat
     *   can never be read back as proof.
     */
    fun onFrame(
        frame: BattleFrame,
        localOwner: Int,
        identitySource: String,
        identityVerified: Boolean = verifiedIdentitySource(identitySource),
    ): List<Event> {
        val now = clock()
        val events = ArrayList<Event>(2)

        if (!frame.inBattle || frame.stale || frame.tick < 0) {
            finish(events)
            return events
        }

        val current = live
        if (current == null) {
            val key = finalFrameKey(frame)
            if (key != null && key == finalizedFrameKey) return events
            if (!frame.finalized || frame.tick <= FULL_BATTLE_MAX_FIRST_TICK) finalizedFrameKey = null
            val started = start(frame, localOwner, identitySource, identityVerified, now)
            events += Event.Started(toRecord(started, Kind.STARTED))
            maybeFinalize(started, frame)?.let(events::add)
            return events
        }

        val restarted = frame.tick < current.lastTick ||
            now - current.lastFrameWallMs > RESTART_GAP_MS ||
            accountsChanged(current, frame)
        if (restarted) {
            finish(events)
            val started = start(frame, localOwner, identitySource, identityVerified, now)
            events += Event.Started(toRecord(started, Kind.STARTED))
            maybeFinalize(started, frame)?.let(events::add)
            return events
        }

        update(current, frame, localOwner, identitySource, identityVerified, now)
        maybeFinalize(current, frame)?.let(events::add)
        return events
    }

    /**
     * Called when no frame has arrived for a while, so a battle whose probe
     * link died is closed as a working session instead of becoming a fake row.
     */
    fun onIdle(): List<Event> {
        val events = ArrayList<Event>(1)
        val current = live ?: return events
        if (clock() - current.lastFrameWallMs > IDLE_FINISH_MS) finish(events)
        return events
    }

    /** Drop the in-memory session without writing; used when the user clears state. */
    fun reset() {
        live = null
        finalizedFrameKey = null
    }

    private fun start(
        frame: BattleFrame,
        localOwner: Int,
        identitySource: String,
        identityVerified: Boolean,
        now: Long,
    ): Live {
        val owner = if (localOwner in 0..1 && frame.player(localOwner) != null) {
            localOwner
        } else {
            frame.players.firstOrNull()?.owner ?: 0
        }
        val accounts = HashMap<Int, Long>()
        for (player in frame.players) accounts[player.owner] = player.accountId
        val slots = HashMap<Int, MutableMap<Int, Slot>>()
        for (player in frame.players) {
            val forPlayer = LinkedHashMap<Int, Slot>()
            for (card in player.deck) {
                forPlayer[card.slot] = Slot(
                    cardId = card.cardId,
                    evolution = card.activeForm == 1 || card.evolutionProgress > 0,
                    hero = card.activeForm == 2,
                    cost = card.selectedCost,
                )
            }
            slots[player.owner] = forPlayer
        }
        val startWallMs = now - frame.tick * TICK_MS
        val session = Live(
            uid = "live:${startWallMs / 1000}:${accounts[owner] ?: 0L}:${accounts[1 - owner] ?: 0L}",
            startWallMs = startWallMs,
            myOwner = owner,
            identitySource = identitySource,
            identityVerified = identityVerified && localOwner in 0..1,
            accounts = accounts,
            slots = slots,
            firstTick = frame.tick,
            lastTick = frame.tick,
            myCrowns = crownsFor(frame, owner),
            enemyCrowns = crownsFor(frame, 1 - owner),
            decidedTick = null,
            towers = frame.towers,
            lastFrameWallMs = now,
            lastRaw = null,
        )
        live = session
        update(session, frame, localOwner, identitySource, identityVerified, now)
        return session
    }

    private fun update(
        session: Live,
        frame: BattleFrame,
        localOwner: Int,
        identitySource: String,
        identityVerified: Boolean,
        now: Long,
    ) {
        if (identityVerified && localOwner in 0..1 && frame.player(localOwner) != null) {
            session.myOwner = localOwner
            session.identitySource = identitySource
            session.identityVerified = true
        }
        session.lastTick = frame.tick
        session.lastFrameWallMs = now
        session.towers = frame.towers
        session.myCrowns = maxOf(session.myCrowns, crownsFor(frame, session.myOwner))
        session.enemyCrowns = maxOf(session.enemyCrowns, crownsFor(frame, 1 - session.myOwner))

        for (player in frame.players) {
            session.accounts[player.owner] = player.accountId
            val forPlayer = session.slots.getOrPut(player.owner) { LinkedHashMap() }
            for (card in player.deck) {
                val slot = forPlayer.getOrPut(card.slot) {
                    Slot(card.cardId, false, false, card.selectedCost)
                }
                slot.cardId = card.cardId
                // Evolution and hero are loadout state, so they are latched from
                // observed evidence only. A form-1 variant merely means the game
                // has an evolution for this card, which every deck would report.
                if (card.activeForm == 1 || card.evolutionProgress > 0) slot.evolution = true
                if (card.activeForm == 2) slot.hero = true
                if (card.selectedCost != null) slot.cost = card.selectedCost
            }
        }
    }

    private fun maybeFinalize(session: Live, frame: BattleFrame): Event.Finalized? {
        if (session.finalizedEmitted || !frame.resultValidated || !frame.finalized) return null
        session.nativeResultRaw = frame.resultRaw
        session.nativeResultValidated = true
        session.decidedTick = session.decidedTick ?: frame.tick
        val winner = frame.resultRaw?.takeIf { it in 0..1 } ?: return null
        session.finalizedEmitted = true
        session.winnerOwner = winner
        finalizedFrameKey = finalFrameKey(frame)
        return Event.Finalized(toRecord(session, Kind.FINALIZED))
    }

    private fun finish(events: MutableList<Event>) {
        val session = live ?: return
        live = null
        events += Event.Finished(toRecord(session, Kind.FINISHED))
    }

    private fun accountsChanged(session: Live, frame: BattleFrame): Boolean {
        for (player in frame.players) {
            val known = session.accounts[player.owner] ?: 0L
            if (known != 0L && player.accountId != 0L && known != player.accountId) return true
        }
        return false
    }

    private fun crownsFor(frame: BattleFrame, owner: Int): Int =
        if (owner in frame.crowns.indices) frame.crowns[owner] else 0

    private fun finalFrameKey(frame: BattleFrame): String? {
        if (!frame.finalized || !frame.resultValidated || frame.resultRaw !in 0..1) return null
        val accounts = frame.players.sortedBy { it.owner }.joinToString(",") { "${it.owner}:${it.accountId}" }
        val decks = frame.players.sortedBy { it.owner }.joinToString("|") { player ->
            player.deck.map { it.cardId }.filter { it > 0 }.sorted().joinToString(",")
        }
        return "$accounts|$decks|${frame.crowns.joinToString(",")}|${frame.tick}|${frame.resultRaw}"
    }

    private fun toRecord(session: Live, kind: Kind): BattleRecord {
        val myOwner = session.myOwner
        val enemyOwner = 1 - myOwner
        val endTick = if (kind == Kind.FINALIZED) session.decidedTick!! else session.decidedTick ?: session.lastTick
        val durationTicks = endTick.coerceAtLeast(0)
        val nativeComplete = kind == Kind.FINALIZED
        val nativeRaw = session.nativeResultRaw
        val unsupportedNativeResult = kind == Kind.FINISHED &&
            session.nativeResultValidated && (nativeRaw == null || nativeRaw !in 0..1)
        val result = if (nativeComplete && session.identityVerified) {
            if (session.winnerOwner == myOwner) BattleResult.WIN else BattleResult.LOSS
        } else if (nativeComplete) {
            BattleResult.UNKNOWN
        } else if (unsupportedNativeResult) {
            BattleResult.UNKNOWN
        } else {
            BattleResult.INCOMPLETE
        }

        val myDeck = deckOf(session, myOwner)
        val enemyDeck = deckOf(session, enemyOwner)
        val archetype = ArchetypeClassifier.classify(enemyDeck.map { it.cardId })

        return BattleRecord(
            battleUid = session.uid,
            battleTime = session.startWallMs,
            startTime = session.startWallMs,
            endTime = if (kind != Kind.STARTED) session.startWallMs + durationTicks * TICK_MS else null,
            durationTicks = if (kind != Kind.STARTED) durationTicks else null,
            durationSeconds = if (kind != Kind.STARTED) durationTicks * TICK_MS / 1000.0 else null,
            myPlayerId = session.accounts[myOwner]?.takeIf { it != 0L }?.toString(),
            enemyPlayerId = session.accounts[enemyOwner]?.takeIf { it != 0L }?.toString(),
            myCrowns = session.myCrowns,
            enemyCrowns = session.enemyCrowns,
            // Crowns and scene exit never decide the outcome. COMPLETE is only
            // emitted for a validated/finalized native winner in {0, 1}.
            result = result,
            status = if (nativeComplete) BattleStatus.COMPLETE else BattleStatus.INCOMPLETE,
            myDeck = myDeck,
            enemyDeck = enemyDeck,
            enemyArchetype = archetype.archetype,
            enemyArchetypeSubtype = archetype.subtype,
            enemyArchetypeConfidence = archetype.confidence,
            source = if (nativeComplete) BattleSource.NATIVE_RESULT else BattleSource.LIVE_CAPTURE,
            firstTick = session.firstTick,
            lastTick = endTick,
            identitySource = session.identitySource,
            fullBattle = session.firstTick <= FULL_BATTLE_MAX_FIRST_TICK,
            winnerOwner = session.winnerOwner,
            nativeResultRaw = session.nativeResultRaw,
            nativeResultValidated = session.nativeResultValidated,
        )
    }

    private fun deckOf(session: Live, owner: Int): List<BattleCard> {
        val slots = session.slots[owner] ?: return emptyList()
        return slots.entries.sortedBy { it.key }.map { (slot, state) ->
            names.card(
                side = if (owner == session.myOwner) CardSide.SELF else CardSide.ENEMY,
                slot = slot,
                cardId = state.cardId,
                isEvolution = state.evolution,
                isHero = state.hero,
                cost = state.cost,
            )
        }
    }

    private companion object {
        enum class Kind { STARTED, FINALIZED, FINISHED }

        fun verifiedIdentitySource(source: String): Boolean = source == "CONFIGURED" ||
            source == "OBSERVED" || source == "LEARNED" || source == "DECK"
        /** One probe tick. `TICK_SECONDS = 0.05` in the bridge's own config. */
        const val TICK_MS = 50L

        /** A frame gap longer than this is a new battle, not a slow frame. */
        const val RESTART_GAP_MS = 6_000L

        /** No frame for this long ends the session and persists what was seen. */
        const val IDLE_FINISH_MS = 4_000L

        /** Tick 0..5 still counts as "this device saw the whole battle". */
        const val FULL_BATTLE_MAX_FIRST_TICK = 5

    }
}

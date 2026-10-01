package dev.clashaiaa.overlay.history

/**
 * Builds one transient working session out of the live probe stream.
 *
 * The state machine is deliberately small and total:
 *
 * ```
 * BattleStart -> BattleRunning -> BattleEnd -> WaitForAuthoritativeHistory
 * ```
 *
 * These records are telemetry, never final history. Nothing here touches the
 * database: the service keeps the latest session in memory until a game-owned
 * Battle Log payload can finalize it.
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

        /** Capture ended: retain as a diagnostic/reconciliation candidate only. */
        data class Finished(val record: BattleRecord) : Event()
    }

    private class Slot(
        var cardId: Int,
        var evolution: Boolean,
        var hero: Boolean,
        var cost: Int?,
    )

    private class Live(
        val startWallMs: Long,
        val myOwner: Int,
        val identitySource: String,
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
    ) {
        val uid: String
            get() {
                val me = accounts[myOwner] ?: 0L
                val enemy = accounts[1 - myOwner] ?: 0L
                return "live:${startWallMs / 1000}:$me:$enemy"
            }
    }

    private var live: Live? = null

    /** The battle currently being recorded, if any. */
    val active: Boolean get() = live != null

    /**
     * Feed one probe frame.
     *
     * @param localOwner the seat this device plays, already resolved by the
     *   HUD's own identity evidence.
     * @param identitySource how that seat was decided; stored so a guessed seat
     *   can never be read back as proof.
     */
    fun onFrame(frame: BattleFrame, localOwner: Int, identitySource: String): List<Event> {
        val now = clock()
        val events = ArrayList<Event>(2)

        if (!frame.inBattle || frame.stale || frame.tick < 0) {
            finish(events)
            return events
        }

        val current = live
        if (current == null) {
            events += start(frame, localOwner, identitySource, now)
            return events
        }

        val restarted = frame.tick < current.lastTick ||
            now - current.lastFrameWallMs > RESTART_GAP_MS ||
            accountsChanged(current, frame)
        if (restarted) {
            finish(events)
            events += start(frame, localOwner, identitySource, now)
            return events
        }

        update(current, frame, now)
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
    }

    private fun start(frame: BattleFrame, localOwner: Int, identitySource: String, now: Long): Event {
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
        val session = Live(
            startWallMs = now - frame.tick * TICK_MS,
            myOwner = owner,
            identitySource = identitySource,
            accounts = accounts,
            slots = slots,
            firstTick = frame.tick,
            lastTick = frame.tick,
            myCrowns = crownsFor(frame, owner),
            enemyCrowns = crownsFor(frame, 1 - owner),
            decidedTick = if (frame.finalized) frame.tick else null,
            towers = frame.towers,
            lastFrameWallMs = now,
            lastRaw = null,
        )
        live = session
        update(session, frame, now)
        return Event.Started(toRecord(session, complete = false))
    }

    private fun update(session: Live, frame: BattleFrame, now: Long) {
        session.lastTick = frame.tick
        session.lastFrameWallMs = now
        session.towers = frame.towers
        session.myCrowns = maxOf(session.myCrowns, crownsFor(frame, session.myOwner))
        session.enemyCrowns = maxOf(session.enemyCrowns, crownsFor(frame, 1 - session.myOwner))
        if (frame.finalized && session.decidedTick == null) session.decidedTick = frame.tick

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

    private fun finish(events: MutableList<Event>) {
        val session = live ?: return
        live = null
        events += Event.Finished(toRecord(session, complete = true))
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

    private fun toRecord(session: Live, complete: Boolean): BattleRecord {
        val myOwner = session.myOwner
        val enemyOwner = 1 - myOwner
        val endTick = session.decidedTick ?: session.lastTick
        val durationTicks = endTick.coerceAtLeast(0)

        val myDeck = deckOf(session, myOwner)
        val enemyDeck = deckOf(session, enemyOwner)
        val archetype = ArchetypeClassifier.classify(enemyDeck.map { it.cardId })

        return BattleRecord(
            battleUid = session.uid,
            battleTime = session.startWallMs,
            startTime = session.startWallMs,
            endTime = if (complete) session.startWallMs + durationTicks * TICK_MS else null,
            durationTicks = if (complete) durationTicks else null,
            durationSeconds = if (complete) durationTicks * TICK_MS / 1000.0 else null,
            myPlayerId = session.accounts[myOwner]?.takeIf { it != 0L }?.toString(),
            enemyPlayerId = session.accounts[enemyOwner]?.takeIf { it != 0L }?.toString(),
            myCrowns = session.myCrowns,
            enemyCrowns = session.enemyCrowns,
            // A live frame is never authoritative for the final outcome. In
            // particular, 0-0 is a loading/exit state far more often than a
            // real draw. Only BattleHistoryReconciler may create COMPLETE.
            result = BattleResult.INCOMPLETE,
            status = BattleStatus.INCOMPLETE,
            myDeck = myDeck,
            enemyDeck = enemyDeck,
            enemyArchetype = archetype.archetype,
            enemyArchetypeSubtype = archetype.subtype,
            enemyArchetypeConfidence = archetype.confidence,
            source = BattleSource.LIVE_CAPTURE,
            firstTick = session.firstTick,
            lastTick = endTick,
            identitySource = session.identitySource,
            fullBattle = session.firstTick <= FULL_BATTLE_MAX_FIRST_TICK,
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

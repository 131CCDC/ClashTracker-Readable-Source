package dev.clashaiaa.overlay.history

import dev.clashaiaa.overlay.GHOST_SCHEMA
import dev.clashaiaa.overlay.GhostDropEvent
import dev.clashaiaa.overlay.GhostFeed
import dev.clashaiaa.overlay.LocalIdentity
import java.util.ArrayDeque

/**
 * Converts the unfiltered semantic Ghost stream into battle-scoped rows.
 *
 * This deliberately runs beside [dev.clashaiaa.overlay.GhostState]: that view
 * filters the local player for rendering, while history must keep both sides.
 * A small queue covers the normal race where GHOST publishes before the first
 * BattleFrame has established the stable uid.
 */
class CardPlayRecorder(
    private val pendingLimit: Int = DEFAULT_PENDING_LIMIT,
) {
    private data class Pending(
        val feed: GhostFeed,
        val event: GhostDropEvent,
        val receivedAtMs: Long,
    ) {
        val queueKey: String = "${feed.account0}:${feed.account1}:${event.key}"
    }

    private val pending = ArrayDeque<Pending>()
    private val pendingKeys = HashSet<String>()
    private var seenBattleUid: String? = null
    private val seenEventKeys = HashSet<String>()

    fun observe(
        feed: GhostFeed,
        activeBattleUid: String?,
        activeAccountIds: Set<Long>,
        identity: LocalIdentity,
        receivedAtMs: Long,
    ): List<CardPlayRecord> {
        if (feed.schema != GHOST_SCHEMA || !feed.inBattle || feed.tick < 0) {
            clearPending()
            return emptyList()
        }

        val complete = feed.events.filter { it.issuerAccountId != 0L && it.cardId > 0 }
        if (activeBattleUid == null) {
            for (event in complete) enqueue(Pending(feed, event, receivedAtMs))
            return emptyList()
        }

        beginBattle(activeBattleUid)
        val candidates = ArrayList<Pending>()
        drainMatching(activeAccountIds, candidates)
        if (belongsToBattle(feed, activeAccountIds)) {
            for (event in complete) candidates += Pending(feed, event, receivedAtMs)
        }
        return rows(activeBattleUid, identity, candidates)
    }

    /** Flush rows queued before the first BattleFrame created [battleUid]. */
    fun attachPending(
        battleUid: String,
        activeAccountIds: Set<Long>,
        identity: LocalIdentity,
    ): List<CardPlayRecord> {
        beginBattle(battleUid)
        val candidates = ArrayList<Pending>()
        drainMatching(activeAccountIds, candidates)
        return rows(battleUid, identity, candidates)
    }

    fun reset() {
        clearPending()
        seenBattleUid = null
        seenEventKeys.clear()
    }

    private fun beginBattle(battleUid: String) {
        if (seenBattleUid == battleUid) return
        seenBattleUid = battleUid
        seenEventKeys.clear()
    }

    private fun enqueue(row: Pending) {
        if (!pendingKeys.add(row.queueKey)) return
        while (pending.size >= pendingLimit.coerceAtLeast(1)) {
            pending.pollFirst()?.let { pendingKeys.remove(it.queueKey) }
        }
        pending.addLast(row)
    }

    private fun drainMatching(activeAccountIds: Set<Long>, output: MutableList<Pending>) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val row = iterator.next()
            if (!belongsToBattle(row.feed, activeAccountIds)) continue
            iterator.remove()
            pendingKeys.remove(row.queueKey)
            output += row
        }
    }

    private fun rows(
        battleUid: String,
        identity: LocalIdentity,
        candidates: List<Pending>,
    ): List<CardPlayRecord> = candidates
        .sortedWith(
            compareBy<Pending> { sortableTick(it.event.serverTick) }
                .thenBy { sortableTick(it.event.execTick) }
                .thenBy { it.event.sequence },
        )
        .mapNotNull { row ->
            if (!seenEventKeys.add(row.event.key)) return@mapNotNull null
            val owner = ownerOf(row.feed, row.event.issuerAccountId)
            CardPlayRecord(
                battleUid = battleUid,
                eventKey = row.event.key,
                issuerAccountId = row.event.issuerAccountId,
                owner = owner,
                isSelf = selfFlag(row.feed, row.event.issuerAccountId, owner, identity),
                cardId = row.event.cardId,
                targetX = row.event.x,
                targetY = row.event.y,
                serverTick = row.event.serverTick.takeIf { it >= 0 },
                execTick = row.event.execTick.takeIf { it >= 0 },
                semanticTick = row.event.semanticTick,
                semanticMs = row.event.semanticMs,
                commandSequence = row.event.commandSequence.takeIf { it != 0L },
                source = CardPlaySource.SEMANTIC_GHOST,
                createdAt = row.receivedAtMs,
            )
        }

    private fun selfFlag(
        feed: GhostFeed,
        issuerAccountId: Long,
        owner: Int?,
        identity: LocalIdentity,
    ): Boolean? {
        if (!identity.verified || identity.accountId == 0L) return null
        if (sameAccount(issuerAccountId, identity.accountId)) return true
        val localOwner = ownerOf(feed, identity.accountId) ?: identity.owner.takeIf { it in 0..1 }
        return if (owner != null && localOwner != null && owner != localOwner) false else null
    }

    private fun belongsToBattle(feed: GhostFeed, activeAccountIds: Set<Long>): Boolean {
        if (activeAccountIds.isEmpty()) return false
        val feedAccounts = listOf(feed.account0, feed.account1).filter { it != 0L }
        if (feedAccounts.isEmpty()) return false
        return feedAccounts.all { feedAccount ->
            activeAccountIds.any { activeAccount -> sameAccount(feedAccount, activeAccount) }
        }
    }

    private fun ownerOf(feed: GhostFeed, accountId: Long): Int? = when {
        accountId != 0L && sameAccount(accountId, feed.account0) -> 0
        accountId != 0L && sameAccount(accountId, feed.account1) -> 1
        else -> null
    }

    private fun sameAccount(left: Long, right: Long): Boolean =
        left == right || (left and LOW_32_BITS) == right || left == (right and LOW_32_BITS)

    private fun sortableTick(tick: Int): Int = if (tick >= 0) tick else Int.MAX_VALUE

    private fun clearPending() {
        pending.clear()
        pendingKeys.clear()
    }

    private companion object {
        const val DEFAULT_PENDING_LIMIT = 128
        const val LOW_32_BITS = 0xFFFFFFFFL
    }
}

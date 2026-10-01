package dev.clashaiaa.overlay

import org.json.JSONObject

const val GHOST_SCHEMA = "nulls-ghost.v1"

data class GhostDropEvent(
    val sequence: Long,
    val issuerAccountId: Long,
    val cardId: Int,
    val x: Int,
    val y: Int,
    val serverTick: Int,
    val execTick: Int,
    val commandSequence: Long,
    /** Tick chosen by the semantic decoder, when it differs from wire timing. */
    val semanticTick: Int? = null,
    /** Semantic event time in milliseconds, when the probe publishes it. */
    val semanticMs: Long? = null,
) {
    val key: String get() = "$issuerAccountId:$serverTick:$commandSequence"
}

data class GhostFeed(
    val schema: String,
    val inBattle: Boolean,
    val tick: Int,
    val account0: Long,
    val account1: Long,
    val selfHint: Long,
    val events: List<GhostDropEvent>,
) {
    fun ownerOf(accountId: Long): Int? = when {
        accountId != 0L && accountId == account0 -> 0
        accountId != 0L && accountId == account1 -> 1
        else -> null
    }
}

object GhostJson {
    fun parse(raw: String): GhostFeed {
        val root = JSONObject(raw)
        val events = ArrayList<GhostDropEvent>()
        val rows = root.optJSONArray("events")
        if (rows != null) {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val issuer = row.optLong("issuer", 0L)
                val cardId = row.optInt("card_id", 0)
                if (issuer == 0L || cardId <= 0) continue
                events += GhostDropEvent(
                    sequence = row.optLong("sequence", 0L),
                    issuerAccountId = issuer,
                    cardId = cardId,
                    x = row.optInt("x", 0),
                    y = row.optInt("y", 0),
                    serverTick = row.optInt("server_tick", -1),
                    execTick = row.optInt("exec_tick", -1),
                    commandSequence = row.optLong("command_sequence", 0L),
                    semanticTick = row.optNullableInt("semantic_tick"),
                    semanticMs = row.optNullableLong("semantic_ms"),
                )
            }
        }
        return GhostFeed(
            schema = root.optString("schema", ""),
            inBattle = root.optBoolean("in_battle", false),
            tick = root.optInt("tick", -1),
            account0 = root.optLong("acct0", 0L),
            account1 = root.optLong("acct1", 0L),
            selfHint = root.optLong("self_hint", 0L),
            events = events,
        )
    }
}

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONObject.optNullableLong(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

data class GhostMarker(
    val event: GhostDropEvent,
    val viewOwner: Int,
    val createdAtMs: Long,
)

/** Read-only marker lifecycle; it never sends commands to the game. */
class GhostState(
    private val minLeadMs: Int = 100,
    private val failSafeMs: Int = 1_500,
) {
    private val live = LinkedHashMap<String, GhostMarker>()

    /**
     * @param selfAccountId the account [IdentityResolver] proved to be this
     *   device. A seat or `self_hint` is never accepted here: owner 0/1 swaps
     *   between matches, and the live audit found both clients recording the
     *   same first `self_hint` issuer. Without proof nothing is drawn.
     */
    fun update(
        feed: GhostFeed,
        nowMs: Long,
        selfAccountId: Long,
    ): List<GhostMarker> {
        if (feed.schema != GHOST_SCHEMA || !feed.inBattle || feed.tick < 0) {
            reset()
            return emptyList()
        }
        val viewOwner = feed.ownerOf(selfAccountId)
        if (selfAccountId == 0L || viewOwner == null) {
            live.clear()
            return emptyList()
        }

        for (event in feed.events) {
            if (event.issuerAccountId == selfAccountId || event.execTick < 0) continue
            val leadMs = (event.execTick - feed.tick) * 50
            if (leadMs < minLeadMs) continue
            live.putIfAbsent(event.key, GhostMarker(event, viewOwner, nowMs))
        }
        val iterator = live.iterator()
        while (iterator.hasNext()) {
            val marker = iterator.next().value
            if (feed.tick >= marker.event.execTick + 2 || nowMs - marker.createdAtMs > failSafeMs) {
                iterator.remove()
            }
        }
        return live.values.toList()
    }

    fun clear() {
        live.clear()
    }

    fun reset() {
        live.clear()
    }
}

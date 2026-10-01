package dev.clashaiaa.overlay

/** Conservative display-only history derived from adjacent hand snapshots. */
class RecentPlayTracker {
    private var previousTick = -1
    private var previousOwner = -1
    private var previousHand: List<Int> = emptyList()
    private val recent = ArrayDeque<Int>()

    fun update(state: BattleState?, localOwner: Int, localAccountId: Long): List<Int> {
        if (state == null || !state.inBattle || state.stale) {
            reset()
            return emptyList()
        }
        val opponent = state.opponent(localOwner, localAccountId) ?: run {
            reset()
            return emptyList()
        }
        val current = opponent.hand.mapNotNull { it.cardId.takeIf { id -> id > 0 } }
        val sameBattle = previousOwner == opponent.owner && previousTick >= 0 &&
            state.tick > previousTick && state.tick - previousTick <= 40
        if (sameBattle && previousHand.isNotEmpty()) {
            val left = previousHand.filterNot { it in current }
            val entered = current.filterNot { it in previousHand }
            if (left.size == 1 && entered.size == 1) {
                recent.addFirst(left.single())
                while (recent.size > 4) recent.removeLast()
            }
        } else if (previousTick >= 0 && (state.tick <= previousTick || previousOwner != opponent.owner)) {
            recent.clear()
        }
        previousTick = state.tick
        previousOwner = opponent.owner
        previousHand = current
        return recent.toList()
    }

    fun reset() {
        previousTick = -1
        previousOwner = -1
        previousHand = emptyList()
        recent.clear()
    }
}

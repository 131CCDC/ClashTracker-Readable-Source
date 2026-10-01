package dev.clashaiaa.overlay.history

/** Shared builders so each test states only what it is actually about. */
object TestRecords {

    fun card(
        cardId: Int,
        slot: Int = 0,
        side: CardSide = CardSide.ENEMY,
        name: String = "Card$cardId",
        isEvolution: Boolean = false,
        isHero: Boolean = false,
        cost: Int? = null,
    ) = BattleCard(side, slot, cardId, name, "卡$cardId", isEvolution, isHero, cost)

    fun deck(
        vararg ids: Int,
        side: CardSide = CardSide.ENEMY,
    ): List<BattleCard> = ids.mapIndexed { index, id -> card(id, index, side) }

    fun record(
        uid: String,
        time: Long,
        result: BattleResult,
        durationSeconds: Double? = 120.0,
        myCrowns: Int? = 1,
        enemyCrowns: Int? = 0,
        archetype: String? = "Hog",
        subtype: String? = "Cycle",
        enemyCards: List<BattleCard> = emptyList(),
        myCards: List<BattleCard> = emptyList(),
        status: BattleStatus = BattleStatus.COMPLETE,
    ) = BattleRecord(
        battleUid = uid,
        battleTime = time,
        startTime = time,
        durationSeconds = durationSeconds,
        durationTicks = durationSeconds?.let { (it * 20).toInt() },
        myCrowns = myCrowns,
        enemyCrowns = enemyCrowns,
        result = result,
        status = status,
        myDeck = myCards,
        enemyDeck = enemyCards,
        enemyArchetype = archetype,
        enemyArchetypeSubtype = subtype,
        enemyArchetypeConfidence = 0.7,
        source = BattleSource.LIVE_CAPTURE,
        fullBattle = true,
    )
}

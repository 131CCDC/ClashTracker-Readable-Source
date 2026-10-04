package dev.clashaiaa.overlay.history

/** Outcome of one battle, from this device's point of view. */
enum class BattleResult(val wire: String) {
    WIN("win"),
    LOSS("loss"),
    DRAW("draw"),
    /** The battle was seen but never reached a decided end state. */
    INCOMPLETE("incomplete"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): BattleResult =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** Whether the row is a finished record or a crash-safe stub. */
enum class BattleStatus(val wire: String) {
    COMPLETE("complete"),
    INCOMPLETE("incomplete");

    companion object {
        fun fromWire(value: String?): BattleStatus =
            entries.firstOrNull { it.wire == value } ?: COMPLETE
    }
}

/** Where a row came from. The two sources never overwrite each other's facts. */
enum class BattleSource(val wire: String) {
    /** Recovered from Null's own battle-log interface. */
    NULLS_HISTORY("nulls_history"),

    /** Captured live by this device from the native probe. */
    LIVE_CAPTURE("live_capture"),

    /** Finalized directly from the game's validated native result. */
    NATIVE_RESULT("native_result");

    companion object {
        fun fromWire(value: String?): BattleSource =
            entries.firstOrNull { it.wire == value } ?: LIVE_CAPTURE
    }
}

enum class CardSide(val wire: String) {
    SELF("self"),
    ENEMY("enemy");

    companion object {
        fun fromWire(value: String?): CardSide =
            entries.firstOrNull { it.wire == value } ?: SELF
    }
}

/** The producer that supplied a semantic card-play row. */
enum class CardPlaySource(val wire: String) {
    SEMANTIC_GHOST("semantic_ghost");

    companion object {
        fun fromWire(value: String?): CardPlaySource =
            entries.firstOrNull { it.wire == value } ?: SEMANTIC_GHOST
    }
}

/** One decoded play command attached to a stable battle uid. */
data class CardPlayRecord(
    val battleUid: String,
    val eventKey: String,
    val issuerAccountId: Long,
    val owner: Int? = null,
    val isSelf: Boolean? = null,
    val cardId: Int? = null,
    val targetX: Int? = null,
    val targetY: Int? = null,
    val serverTick: Int? = null,
    val execTick: Int? = null,
    val semanticTick: Int? = null,
    val semanticMs: Long? = null,
    val commandSequence: Long? = null,
    val source: CardPlaySource = CardPlaySource.SEMANTIC_GHOST,
    val createdAt: Long,
)

/**
 * One deck slot of one side.
 *
 * `cardId` is always the *base family* id the game deals (for example
 * `26000000` Knight), never the evolved data id, so the same card played with
 * and without an evolution is one row plus a flag instead of two unrelated
 * cards. `isEvolution` / `isHero` carry the native variant evidence.
 */
data class BattleCard(
    val side: CardSide,
    val slot: Int,
    val cardId: Int,
    val name: String,
    val nameZh: String,
    val isEvolution: Boolean = false,
    val isHero: Boolean = false,
    /** Elixir cost the probe attested for this slot, when it had one. */
    val cost: Int? = null,
) {
    /** Display label that keeps `Elite Barbarians` and `Evo Elite Barbarians` apart. */
    val label: String
        get() = when {
            isHero && isEvolution -> "Hero Evo $name"
            isHero -> "Hero $name"
            isEvolution -> "Evo $name"
            else -> name
        }

    val labelZh: String
        get() = when {
            isHero && isEvolution -> "$nameZh·英雄进化"
            isHero -> "$nameZh·英雄"
            isEvolution -> "$nameZh·进化"
            else -> nameZh
        }
}

/**
 * One complete battle-history row. Field names follow the module's documented
 * schema; everything that the live probe cannot attest stays null instead of
 * being invented.
 */
data class BattleRecord(
    val battleUid: String,
    val battleId: String? = null,
    val replayId: String? = null,
    val battleTime: Long,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val durationTicks: Int? = null,
    val durationSeconds: Double? = null,
    val mode: String? = null,
    val arena: String? = null,
    val myPlayerId: String? = null,
    val myPlayerName: String? = null,
    val enemyPlayerId: String? = null,
    val enemyPlayerName: String? = null,
    val myCrowns: Int? = null,
    val enemyCrowns: Int? = null,
    val result: BattleResult = BattleResult.UNKNOWN,
    val status: BattleStatus = BattleStatus.COMPLETE,
    val startingTrophies: Int? = null,
    val endingTrophies: Int? = null,
    val trophyChange: Int? = null,
    val myDeck: List<BattleCard> = emptyList(),
    val enemyDeck: List<BattleCard> = emptyList(),
    val enemyArchetype: String? = null,
    val enemyArchetypeSubtype: String? = null,
    val enemyArchetypeConfidence: Double? = null,
    val source: BattleSource = BattleSource.LIVE_CAPTURE,
    /** Probe tick of the first frame this device saw of the battle. */
    val firstTick: Int? = null,
    /** Probe tick the battle was decided at (or last seen at). */
    val lastTick: Int? = null,
    /** How the local seat was decided; a guess must stay visible. */
    val identitySource: String? = null,
    /** `true` when the whole battle was observed from tick ~0. */
    val fullBattle: Boolean = false,
    /** World owner (0/1) declared the winner by the native result. */
    val winnerOwner: Int? = null,
    /** Unmodified `world_result_raw` value retained for diagnostics. */
    val nativeResultRaw: Int? = null,
    /** Whether the game marked the native result as validated. */
    val nativeResultValidated: Boolean = false,
    val rawJson: String? = null,
    val canonicalBattleId: String? = null,
    val provisionalBattleId: String? = null,
    val battleFingerprint: String? = null,
    val identityConfidence: String? = null,
    val resultSource: String? = null,
    val resultConfidence: String? = null,
    val personalRecordEligible: Boolean = false,
    val mergedSources: List<String> = emptyList(),
    val needsHistoryReconciliation: Boolean = false,
    val reconciliationState: String = "none",
    val reconciliationAttempts: Int = 0,
    val reconciliationAttemptedAt: Long? = null,
) {
    val decided: Boolean
        get() = result == BattleResult.WIN || result == BattleResult.LOSS || result == BattleResult.DRAW

    /** Cards of one side, ordered by slot. */
    fun cards(side: CardSide): List<BattleCard> = when (side) {
        CardSide.SELF -> myDeck
        CardSide.ENEMY -> enemyDeck
    }

    fun cardIds(side: CardSide): List<Int> = cards(side).map { it.cardId }.filter { it > 0 }
}

/**
 * Field-wise merge of an incoming record into a stored one.
 *
 * A value only replaces another when the newer record actually carries it, so
 * the crash-safe stub written at battle start is completed by the battle-end
 * update, and a later Null's history import can fill fields the live capture
 * could not attest without erasing what the capture proved. A decided outcome
 * always beats an undecided one.
 *
 * Top-level and free of Android types so the rule is unit-tested directly.
 */
internal fun mergeBattleRecords(old: BattleRecord, new: BattleRecord): BattleRecord = new.copy(
    battleId = new.battleId ?: old.battleId,
    replayId = new.replayId ?: old.replayId,
    startTime = new.startTime ?: old.startTime,
    endTime = new.endTime ?: old.endTime,
    durationTicks = new.durationTicks ?: old.durationTicks,
    durationSeconds = new.durationSeconds ?: old.durationSeconds,
    mode = new.mode ?: old.mode,
    arena = new.arena ?: old.arena,
    myPlayerId = new.myPlayerId ?: old.myPlayerId,
    myPlayerName = new.myPlayerName ?: old.myPlayerName,
    enemyPlayerId = new.enemyPlayerId ?: old.enemyPlayerId,
    enemyPlayerName = new.enemyPlayerName ?: old.enemyPlayerName,
    myCrowns = new.myCrowns ?: old.myCrowns,
    enemyCrowns = new.enemyCrowns ?: old.enemyCrowns,
    result = if (new.decided || !old.decided) new.result else old.result,
    status = if (new.status == BattleStatus.COMPLETE || old.status == BattleStatus.INCOMPLETE) {
        new.status
    } else {
        old.status
    },
    startingTrophies = new.startingTrophies ?: old.startingTrophies,
    endingTrophies = new.endingTrophies ?: old.endingTrophies,
    trophyChange = new.trophyChange ?: old.trophyChange,
    myDeck = if (new.myDeck.isNotEmpty()) new.myDeck else old.myDeck,
    enemyDeck = if (new.enemyDeck.isNotEmpty()) new.enemyDeck else old.enemyDeck,
    enemyArchetype = new.enemyArchetype ?: old.enemyArchetype,
    enemyArchetypeSubtype = new.enemyArchetypeSubtype ?: old.enemyArchetypeSubtype,
    enemyArchetypeConfidence = new.enemyArchetypeConfidence ?: old.enemyArchetypeConfidence,
    firstTick = new.firstTick ?: old.firstTick,
    lastTick = new.lastTick ?: old.lastTick,
    identitySource = new.identitySource ?: old.identitySource,
    fullBattle = new.fullBattle || old.fullBattle,
    winnerOwner = new.winnerOwner ?: old.winnerOwner,
    nativeResultRaw = new.nativeResultRaw ?: old.nativeResultRaw,
    nativeResultValidated = new.nativeResultValidated || old.nativeResultValidated,
    rawJson = new.rawJson ?: old.rawJson,
    canonicalBattleId = new.canonicalBattleId ?: old.canonicalBattleId,
    provisionalBattleId = new.provisionalBattleId ?: old.provisionalBattleId,
    battleFingerprint = new.battleFingerprint ?: old.battleFingerprint,
    identityConfidence = new.identityConfidence ?: old.identityConfidence,
    resultSource = new.resultSource ?: old.resultSource,
    resultConfidence = new.resultConfidence ?: old.resultConfidence,
    personalRecordEligible = new.personalRecordEligible || old.personalRecordEligible,
    mergedSources = (old.mergedSources + new.mergedSources + old.source.wire + new.source.wire).distinct(),
    needsHistoryReconciliation = if (new.status == BattleStatus.COMPLETE) false
        else new.needsHistoryReconciliation || old.needsHistoryReconciliation,
    reconciliationState = when {
        new.status == BattleStatus.COMPLETE -> "matched"
        new.reconciliationState != "none" -> new.reconciliationState
        else -> old.reconciliationState
    },
    reconciliationAttempts = maxOf(old.reconciliationAttempts, new.reconciliationAttempts),
    reconciliationAttemptedAt = new.reconciliationAttemptedAt ?: old.reconciliationAttemptedAt,
)

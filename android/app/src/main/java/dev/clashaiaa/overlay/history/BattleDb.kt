package dev.clashaiaa.overlay.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * Permanent battle-history store.
 *
 * A plain [SQLiteOpenHelper] on purpose: the module has to work on the tablet
 * with no network and no annotation processor, and the schema below is the
 * contract. Every schema change is an append-only step in [MIGRATIONS];
 * [SCHEMA_VERSION] is only raised together with one, so an existing install
 * upgrades in place instead of being dropped. The database file is new and
 * separate from the app's `SharedPreferences`, so an upgrade cannot disturb
 * anything the HUD already stores.
 */
class BattleDb(context: Context) : SQLiteOpenHelper(context, NAME, null, SCHEMA_VERSION) {

    init {
        // Write-ahead logging keeps a battle-end insert from blocking a read of
        // the history list, which is what "battle end is persisted
        // asynchronously" needs.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_BATTLES)
        db.execSQL(CREATE_BATTLE_CARDS)
        db.execSQL(CREATE_CARD_PLAYS)
        db.execSQL(CREATE_META)
        for (statement in INDEXES) db.execSQL(statement)
        writeVersion(db, SCHEMA_VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        while (version < newVersion) {
            val step = MIGRATIONS[version]
                ?: throw IllegalStateException("no migration from schema $version to ${version + 1}")
            step(db)
            version++
        }
        writeVersion(db, newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never destroy history to satisfy an older build; columns that build
        // does not know about are simply left in place.
        writeVersion(db, newVersion)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    fun putMeta(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)",
            arrayOf(key, value),
        )
    }

    private fun writeVersion(db: SQLiteDatabase, version: Int) {
        db.execSQL(
            "INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)",
            arrayOf("schema_version", version.toString()),
        )
    }

    companion object {
        const val NAME = "clashtracker_history.db"
        const val SCHEMA_VERSION = 2

        private const val CREATE_BATTLES = """
            CREATE TABLE battles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                battle_uid TEXT NOT NULL UNIQUE,
                battle_id TEXT,
                replay_id TEXT,
                battle_time INTEGER NOT NULL,
                start_time INTEGER,
                end_time INTEGER,
                duration_ticks INTEGER,
                duration_seconds REAL,
                mode TEXT,
                arena TEXT,
                my_player_id TEXT,
                my_player_name TEXT,
                enemy_player_id TEXT,
                enemy_player_name TEXT,
                my_crowns INTEGER,
                enemy_crowns INTEGER,
                result TEXT NOT NULL,
                status TEXT NOT NULL,
                starting_trophies INTEGER,
                ending_trophies INTEGER,
                trophy_change INTEGER,
                my_deck_json TEXT,
                enemy_deck_json TEXT,
                my_evos_json TEXT,
                enemy_evos_json TEXT,
                my_heroes_json TEXT,
                enemy_heroes_json TEXT,
                enemy_archetype TEXT,
                enemy_archetype_subtype TEXT,
                enemy_archetype_confidence REAL,
                source TEXT NOT NULL,
                first_tick INTEGER,
                last_tick INTEGER,
                identity_source TEXT,
                full_battle INTEGER NOT NULL DEFAULT 0,
                winner_owner INTEGER,
                native_result_raw INTEGER,
                native_result_validated INTEGER NOT NULL DEFAULT 0,
                raw_json TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """

        private const val CREATE_BATTLE_CARDS = """
            CREATE TABLE battle_cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                battle_uid TEXT NOT NULL,
                side TEXT NOT NULL,
                slot INTEGER NOT NULL,
                card_id INTEGER NOT NULL,
                card_name TEXT,
                card_name_zh TEXT,
                is_evolution INTEGER NOT NULL DEFAULT 0,
                is_hero INTEGER NOT NULL DEFAULT 0,
                cost INTEGER,
                UNIQUE(battle_uid, side, slot),
                FOREIGN KEY(battle_uid) REFERENCES battles(battle_uid) ON DELETE CASCADE
            )
        """

        private const val CREATE_META = """
            CREATE TABLE meta (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """

        internal const val CREATE_CARD_PLAYS = """
            CREATE TABLE card_plays (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                battle_uid TEXT NOT NULL REFERENCES battles(battle_uid) ON DELETE CASCADE,
                event_key TEXT NOT NULL,
                issuer_account_id INTEGER NOT NULL,
                owner INTEGER,
                is_self INTEGER,
                card_id INTEGER NOT NULL,
                target_x INTEGER,
                target_y INTEGER,
                server_tick INTEGER,
                exec_tick INTEGER,
                semantic_tick INTEGER,
                semantic_ms INTEGER,
                command_sequence INTEGER,
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(battle_uid, event_key)
            )
        """

        private val INDEXES = listOf(
            "CREATE INDEX idx_battles_time ON battles(battle_time)",
            "CREATE INDEX idx_battles_result ON battles(result)",
            "CREATE INDEX idx_battles_archetype ON battles(enemy_archetype)",
            "CREATE INDEX idx_battles_source ON battles(source)",
            "CREATE INDEX idx_cards_uid ON battle_cards(battle_uid)",
            "CREATE INDEX idx_cards_card ON battle_cards(card_id)",
            "CREATE INDEX idx_card_plays_battle_uid ON card_plays(battle_uid)",
            "CREATE INDEX idx_card_plays_card_id ON card_plays(card_id)",
            "CREATE INDEX idx_card_plays_server_tick ON card_plays(server_tick)",
            "CREATE INDEX idx_card_plays_owner ON card_plays(owner)",
        )

        /**
         * One entry per schema step. Index `n` upgrades `n` -> `n + 1`. Version
         * 1 is the initial schema, so this is empty until the first change
         * ships; adding a column means bumping [SCHEMA_VERSION] and adding the
         * matching `ALTER TABLE` here in the same commit.
         */
        internal val MIGRATION_1_TO_2 = listOf(
            "ALTER TABLE battles ADD COLUMN winner_owner INTEGER",
            "ALTER TABLE battles ADD COLUMN native_result_raw INTEGER",
            "ALTER TABLE battles ADD COLUMN native_result_validated INTEGER NOT NULL DEFAULT 0",
            CREATE_CARD_PLAYS,
            "CREATE INDEX idx_card_plays_battle_uid ON card_plays(battle_uid)",
            "CREATE INDEX idx_card_plays_card_id ON card_plays(card_id)",
            "CREATE INDEX idx_card_plays_server_tick ON card_plays(server_tick)",
            "CREATE INDEX idx_card_plays_owner ON card_plays(owner)",
        )

        private val MIGRATIONS: Map<Int, (SQLiteDatabase) -> Unit> = mapOf(
            1 to { database ->
                for (statement in MIGRATION_1_TO_2) database.execSQL(statement)
            },
        )
    }
}

/** Row-level reads and writes for [BattleDb]. Synchronous; call off the UI thread. */
class BattleDao(private val db: BattleDb) {

    private class Row(val record: BattleRecord, val createdAt: Long)

    /**
     * Insert a new row, or merge into the existing one with the same
     * `battle_uid`. Merging never overwrites a known fact with null, so the
     * crash-safe stub written at battle start is completed by the battle-end
     * update, and a later Null's history import can fill fields the live
     * capture could not attest without erasing what the capture proved.
     *
     * @return true when a row was created, false when an existing row was updated.
     */
    fun upsert(record: BattleRecord): Boolean {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val existing = loadRow(database, record.battleUid)
            val merged = if (existing == null) record else mergeBattleRecords(existing.record, record)
            val values = toValues(merged, existing?.createdAt)
            if (existing == null) {
                database.insertOrThrow("battles", null, values)
            } else {
                database.update("battles", values, "battle_uid = ?", arrayOf(merged.battleUid))
            }
            if (merged.myDeck.isNotEmpty() || merged.enemyDeck.isNotEmpty()) {
                writeCards(database, merged)
            }
            database.setTransactionSuccessful()
            return existing == null
        } finally {
            database.endTransaction()
        }
    }

    /** Raw rows, including legacy diagnostics. Never use for user-facing history. */
    fun all(): List<BattleRecord> = read("SELECT * FROM battles ORDER BY battle_time DESC", null)

    /** Structurally valid records finalized by either game-owned source. */
    fun finalized(): List<BattleRecord> = read(
        "SELECT * FROM battles WHERE status = ? AND source IN (?, ?) ORDER BY battle_time DESC",
        arrayOf(
            BattleStatus.COMPLETE.wire,
            BattleSource.NULLS_HISTORY.wire,
            BattleSource.NATIVE_RESULT.wire,
        ),
    ).filter(BattleHistoryReconciler::isFinalized)

    fun recent(limit: Int): List<BattleRecord> =
        read("SELECT * FROM battles ORDER BY battle_time DESC LIMIT ?", arrayOf(limit.toString()))

    /** Battles inside `[fromMs, toMs]`, newest first. */
    fun range(fromMs: Long, toMs: Long): List<BattleRecord> = read(
        "SELECT * FROM battles WHERE battle_time >= ? AND battle_time <= ? ORDER BY battle_time DESC",
        arrayOf(fromMs.toString(), toMs.toString()),
    )

    fun byUid(battleUid: String): BattleRecord? =
        read("SELECT * FROM battles WHERE battle_uid = ?", arrayOf(battleUid)).firstOrNull()

    fun count(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM battles", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /** Rows still missing an outcome, oldest first, for a later backfill pass. */
    fun incomplete(limit: Int = 200): List<BattleRecord> = read(
        "SELECT * FROM battles WHERE status = ? ORDER BY battle_time ASC LIMIT ?",
        arrayOf(BattleStatus.INCOMPLETE.wire, limit.toString()),
    )

    fun deleteAll() {
        db.writableDatabase.beginTransaction()
        try {
            db.writableDatabase.execSQL("DELETE FROM card_plays")
            db.writableDatabase.execSQL("DELETE FROM battle_cards")
            db.writableDatabase.execSQL("DELETE FROM battles")
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
    }

    /**
     * Reads battles and their cards in two passes. Loading the cards per row
     * would open a second cursor inside the first one, which is exactly the
     * pattern that goes wrong once the history is large.
     */
    private fun read(sql: String, args: Array<String>?): List<BattleRecord> {
        val rows = db.readableDatabase.rawQuery(sql, args).use { cursor -> readRows(cursor) }
        if (rows.isEmpty()) return rows.map { it.record }
        val cards = cardsByUid()
        return rows.map { row ->
            val forBattle = cards[row.record.battleUid].orEmpty()
            row.record.copy(
                myDeck = forBattle.filter { it.side == CardSide.SELF },
                enemyDeck = forBattle.filter { it.side == CardSide.ENEMY },
            )
        }
    }

    private fun cardsByUid(): Map<String, List<BattleCard>> {
        val result = HashMap<String, MutableList<BattleCard>>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM battle_cards ORDER BY battle_uid, side, slot",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uid = cursor.getString(cursor.getColumnIndexOrThrow("battle_uid"))
                result.getOrPut(uid) { ArrayList() }.add(readCard(cursor))
            }
        }
        return result
    }

    private fun loadRow(database: SQLiteDatabase, battleUid: String): Row? =
        database.rawQuery("SELECT * FROM battles WHERE battle_uid = ?", arrayOf(battleUid))
            .use { cursor -> readRows(cursor).firstOrNull() }

    private fun readRows(cursor: Cursor): List<Row> = buildList {
        while (cursor.moveToNext()) {
            add(
                Row(
                    record = readRecord(cursor),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                ),
            )
        }
    }

    private fun writeCards(database: SQLiteDatabase, record: BattleRecord) {
        // Replace both sides wholesale: a merge can add slots that were unknown
        // at battle start, and the unique key makes this idempotent.
        database.delete("battle_cards", "battle_uid = ?", arrayOf(record.battleUid))
        for (card in record.myDeck + record.enemyDeck) {
            val values = ContentValues().apply {
                put("battle_uid", record.battleUid)
                put("side", card.side.wire)
                put("slot", card.slot)
                put("card_id", card.cardId)
                put("card_name", card.name)
                put("card_name_zh", card.nameZh)
                put("is_evolution", if (card.isEvolution) 1 else 0)
                put("is_hero", if (card.isHero) 1 else 0)
                if (card.cost == null) putNull("cost") else put("cost", card.cost)
            }
            database.insertWithOnConflict(
                "battle_cards", null, values, SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private fun toValues(record: BattleRecord, createdAt: Long?): ContentValues {
        val now = System.currentTimeMillis()
        return ContentValues().apply {
            put("battle_uid", record.battleUid)
            put("battle_id", record.battleId)
            put("replay_id", record.replayId)
            put("battle_time", record.battleTime)
            put("start_time", record.startTime)
            put("end_time", record.endTime)
            put("duration_ticks", record.durationTicks)
            put("duration_seconds", record.durationSeconds)
            put("mode", record.mode)
            put("arena", record.arena)
            put("my_player_id", record.myPlayerId)
            put("my_player_name", record.myPlayerName)
            put("enemy_player_id", record.enemyPlayerId)
            put("enemy_player_name", record.enemyPlayerName)
            put("my_crowns", record.myCrowns)
            put("enemy_crowns", record.enemyCrowns)
            put("result", record.result.wire)
            put("status", record.status.wire)
            put("starting_trophies", record.startingTrophies)
            put("ending_trophies", record.endingTrophies)
            put("trophy_change", record.trophyChange)
            put("my_deck_json", cardIdsJson(record.myDeck))
            put("enemy_deck_json", cardIdsJson(record.enemyDeck))
            put("my_evos_json", flagsJson(record.myDeck) { it.isEvolution })
            put("enemy_evos_json", flagsJson(record.enemyDeck) { it.isEvolution })
            put("my_heroes_json", flagsJson(record.myDeck) { it.isHero })
            put("enemy_heroes_json", flagsJson(record.enemyDeck) { it.isHero })
            put("enemy_archetype", record.enemyArchetype)
            put("enemy_archetype_subtype", record.enemyArchetypeSubtype)
            put("enemy_archetype_confidence", record.enemyArchetypeConfidence)
            put("source", record.source.wire)
            put("first_tick", record.firstTick)
            put("last_tick", record.lastTick)
            put("identity_source", record.identitySource)
            put("full_battle", if (record.fullBattle) 1 else 0)
            put("winner_owner", record.winnerOwner)
            put("native_result_raw", record.nativeResultRaw)
            put("native_result_validated", if (record.nativeResultValidated) 1 else 0)
            put("raw_json", record.rawJson)
            put("created_at", createdAt ?: now)
            put("updated_at", now)
        }
    }

    private fun cardIdsJson(cards: List<BattleCard>): String {
        val array = JSONArray()
        for (card in cards.sortedBy { it.slot }) array.put(card.cardId)
        return array.toString()
    }

    private fun flagsJson(cards: List<BattleCard>, predicate: (BattleCard) -> Boolean): String {
        val array = JSONArray()
        for (card in cards.sortedBy { it.slot }) if (predicate(card)) array.put(card.cardId)
        return array.toString()
    }

    private fun readCard(cursor: Cursor): BattleCard = BattleCard(
        side = CardSide.fromWire(cursor.getStringOrNull("side")),
        slot = cursor.getInt(cursor.getColumnIndexOrThrow("slot")),
        cardId = cursor.getInt(cursor.getColumnIndexOrThrow("card_id")),
        name = cursor.getStringOrNull("card_name") ?: "",
        nameZh = cursor.getStringOrNull("card_name_zh") ?: "",
        isEvolution = cursor.getInt(cursor.getColumnIndexOrThrow("is_evolution")) != 0,
        isHero = cursor.getInt(cursor.getColumnIndexOrThrow("is_hero")) != 0,
        cost = cursor.getIntOrNull("cost"),
    )

    private fun readRecord(cursor: Cursor): BattleRecord = BattleRecord(
        battleUid = cursor.getString(cursor.getColumnIndexOrThrow("battle_uid")),
        battleId = cursor.getStringOrNull("battle_id"),
        replayId = cursor.getStringOrNull("replay_id"),
        battleTime = cursor.getLong(cursor.getColumnIndexOrThrow("battle_time")),
        startTime = cursor.getLongOrNull("start_time"),
        endTime = cursor.getLongOrNull("end_time"),
        durationTicks = cursor.getIntOrNull("duration_ticks"),
        durationSeconds = cursor.getDoubleOrNull("duration_seconds"),
        mode = cursor.getStringOrNull("mode"),
        arena = cursor.getStringOrNull("arena"),
        myPlayerId = cursor.getStringOrNull("my_player_id"),
        myPlayerName = cursor.getStringOrNull("my_player_name"),
        enemyPlayerId = cursor.getStringOrNull("enemy_player_id"),
        enemyPlayerName = cursor.getStringOrNull("enemy_player_name"),
        myCrowns = cursor.getIntOrNull("my_crowns"),
        enemyCrowns = cursor.getIntOrNull("enemy_crowns"),
        result = BattleResult.fromWire(cursor.getStringOrNull("result")),
        status = BattleStatus.fromWire(cursor.getStringOrNull("status")),
        startingTrophies = cursor.getIntOrNull("starting_trophies"),
        endingTrophies = cursor.getIntOrNull("ending_trophies"),
        trophyChange = cursor.getIntOrNull("trophy_change"),
        enemyArchetype = cursor.getStringOrNull("enemy_archetype"),
        enemyArchetypeSubtype = cursor.getStringOrNull("enemy_archetype_subtype"),
        enemyArchetypeConfidence = cursor.getDoubleOrNull("enemy_archetype_confidence"),
        source = BattleSource.fromWire(cursor.getStringOrNull("source")),
        firstTick = cursor.getIntOrNull("first_tick"),
        lastTick = cursor.getIntOrNull("last_tick"),
        identitySource = cursor.getStringOrNull("identity_source"),
        fullBattle = cursor.getInt(cursor.getColumnIndexOrThrow("full_battle")) != 0,
        winnerOwner = cursor.getIntOrNull("winner_owner"),
        nativeResultRaw = cursor.getIntOrNull("native_result_raw"),
        nativeResultValidated = cursor.getInt(cursor.getColumnIndexOrThrow("native_result_validated")) != 0,
        rawJson = cursor.getStringOrNull("raw_json"),
    )
}

internal fun Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

internal fun Cursor.getIntOrNull(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}

internal fun Cursor.getLongOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

internal fun Cursor.getDoubleOrNull(column: String): Double? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getDouble(index)
}

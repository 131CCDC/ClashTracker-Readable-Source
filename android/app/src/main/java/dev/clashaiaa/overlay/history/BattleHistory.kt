package dev.clashaiaa.overlay.history

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Process-wide handle on the battle-history database.
 *
 * The recorder service and the history screen are the same process, so one
 * lazy [BattleDb] serves both and every write is followed by a change
 * notification. All access goes through a single background executor: SQLite
 * writes happen off the UI thread, which is what keeps a battle-end insert from
 * ever touching the overlay's frame path.
 */
object BattleHistory {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "clashtracker-history").apply { isDaemon = true }
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var database: BattleDb? = null

    @Volatile
    private var dao: BattleDao? = null

    private fun dao(context: Context): BattleDao {
        val existing = dao
        if (existing != null) return existing
        return synchronized(this) {
            dao ?: run {
                val db = BattleDb(context.applicationContext)
                database = db
                BattleDao(db).also { dao = it }
            }
        }
    }

    /** Run [block] on the history thread. */
    fun <T> run(context: Context, block: (BattleDao) -> T): java.util.concurrent.Future<T> {
        val target = dao(context)
        return executor.submit<T> { block(target) }
    }

    /** Persist a crash-safe start row or an authoritative finalized row. */
    fun save(context: Context, record: BattleRecord): Boolean {
        val crashSafeStart = record.source == BattleSource.LIVE_CAPTURE &&
            record.status == BattleStatus.INCOMPLETE &&
            record.result in setOf(BattleResult.INCOMPLETE, BattleResult.UNKNOWN)
        if (!crashSafeStart && !BattleHistoryReconciler.isFinalized(record)) {
            Log.w(TAG, "[History] rejected invalid row uid=${record.battleUid}")
            return false
        }
        val target = dao(context)
        executor.execute {
            runCatching { target.upsert(record) }
                .onSuccess { notifyChanged() }
                .onFailure { Log.w(TAG, "[History] save failed uid=${record.battleUid}", it) }
        }
        return true
    }

    /** Persist semantic events on the same executor as their parent battle row. */
    fun saveCardPlays(context: Context, records: List<CardPlayRecord>): Boolean {
        if (records.isEmpty()) return true
        if (records.any { it.battleUid.isBlank() || it.eventKey.isBlank() || it.issuerAccountId == 0L }) {
            Log.w(TAG, "[History] rejected malformed semantic card-play batch")
            return false
        }
        val target = dao(context)
        executor.execute {
            runCatching { target.insertCardPlays(records) }
                .onFailure { Log.w(TAG, "[History] card-play save failed", it) }
        }
        return true
    }

    fun notifyChanged() {
        for (listener in listeners) runCatching { listener() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** The backing file, for diagnostics and the report. */
    fun databasePath(context: Context): String = context.applicationContext
        .getDatabasePath(BattleDb.NAME)
        .absolutePath

    private const val TAG = "ClashTrackerBattle"
}

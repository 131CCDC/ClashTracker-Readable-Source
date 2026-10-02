package dev.clashaiaa.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class CardInfo(
    val cardId: Int,
    val name: String,
    val cost: Int?,
)

/** Offline card names, elixir costs and art shared with the desktop tracker. */
class CardCatalog(private val context: Context) {
    private val byId: Map<Int, CardInfo> by lazy { loadTable() }
    private val bitmaps = object : LinkedHashMap<Int, Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean = size > 40
    }

    fun info(cardId: Int, fallback: String? = null): CardInfo {
        val stored = byId[cardId]
        if (stored != null) return stored
        return CardInfo(cardId, fallback?.takeIf { it.isNotBlank() } ?: "Unknown($cardId)", null)
    }

    @Synchronized
    fun art(cardId: Int): Bitmap? {
        if (cardId <= 0) return null
        bitmaps[cardId]?.let { return it }
        val decoded = runCatching {
            context.assets.open("$cardId.png").use(BitmapFactory::decodeStream)
        }.getOrNull()
        if (decoded == null) {
            logMissingArtOnce(cardId)
            return null
        }
        bitmaps[cardId] = decoded
        return decoded
    }

    private fun loadTable(): Map<Int, CardInfo> = runCatching {
        val raw = context.assets.open("cards.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val rows = JSONObject(raw).optJSONObject("by_id") ?: JSONObject()
        val table = buildMap {
            val keys = rows.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toIntOrNull() ?: continue
                val row = rows.optJSONObject(key) ?: continue
                val zh = row.optString("zh_cn", "").trim()
                val internal = row.optString("internal_name", "").trim()
                val name = zh.ifBlank { internal.ifBlank { "Unknown($id)" } }
                val rawCost = row.optInt("elixir", -1)
                put(id, CardInfo(id, name, rawCost.takeIf { it in 0..15 }))
            }
        }
        if (table.isEmpty()) {
            logMissingTableOnce()
        } else {
            logLoadedTableOnce(table.size)
        }
        table
    }.getOrElse {
        logMissingTableOnce()
        emptyMap()
    }

    private fun logMissingArtOnce(cardId: Int) {
        if (missingArtReported.add(cardId)) {
            Log.w(TAG, "[CardCatalog] missing art card_id=$cardId")
        }
    }

    companion object {
        private const val TAG = "ClashTrackerCard"

        /** Diagnostics only: reported at most once per process so frames stay quiet. */
        private val loadedTableReported = AtomicBoolean(false)
        private val missingTableReported = AtomicBoolean(false)
        private val missingArtReported: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        private fun logLoadedTableOnce(size: Int) {
            if (loadedTableReported.compareAndSet(false, true)) {
                Log.i(TAG, "[CardCatalog] loaded cards=$size")
            }
        }

        private fun logMissingTableOnce() {
            if (missingTableReported.compareAndSet(false, true)) {
                Log.w(TAG, "[CardCatalog] cards.json missing or empty")
            }
        }
    }
}

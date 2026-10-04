package dev.clashaiaa.overlay.history

import android.content.Context
import dev.clashaiaa.overlay.SettingsStore
import java.io.File

data class PayloadSyncSummary(
    val files: Int,
    val found: Int,
    val imported: Int,
    val duplicates: Int,
    val updated: Int,
    val skipped: Int,
    val consumedLiveUids: Set<String>,
    val diagnostics: List<String>,
) {
    val summary: String
        get() = "payload $files 个 · 找到 $found · 新增 $imported · 重复 $duplicates · " +
            "补全 $updated · 拒绝 $skipped" +
            if (diagnostics.isEmpty()) "" else "\n诊断: ${diagnostics.joinToString(", ")}"
}

/** Low-frequency filesystem bridge to captured game Battle Log payloads. */
object BattleHistorySync {
    fun exportsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").also { it.mkdirs() }

    fun payloadFiles(context: Context): List<File> =
        exportsDir(context).listFiles { file ->
            file.isFile && file.name.startsWith("history_payload") && file.name.endsWith(".json")
        }?.sortedBy { it.name } ?: emptyList()

    fun fingerprint(context: Context, pending: List<BattleRecord>): String = buildString {
        for (file in payloadFiles(context)) {
            append(file.name).append(':').append(file.length()).append(':').append(file.lastModified()).append('|')
        }
        append("pending=").append(pending.joinToString(",") { it.battleUid })
    }

    /** Call off the main thread. */
    fun syncBlocking(context: Context, pending: List<BattleRecord>): PayloadSyncSummary {
        val files = payloadFiles(context)
        if (files.isEmpty()) return PayloadSyncSummary(0, 0, 0, 0, 0, 0, emptySet(), emptyList())

        val localAccountId = SettingsStore.loadCanonicalSelfAccountId(context)
        val namer = CardNames(context)
        return BattleHistory.run(context) { dao ->
            var found = 0
            var imported = 0
            var duplicates = 0
            var updated = 0
            var skipped = 0
            val consumed = LinkedHashSet<String>()
            val diagnostics = ArrayList<String>()
            for (file in files) {
                val parsed = runCatching {
                    NullsHistoryImporter.parsePayloadFile(file, localAccountId)
                }.getOrElse { error ->
                    diagnostics += "${file.name}: ${error.message ?: "parse_failed"}"
                    emptyList()
                }
                val report = NullsHistoryImporter.import(
                    dao = dao,
                    entries = parsed,
                    source = file.name,
                    namer = namer,
                    fallbackTimeMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                    pending = pending,
                    canonicalSelfId = localAccountId,
                )
                found += report.found
                imported += report.imported
                duplicates += report.duplicates
                updated += report.updated
                skipped += report.skipped
                consumed += report.consumedLiveUids
                diagnostics += report.diagnostics.map { "${file.name}: $it" }
            }
            PayloadSyncSummary(
                files.size, found, imported, duplicates, updated, skipped,
                consumed, diagnostics.distinct().take(8),
            )
        }.get()
    }
}

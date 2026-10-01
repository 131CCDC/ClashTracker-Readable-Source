package dev.clashaiaa.overlay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.clashaiaa.overlay.history.BattleExport
import dev.clashaiaa.overlay.history.BattleHistory
import dev.clashaiaa.overlay.history.BattleHistorySync
import dev.clashaiaa.overlay.history.BattleRecord
import dev.clashaiaa.overlay.history.BattleRecordService
import dev.clashaiaa.overlay.history.BattleResult
import dev.clashaiaa.overlay.history.BattleSource
import dev.clashaiaa.overlay.history.BattleWorkingSessions
import dev.clashaiaa.overlay.history.BattleStats
import dev.clashaiaa.overlay.history.BattleStatsCalculator
import dev.clashaiaa.overlay.history.ExportStore
import dev.clashaiaa.overlay.history.MatchupRow
import dev.clashaiaa.overlay.history.asClock
import dev.clashaiaa.overlay.history.asPercent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对战记录 / Battle History.
 *
 * A read-only view over the database the recorder writes. It never touches the
 * probe, the overlay or the game: every query runs on the history thread and
 * every result is posted back, so opening this screen during a battle cannot
 * slow the HUD down.
 */
class HistoryActivity : Activity() {

    private enum class Section(val label: String) {
        SUMMARY("摘要"),
        MATCHUPS("对阵分析"),
        CARDS("卡牌统计"),
        DURATION("时长分析"),
        LOSSES("败局分析"),
        LIST("历史列表"),
    }

    private enum class Sort(val label: String) {
        GAMES("按场次"),
        WIN_RATE("按胜率"),
        LOSSES("按败场"),
    }

    private enum class Range(val label: String, val days: Int) {
        ALL("全部", 0),
        TODAY("今天", 1),
        WEEK("7 天", 7),
        MONTH("30 天", 30),
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private var records: List<BattleRecord> = emptyList()
    private var section = Section.SUMMARY
    private var sort = Sort.GAMES
    private var range = Range.ALL
    private var limit = 0
    private var expanded: String? = null
    private var statusText: TextView? = null

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        BattleHistory.addListener(listener)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        BattleHistory.removeListener(listener)
        super.onDestroy()
    }

    private val listener: () -> Unit = { handler.post { reload() } }

    // ------------------------------------------------------------ layout --

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        root.addView(
            TextView(this).apply {
                text = "对战记录 · Battle History"
                setTextColor(FG)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(16), dp(14), dp(16), dp(4))
            },
        )

        statusText = TextView(this).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(statusText)

        root.addView(
            scroller(
                listOf(
                    actionButton(if (BattleRecordService.running) "停止记录" else "开始记录") {
                        if (BattleRecordService.running) {
                            BattleRecordService.stop(this)
                            toast("已停止记录")
                        } else {
                            BattleRecordService.start(this)
                            toast("已开始记录，结算后由 Battle Log 入库")
                        }
                        handler.postDelayed({ rebuildHeader() }, 400)
                    },
                    actionButton("导出 CSV") { export(csv = true) },
                    actionButton("导出 JSON") { export(csv = false) },
                    actionButton("同步历史") { syncHistory() },
                    actionButton("刷新") { reload() },
                    actionButton("清空记录") { confirmClear() },
                ),
            ),
        )

        root.addView(sectionTabs())

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        root.addView(
            ScrollView(this).apply {
                addView(content)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            },
        )
        return root
    }

    private fun rebuildHeader() {
        setContentView(buildRoot())
        render()
    }

    private fun sectionTabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        for (value in Section.entries) {
            row.addView(
                Button(this).apply {
                    text = value.label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    isAllCaps = false
                    setOnClickListener {
                        section = value
                        expanded = null
                        rebuildHeader()
                    }
                    alpha = if (value == section) 1f else 0.55f
                },
            )
        }
        return scroller(listOf(row))
    }

    private fun reload() {
        BattleHistory.run(this) { dao -> dao.finalized() }.let { future ->
            Thread {
                val loaded = runCatching { future.get() }.getOrDefault(emptyList())
                handler.post { onLoaded(loaded) }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun onLoaded(loaded: List<BattleRecord>) {
        records = loaded
        render()
    }

    private fun filtered(): List<BattleRecord> {
        var list = records
        if (range.days > 0) {
            val from = System.currentTimeMillis() - range.days * 24L * 60 * 60 * 1000
            list = list.filter { it.battleTime >= from }
        }
        list = list.sortedByDescending { it.battleTime }
        if (limit > 0) list = list.take(limit)
        return list
    }

    // ----------------------------------------------------------- render --

    private fun render() {
        content.removeAllViews()
        val slice = filtered()
        val stats = BattleStatsCalculator.compute(slice)
        statusText?.text = buildString {
            append("数据库 ${records.size} 条 · 当前视图 ${slice.size} 条 · 库文件 ")
            append(BattleHistory.databasePath(this@HistoryActivity))
        }
        content.addView(filterBar())
        when (section) {
            Section.SUMMARY -> renderSummary(stats)
            Section.MATCHUPS -> renderMatchups(stats)
            Section.CARDS -> renderCards(stats)
            Section.DURATION -> renderDuration(stats)
            Section.LOSSES -> renderLosses(stats)
            Section.LIST -> renderList(slice)
        }
    }

    private fun filterBar(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val ranges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ranges.addView(label("时间范围"))
        for (value in Range.entries) {
            ranges.addView(
                chip(value.label, value == range) {
                    range = value
                    render()
                },
            )
        }
        container.addView(scroller(listOf(ranges)))

        val counts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        counts.addView(label("场次"))
        for (value in listOf(0 to "全部", 100 to "最近100", 50 to "最近50", 20 to "最近20")) {
            counts.addView(
                chip(value.second, value.first == limit) {
                    limit = value.first
                    render()
                },
            )
        }
        container.addView(scroller(listOf(counts)))
        return container
    }

    private fun renderSummary(stats: BattleStats) {
        content.addView(header("总览"))
        content.addView(kv("总对局", stats.total.toString()))
        content.addView(kv("胜", stats.wins.toString()))
        content.addView(kv("负", stats.losses.toString()))
        content.addView(kv("平", stats.draws.toString()))
        if (stats.incomplete > 0) {
            content.addView(kv("未完成/待补全", stats.incomplete.toString()))
        }
        content.addView(kv("胜率", stats.winRate.asPercent()))
        content.addView(kv("平均时长", stats.averageDurationSeconds.asClock()))
        content.addView(kv("中位时长", stats.medianDurationSeconds.asClock()))
        content.addView(kv("平均皇冠", stats.averageCrowns?.let { fmt(it) } ?: "n/a"))
        content.addView(kv("平均失冠", stats.averageEnemyCrowns?.let { fmt(it) } ?: "n/a"))

        content.addView(header("时长分布"))
        content.addView(
            row("分桶", "场次", "胜", "胜率"),
        )
        for (bucket in stats.durationBuckets) {
            content.addView(
                row(bucket.labelZh, bucket.games.toString(), bucket.wins.toString(), bucket.winRate.asPercent()),
            )
        }

        content.addView(header("时长分位"))
        content.addView(kv("P25", stats.p25DurationSeconds.asClock()))
        content.addView(kv("P50 (中位)", stats.medianDurationSeconds.asClock()))
        content.addView(kv("P75", stats.p75DurationSeconds.asClock()))
        content.addView(kv("P90", stats.p90DurationSeconds.asClock()))
    }

    private fun renderMatchups(stats: BattleStats) {
        content.addView(header("对阵分析"))
        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sortRow.addView(label("排序"))
        for (value in Sort.entries) {
            sortRow.addView(
                chip(value.label, value == sort) {
                    sort = value
                    render()
                },
            )
        }
        content.addView(sortRow)

        val rows = stats.matchups.sortedWith(
            when (sort) {
                Sort.GAMES -> compareByDescending<MatchupRow> { it.games }.thenBy { it.display }
                Sort.WIN_RATE -> compareBy<MatchupRow> { it.winRate ?: 0.0 }.thenByDescending { it.games }
                Sort.LOSSES -> compareByDescending<MatchupRow> { it.losses }.thenBy { it.display }
            },
        )
        if (rows.isEmpty()) {
            content.addView(note("还没有已判定的对局。"))
            return
        }
        content.addView(row("体系", "场次", "胜负", "胜率"))
        for (matchup in rows) {
            val sample = if (matchup.sample < BattleStatsCalculator.MIN_SAMPLE_FOR_RANKING) {
                "  n=${matchup.sample}"
            } else {
                ""
            }
            content.addView(
                row(
                    matchup.display + sample,
                    matchup.games.toString(),
                    "${matchup.wins}-${matchup.losses}",
                    matchup.winRate.asPercent(),
                ),
            )
        }
        content.addView(note("n<${BattleStatsCalculator.MIN_SAMPLE_FOR_RANKING} 的行标了 n=，样本太小，胜率不代表趋势。"))
    }

    private fun renderCards(stats: BattleStats) {
        content.addView(header("敌方卡牌使用率"))
        if (stats.cards.isEmpty()) {
            content.addView(note("还没有记录到敌方卡组。"))
            return
        }
        content.addView(row("卡牌", "出现", "出现率", "对阵胜率"))
        val total = stats.decided.coerceAtLeast(1)
        for (card in stats.cards.sortedByDescending { it.appearances }) {
            content.addView(
                row(
                    card.label + (if (card.decided < BattleStatsCalculator.MIN_SAMPLE_FOR_RANKING) " n=${card.decided}" else ""),
                    "${card.appearances}",
                    "${fmt(card.appearances * 100.0 / total)}%",
                    card.winRate.asPercent(),
                ),
            )
        }
        content.addView(note("进化 / 英雄形态单独成行，不与基础卡合并。"))
    }

    private fun renderDuration(stats: BattleStats) {
        content.addView(header("对局时长"))
        content.addView(kv("平均", stats.averageDurationSeconds.asClock()))
        content.addView(kv("中位", stats.medianDurationSeconds.asClock()))
        content.addView(kv("P25", stats.p25DurationSeconds.asClock()))
        content.addView(kv("P75", stats.p75DurationSeconds.asClock()))
        content.addView(kv("P90", stats.p90DurationSeconds.asClock()))
        content.addView(header("分桶"))
        content.addView(row("时长", "场次", "胜", "胜率"))
        for (bucket in stats.durationBuckets) {
            content.addView(
                row(bucket.labelZh, bucket.games.toString(), bucket.wins.toString(), bucket.winRate.asPercent()),
            )
        }
        content.addView(header("最长 10 局"))
        if (stats.longest.isEmpty()) {
            content.addView(note("没有时长数据。"))
            return
        }
        for (record in stats.longest) {
            content.addView(
                row(
                    timeFormat.format(Date(record.battleTime)),
                    record.enemyArchetype ?: "Unknown",
                    record.durationSeconds.asClock(),
                    record.result.wire,
                ),
            )
        }
    }

    private fun renderLosses(stats: BattleStats) {
        content.addView(header("败局分析"))
        content.addView(kv("败局平均时长", stats.lossAnalysis.averageDurationSeconds.asClock()))
        content.addView(kv("败局中位时长", stats.lossAnalysis.medianDurationSeconds.asClock()))

        content.addView(header("最容易输的体系 (n>=${BattleStatsCalculator.MIN_SAMPLE_FOR_RANKING})"))
        if (stats.lossAnalysis.worstMatchups.isEmpty()) {
            content.addView(note("样本还不够，暂不排名。"))
        } else {
            for (matchup in stats.lossAnalysis.worstMatchups) {
                content.addView(
                    row(matchup.display, "n=${matchup.sample}", "${matchup.wins}-${matchup.losses}", matchup.winRate.asPercent()),
                )
            }
        }

        content.addView(header("最容易输的卡 (n>=${BattleStatsCalculator.MIN_SAMPLE_FOR_RANKING})"))
        if (stats.lossAnalysis.worstCards.isEmpty()) {
            content.addView(note("样本还不够，暂不排名。"))
        } else {
            for (card in stats.lossAnalysis.worstCards) {
                content.addView(
                    row(card.label, "n=${card.decided}", "${card.wins}-${card.losses}", card.winRate.asPercent()),
                )
            }
        }

        content.addView(header("败局皇冠分布"))
        if (stats.lossAnalysis.crownDistribution.isEmpty()) {
            content.addView(note("还没有败局。"))
        } else {
            for ((crowns, count) in stats.lossAnalysis.crownDistribution) {
                content.addView(kv("对手 $crowns 冠", "$count 场"))
            }
        }
    }

    private fun renderList(slice: List<BattleRecord>) {
        content.addView(header("历史列表"))
        if (slice.isEmpty()) {
            content.addView(note("数据库是空的。先打开「开始记录」，之后每局都会自动保存。"))
            return
        }
        content.addView(row("时间", "对手体系", "结果", "皇冠", "时长"))
        for (record in slice) {
            val open = expanded == record.battleUid
            content.addView(
                TextView(this).apply {
                    text = buildString {
                        append(timeFormat.format(Date(record.battleTime)))
                        append("  ").append(record.enemyArchetype ?: "Unknown")
                        if (!record.enemyArchetypeSubtype.isNullOrBlank()) {
                            append(" ").append(record.enemyArchetypeSubtype)
                        }
                        append("  ").append(resultLabel(record))
                        append("  ").append(record.myCrowns ?: "-")
                        append("-").append(record.enemyCrowns ?: "-")
                        append("  ").append(record.durationSeconds.asClock())
                    }
                    setTextColor(if (record.result == BattleResult.LOSS) LOSS else FG)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setOnClickListener {
                        expanded = if (open) null else record.battleUid
                        render()
                    }
                },
            )
            if (open) content.addView(detail(record))
        }
    }

    private fun detail(record: BattleRecord): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        box.addView(kv("来源", sourceLabel(record)))
        box.addView(kv("结果", resultLabel(record) + "  " + record.status.wire))
        box.addView(kv("皇冠", "${record.myCrowns ?: "-"} - ${record.enemyCrowns ?: "-"}"))
        box.addView(kv("时长", record.durationSeconds.asClock() + "  (${record.durationTicks ?: 0} ticks)"))
        box.addView(kv("完整对局", if (record.fullBattle) "是" else "否（只观测到后半段）"))
        box.addView(kv("奖杯变化", record.trophyChange?.toString() ?: "n/a"))
        box.addView(kv("Battle ID", record.battleId ?: "n/a"))
        box.addView(kv("Replay ID", record.replayId ?: "n/a"))
        box.addView(kv("Replay", if (record.replayId.isNullOrBlank()) "无" else "Replay available"))
        box.addView(kv("身份来源", record.identitySource ?: "n/a"))
        box.addView(header("我的卡组"))
        box.addView(deckLine(record, true))
        box.addView(header("对手卡组"))
        box.addView(deckLine(record, false))
        return box
    }

    private fun deckLine(record: BattleRecord, mine: Boolean): View {
        val cards = if (mine) record.myDeck else record.enemyDeck
        val text = if (cards.isEmpty()) {
            "（未记录）"
        } else {
            cards.sortedBy { it.slot }.joinToString("\n") { card ->
                val cost = card.cost?.let { " [$it]" } ?: ""
                "${card.slot + 1}. ${card.label} · ${card.labelZh}$cost"
            }
        }
        return TextView(this).apply {
            setText(text)
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
    }

    private fun resultLabel(record: BattleRecord): String = when (record.result) {
        BattleResult.WIN -> "胜"
        BattleResult.LOSS -> "负"
        BattleResult.DRAW -> "平"
        BattleResult.INCOMPLETE -> "未完成"
        BattleResult.UNKNOWN -> "未知"
    }

    private fun sourceLabel(record: BattleRecord): String = when (record.source) {
        BattleSource.LIVE_CAPTURE -> "实时记录 (live_capture)"
        BattleSource.NULLS_HISTORY -> "Nulls 历史 (nulls_history)"
    }

    // --------------------------------------------------------- actions --

    private fun export(csv: Boolean) {
        val slice = filtered()
        if (slice.isEmpty()) {
            toast("没有可导出的记录")
            return
        }
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
        Thread {
            val name = if (csv) BattleExport.csvFileName() else BattleExport.jsonFileName()
            val body = if (csv) BattleExport.toCsv(slice) else BattleExport.toJson(slice)
            val result = runCatching { ExportStore.write(this, name, body) }
            handler.post {
                result.fold(
                    onSuccess = { export ->
                        val withRows = export.copy(rows = slice.size)
                        content.addView(header("导出完成"))
                        content.addView(note(withRows.summary))
                        toast("已导出 ${withRows.rows} 行")
                    },
                    onFailure = { toast("导出失败: ${it.message}") },
                )
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Pulls whatever Null's own battle log still holds.
     *
     * The importer is complete; the source is not. Until a capture of the
     * game's own battle-log response is available, this reports exactly what is
     * missing instead of pretending to have synced, and imports any payload
     * file that was dropped into the app's `exports/` directory.
     */
    private fun syncHistory() {
        Thread {
            val payloads = BattleHistorySync.payloadFiles(this)
            if (payloads.isEmpty()) {
                handler.post {
                    content.addView(header("同步历史"))
                    content.addView(
                        note(
                            "没有找到 Nulls 历史 payload。\n" +
                                "Tracker 不会把实时场景猜成正式战绩。当前缺少游戏自己的 Battle Log payload。\n" +
                                "把抓到的 payload 放到：\n" +
                                "${BattleHistorySync.exportsDir(this).absolutePath}/history_payload_*.json\n" +
                                "再点一次「同步历史」即可导入。",
                        ),
                    )
                }
                return@Thread
            }
            val result = BattleHistorySync.syncBlocking(this, BattleWorkingSessions.snapshot())
            for (uid in result.consumedLiveUids) BattleWorkingSessions.consume(uid)
            handler.post {
                content.addView(header("同步历史"))
                content.addView(note("来源: ${payloads.joinToString(", ") { it.name }}\n${result.summary}"))
                reload()
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Destructive and therefore behind an explicit confirmation. The database is
     * the only copy of the history, so this is never a single tap.
     */
    private fun confirmClear() {
        android.app.AlertDialog.Builder(this)
            .setTitle("清空全部对战记录？")
            .setMessage(
                "会删除数据库里的全部记录（含卡组明细），无法恢复。\n" +
                    "建议先导出 CSV / JSON 备份。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                Thread {
                    BattleHistory.run(this) { dao -> dao.deleteAll() }.get()
                    handler.post {
                        expanded = null
                        toast("已清空")
                        reload()
                    }
                }.apply { isDaemon = true }.start()
            }
            .show()
    }

    private fun scroller(children: List<View>): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (child in children) addView(child)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8), 0, dp(8), 0)
            addView(row)
        }
    }

    private fun actionButton(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { action() }
        }

    private fun chip(text: String, selected: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = if (selected) 1f else 0.55f
            setOnClickListener { action() }
        }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(4), 0)
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ACCENT)
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(4), dp(16), dp(4), dp(6))
    }

    private fun kv(key: String, value: String): TextView = TextView(this).apply {
        text = "$key: $value"
        setTextColor(FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(4), dp(3), dp(4), dp(3))
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun row(vararg cells: String): TextView = TextView(this).apply {
        text = cells.joinToString("   ")
        setTextColor(FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(4), dp(5), dp(4), dp(5))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val BG = Color.parseColor("#0E1116")
        private val PANEL = Color.parseColor("#161B22")
        private val FG = Color.parseColor("#E6EDF3")
        private val MUTED = Color.parseColor("#8B949E")
        private val ACCENT = Color.parseColor("#58A6FF")
        private val LOSS = Color.parseColor("#F85149")

        fun intent(context: android.content.Context): Intent =
            Intent(context, HistoryActivity::class.java)
    }
}

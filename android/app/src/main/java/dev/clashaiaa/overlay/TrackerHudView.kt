package dev.clashaiaa.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Compact in-game HUD with real card art, zh-CN names, costs, the native cycle
 * and the native evolution/hero marks. The information content mirrors the
 * Windows HUD (`tracker/overlay.py`): elixir with one decimal, four opponent
 * hand tiles, the `NEXT` cycle row and the recent-plays row.
 *
 * The four hand tiles are also a live elixir gauge: a card the opponent cannot
 * pay for is greyed, and a darker clock sector covers the share of the cost that
 * is still unpaid, receding clockwise from 12 o'clock as the elixir comes back.
 * Full colour means "castable right now". The exact number stays next to the
 * droplet; the gauge is the glanceable version of it and never replaces it.
 * `NEXT` and recent tiles are deliberately **not** treated this way: a card
 * behind the hand is not castable at any price.
 */
class TrackerHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val catalog = CardCatalog(context.applicationContext)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private var model: OverlayModel = OverlayRenderer.disconnected()

    private val affordability = AffordabilityAnimator()
    private val greyFilter = ColorMatrixColorFilter(greyColorMatrix())
    private val tilePath = Path()
    private val wedgeOval = RectF()

    fun render(value: OverlayModel) {
        model = value
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val footer = if (model.identityLabel.isEmpty()) 0 else dp(14)
        val desired = when {
            model.showBattleData -> dp(if (model.recentIds.isEmpty()) 188 else 242) + footer
            model.notice != null -> dp(58) + footer
            else -> dp(48)
        }
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!model.showBattleData) {
            affordability.reset()
            text(
                canvas,
                model.notice ?: if (model.waiting) "等待对局数据" else "探针未连接",
                dp(12f),
                dp(28f),
                13f,
                0xffffcc80.toInt(),
            )
            identityFooter(canvas)
            return
        }

        val pad = dp(8f)
        val elixirY = dp(24f)
        paint.color = 0xffb256ff.toInt()
        canvas.drawCircle(pad + dp(9f), elixirY - dp(5f), dp(8f), paint)
        text(canvas, "敌方圣水  ${model.elixir}", pad + dp(23f), elixirY, 17f, Color.WHITE, bold = true)

        val handTop = dp(34f)
        val gap = dp(4f)
        val tileWidth = (width - pad * 2 - gap * 3) / 4f
        val tileHeight = dp(88f)
        val ids = model.cardIds.take(4)
        // The effective cost is the probe's own selected cost, then the bundled
        // table - one lookup per hand tile, shared by the gauge and the badge.
        val costs = ids.mapIndexed { index, id ->
            effectiveCost(
                model.cardCosts.getOrNull(index),
                catalog.info(id, model.cards.getOrNull(index)).cost,
            )
        }
        // The gauge runs on the raw elixir, never on the formatted one-decimal text.
        val rawElixir = model.elixirValue
        val states = if (rawElixir == null) {
            affordability.reset()
        } else {
            affordability.update(ids, costs, rawElixir, System.nanoTime() / 1e9)
        }
        ids.forEachIndexed { index, id ->
            val left = pad + index * (tileWidth + gap)
            drawCard(
                canvas,
                id,
                model.cards.getOrNull(index),
                model.cardCosts.getOrNull(index),
                model.cardBadges.getOrNull(index),
                RectF(left, handTop, left + tileWidth, handTop + tileHeight),
                false,
                affordability = states.getOrNull(index),
            )
        }

        val cycleTop = handTop + tileHeight + dp(9f)
        text(canvas, "NEXT", pad, cycleTop + dp(30f), 9f, 0xff80d8ff.toInt(), bold = true)
        val smallW = dp(39f)
        val smallH = dp(46f)
        model.cycleIds.take(4).forEachIndexed { index, id ->
            val left = pad + dp(43f) + index * (smallW + dp(5f))
            drawCard(
                canvas,
                id,
                null,
                null,
                model.cycleBadges.getOrNull(index),
                RectF(left, cycleTop, left + smallW, cycleTop + smallH),
                true,
                highlighted = index == 0,
            )
        }

        if (model.recentIds.isNotEmpty()) {
            val recentTop = cycleTop + smallH + dp(8f)
            text(canvas, "刚出", pad, recentTop + dp(29f), 10f, 0xff90a4ae.toInt(), bold = true)
            model.recentIds.take(4).forEachIndexed { index, id ->
                val left = pad + dp(43f) + index * (smallW + dp(5f))
                drawCard(canvas, id, null, null, null, RectF(left, recentTop, left + smallW, recentTop + smallH), true)
            }
        }
        identityFooter(canvas)
        // The sweep is the only thing here that moves on its own: while a tile is
        // still travelling, ask for the next frame. Once every tile has settled
        // nothing is scheduled, so an idle HUD costs nothing.
        if (affordability.animating) postInvalidateOnAnimation()
    }

    private fun drawCard(
        canvas: Canvas,
        cardId: Int,
        fallback: String?,
        attestedCost: Int?,
        badge: CardBadge?,
        box: RectF,
        compact: Boolean,
        highlighted: Boolean = false,
        affordability: CardAffordabilityState? = null,
    ) {
        val ready = badge?.evoReady == true
        paint.color = 0xff1a1f2b.toInt()
        canvas.drawRoundRect(box, dp(5f), dp(5f), paint)
        val bitmap = catalog.art(cardId)
        if (bitmap != null) {
            src.set(0, 0, bitmap.width, bitmap.height)
            drawArt(canvas, bitmap, box, affordability)
        } else if (cardId > 0) {
            text(canvas, "?", box.centerX() - dp(4f), box.centerY() + dp(6f), 18f, 0xff90a4ae.toInt(), bold = true)
            if (affordability?.dimmed == true) {
                // No art to desaturate (a data gap, not a card): a flat dim layer
                // carries the same "not yet" without hiding the tile.
                paint.color = AFFORD_MASK_INK
                canvas.drawRoundRect(box, dp(5f), dp(5f), paint)
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(if (ready || highlighted) 2f else 1f)
        paint.color = when {
            ready -> COLOR_READY
            highlighted -> 0xff4fc3f7.toInt()
            else -> 0x995e6b83.toInt()
        }
        canvas.drawRoundRect(box, dp(5f), dp(5f), paint)
        paint.style = Paint.Style.FILL
        badge?.let { drawBadges(canvas, it, box, compact) }
        if (cardId <= 0) return
        // The probe's own selected cost is what the game charges right now; the
        // bundled table is only the fallback, exactly like the Windows HUD.
        val cost = attestedCost ?: catalog.info(cardId, fallback).cost
        cost?.let {
            paint.color = 0xffa843db.toInt()
            canvas.drawCircle(box.left + dp(if (compact) 7f else 10f), box.top + dp(if (compact) 7f else 10f), dp(if (compact) 7f else 10f), paint)
            text(canvas, it.toString(), box.left + dp(if (compact) 4.5f else 6.5f), box.top + dp(if (compact) 10f else 14f), if (compact) 8f else 11f, Color.WHITE, bold = true)
        }
        if (!compact) {
            paint.color = 0xc9000000.toInt()
            canvas.drawRect(box.left, box.bottom - dp(22f), box.right, box.bottom, paint)
            val label = ellipsize(catalog.info(cardId, fallback).name, box.width() - dp(6f), 10f)
            text(canvas, label, box.left + dp(3f), box.bottom - dp(7f), 10f, Color.WHITE, bold = true)
        }
    }

    /**
     * Real art, or the two-layer "cannot pay for it yet" look: the card face
     * with its colour removed, and a darker clock sector over the share of the
     * cost that is still unpaid.
     *
     * Layer 1 is the *same* bitmap through a colour matrix, so nothing is
     * decoded, scaled or allocated while elixir moves. Layer 2 is one
     * `drawArc(..., useCenter = true)` in a flat translucent ink - no path, no
     * second bitmap, no per-frame mask rebuild - and it starts where the
     * paid-for sweep ends and closes at 12 o'clock, so the dark area recedes
     * clockwise as the elixir comes back.
     *
     * Both layers are clipped to the card face, and the badges, the cost badge
     * and the name band are painted after this call, so the information on the
     * tile is never greyed by the gauge.
     *
     * A READY tile (or one whose cost the data cannot supply) takes the plain
     * single-draw path, so an all-colour hand is exactly as cheap as before.
     */
    private fun drawArt(
        canvas: Canvas,
        bitmap: Bitmap,
        box: RectF,
        affordability: CardAffordabilityState?,
    ) {
        if (affordability == null || !affordability.dimmed) {
            canvas.drawBitmap(bitmap, src, box, paint)
            return
        }
        val radius = dp(5f)
        tilePath.reset()
        tilePath.addRoundRect(box, radius, radius, Path.Direction.CW)
        val tileCheckpoint = canvas.save()
        canvas.clipPath(tilePath)

        val progress = affordability.visualProgress
        paint.colorFilter = greyFilter
        canvas.drawBitmap(bitmap, src, box, paint)
        paint.colorFilter = null

        val missing = missingFraction(progress)
        if (missing > 0f) {
            val centreX = box.centerX()
            val centreY = box.centerY()
            val reach = hypot(box.width() / 2f, box.height() / 2f) + dp(1f)
            wedgeOval.set(centreX - reach, centreY - reach, centreX + reach, centreY + reach)
            paint.color = AFFORD_MASK_INK
            canvas.drawArc(
                wedgeOval,
                maskStartDegrees(progress),
                maskSweepDegrees(progress),
                true,
                paint,
            )
        }
        canvas.restoreToCount(tileCheckpoint)
    }

    /**
     * Evolution mark top-right, hero mark bottom-left, above the name band; the
     * small cycle tiles only get the glyph so a badge cannot spill over the
     * neighbouring card. Mirrors `tracker/overlay.py::_draw_role_badges`.
     */
    private fun drawBadges(canvas: Canvas, badge: CardBadge, box: RectF, compact: Boolean) {
        if (!badge.any) return
        val evolution = if (compact) badge.evoShort else badge.evoLong
        if (evolution.isNotEmpty()) {
            drawBadge(
                canvas,
                evolution,
                box.right - dp(2f),
                box.top + dp(2f),
                if (badge.evoReady) COLOR_READY else COLOR_EVO,
                anchorRight = true,
                filled = badge.evoReady,
            )
        }
        if (badge.hero.isNotEmpty()) {
            drawBadge(
                canvas,
                if (compact) badge.hero.take(1) else badge.hero,
                box.left + dp(2f),
                box.bottom - dp(if (compact) 10f else 24f) - dp(11f),
                if (badge.heroReady) COLOR_READY else COLOR_ACCENT,
                anchorRight = false,
                filled = badge.heroReady,
            )
        }
    }

    private fun drawBadge(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        color: Int,
        anchorRight: Boolean,
        filled: Boolean,
    ) {
        val width = dp(sp(8f) * 0.62f * value.length + 6f)
        val height = dp(12f)
        val left = if (anchorRight) x - width else x
        val box = RectF(left, y, left + width, y + height)
        paint.style = Paint.Style.FILL
        paint.color = if (filled) color else 0xff12101c.toInt()
        canvas.drawRoundRect(box, dp(3f), dp(3f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = color
        canvas.drawRoundRect(box, dp(3f), dp(3f), paint)
        paint.style = Paint.Style.FILL
        paint.textSize = sp(8f)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.color = if (filled) 0xff0b0e14.toInt() else color
        canvas.drawText(value, box.left + dp(3f), box.bottom - dp(3.5f), paint)
    }

    /**
     * One short line naming the identity the panel used. A guessed identity is
     * amber, a confirmed one is grey, so a wrong account is visible instead of
     * silent.
     */
    private fun identityFooter(canvas: Canvas) {
        if (model.identityLabel.isEmpty()) return
        val color = if (model.identityVerified) 0xff90a4ae.toInt() else 0xffffb74d.toInt()
        text(canvas, model.identityLabel, dp(10f), height - dp(5f), 9f, color)
    }

    private fun ellipsize(value: String, maxWidth: Float, sp: Float): String {
        paint.textSize = sp(sp)
        if (paint.measureText(value) <= maxWidth) return value
        var text = value
        while (text.length > 1 && paint.measureText("$text…") > maxWidth) text = text.dropLast(1)
        return "$text…"
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, sizeSp: Float, color: Int, bold: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sp(sizeSp)
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
    }

    private fun dp(value: Int): Int = dp(value.toFloat()).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private companion object {
        /** `tracker/overlay.py`: READY = '#ffd166', EVO = '#c084fc', ACCENT. */
        const val COLOR_READY = 0xffffd166.toInt()
        const val COLOR_EVO = 0xffc084fc.toInt()
        const val COLOR_ACCENT = 0xff80d8ff.toInt()
    }
}

package dev.clashaiaa.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Full-screen, touch-through canvas for pending enemy placement previews. */
class GhostLayerView(context: Context) : View(context) {
    private val catalog = CardCatalog(context.applicationContext)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private var markers: List<GhostMarker> = emptyList()

    /**
     * The user's Ghost Drop alphas. Assigning a *different* pair repaints the
     * layer - which is what makes the settings slider live, on markers that are
     * already on the arena - and assigning the same pair repaints nothing.
     */
    var opacity: GhostOpacity = GhostOpacity.DEFAULT
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    fun render(value: List<GhostMarker>) {
        markers = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val game = gameRect(width.toFloat(), height.toFloat())
        val cellW = 50.91f * game.width() / 1080f
        val cellH = 42.4f * game.height() / 1920f
        val cardAlpha = opacity.cardAlpha
        val tileAlpha = opacity.tileAlpha
        markers.forEach { marker ->
            val point = project(marker.event.x, marker.event.y, marker.viewOwner, game) ?: return@forEach
            val age = System.currentTimeMillis() - marker.createdAtMs
            val fade = min(1f, age / 70f)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = Color.argb((tileAlpha * fade).toInt(), 238, 242, 248)
            val square = RectF(point.first - cellW / 2f, point.second - cellH / 2f, point.first + cellW / 2f, point.second + cellH / 2f)
            canvas.drawRoundRect(square, dp(3f), dp(3f), paint)

            val iconH = cellH
            val iconW = iconH * 302f / 363f
            val bottom = square.top - cellH * 0.25f
            val icon = RectF(point.first - iconW / 2f, bottom - iconH, point.first + iconW / 2f, bottom)
            paint.style = Paint.Style.FILL
            paint.alpha = (cardAlpha * fade).toInt()
            val bitmap = catalog.art(marker.event.cardId)
            if (bitmap != null) {
                src.set(0, 0, bitmap.width, bitmap.height)
                canvas.drawBitmap(bitmap, src, icon, paint)
            } else {
                // The placeholder carries the same alpha as the art would: at 0%
                // the whole marker is invisible, placeholder included.
                paint.color = Color.argb((cardAlpha * fade).toInt(), 0x1a, 0x1f, 0x2b)
                canvas.drawRoundRect(icon, dp(4f), dp(4f), paint)
            }
            paint.alpha = 255
        }
        if (markers.isNotEmpty()) postInvalidateDelayed(16L)
    }

    private fun project(x: Int, y: Int, owner: Int, game: RectF): Pair<Float, Float>? {
        if (owner !in 0..1) return null
        val viewX = if (owner == 1) x / 1000f else 18f - x / 1000f
        val viewY = if (owner == 1) 32f - y / 1000f else y / 1000f
        if (viewX !in 0f..18f || viewY !in 0f..32f) return null
        val logicalX = 50.91f * viewX + 81.81f
        val logicalY = -42.4f * viewY + 1496.4f
        val px = game.left + logicalX * game.width() / 1080f
        val py = game.top + logicalY * game.height() / 1920f
        if (!game.contains(px, py) || py > game.top + game.height() * 0.805f) return null
        return px to py
    }

    /** Largest 9:16 game surface inside a tablet/phone display. */
    private fun gameRect(w: Float, h: Float): RectF {
        val target = 9f / 16f
        return if (w / h > target) {
            val gameW = h * target
            RectF((w - gameW) / 2f, 0f, (w + gameW) / 2f, h)
        } else {
            val gameH = w / target
            RectF(0f, 0f, w, min(h, gameH))
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

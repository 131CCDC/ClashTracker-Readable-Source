package dev.clashaiaa.overlay

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Measures its single child at a stable design width, then draws and hit-tests it
 * at [contentScale]. This keeps text, cards, padding and controls uniformly scaled.
 */
class ScalablePanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var baseWidthPx: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    var contentScale: Float = 1f
        set(value) {
            field = PanelGeometry.clampScale(value)
            requestLayout()
            invalidate()
        }

    /** Receives physical window coordinates before child hit-testing. */
    var gestureHandler: ((MotionEvent) -> Boolean)? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (childCount == 0) {
            setMeasuredDimension(0, 0)
            return
        }
        val child = getChildAt(0)
        child.measure(
            MeasureSpec.makeMeasureSpec(baseWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val desiredWidth = (child.measuredWidth * contentScale).roundToInt()
        val desiredHeight = (child.measuredHeight * contentScale).roundToInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount == 0) return
        val child = getChildAt(0)
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.scale(contentScale, contentScale)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (gestureHandler?.invoke(event) == true) return true
        val transformed = MotionEvent.obtain(event)
        transformed.setLocation(event.x / contentScale, event.y / contentScale)
        return try {
            super.dispatchTouchEvent(transformed)
        } finally {
            transformed.recycle()
        }
    }
}

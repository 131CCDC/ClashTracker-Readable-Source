package dev.clashaiaa.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

data class PanelPlacement(
    val x: Int,
    val y: Int,
    val scale: Float,
)

/** Pure panel sizing rules, kept outside Android UI code for boundary tests. */
object PanelGeometry {
    const val DEFAULT_SCALE = 0.62f
    const val MIN_SCALE = 0.45f
    const val MAX_SCALE = 1.15f

    fun defaultPlacement(screenWidth: Int, topMargin: Int, baseWidth: Int): PanelPlacement {
        val width = (baseWidth * DEFAULT_SCALE).roundToInt()
        return PanelPlacement(
            x = ((screenWidth - width) / 2).coerceAtLeast(0),
            y = topMargin.coerceAtLeast(0),
            scale = DEFAULT_SCALE,
        )
    }

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun clamp(
        placement: PanelPlacement,
        screenWidth: Int,
        screenHeight: Int,
        panelWidth: Int,
        panelHeight: Int,
    ): PanelPlacement = placement.copy(
        x = placement.x.coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0)),
        y = placement.y.coerceIn(0, (screenHeight - panelHeight).coerceAtLeast(0)),
        scale = clampScale(placement.scale),
    )

    fun resizedScale(
        startScale: Float,
        deltaX: Float,
        deltaY: Float,
        startWidth: Int,
        startHeight: Int,
    ): Float {
        val xRatio = if (startWidth > 0) deltaX / startWidth else 0f
        val yRatio = if (startHeight > 0) deltaY / startHeight else 0f
        val dominant = if (abs(xRatio) >= abs(yRatio)) xRatio else yRatio
        return clampScale(startScale * (1f + dominant))
    }
}

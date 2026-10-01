package dev.clashaiaa.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelGeometryTest {
    @Test
    fun `default is scaled and horizontally centered`() {
        assertEquals(PanelPlacement(669, 24, 0.62f), PanelGeometry.defaultPlacement(2000, 24, 1068))
    }

    @Test
    fun `placement cannot leave the display`() {
        assertEquals(
            PanelPlacement(1300, 1600, 0.62f),
            PanelGeometry.clamp(PanelPlacement(9999, 9999, 0.62f), 2000, 2400, 700, 800),
        )
    }

    @Test
    fun `resize follows dominant axis and clamps limits`() {
        assertEquals(0.93f, PanelGeometry.resizedScale(0.62f, 50f, 400f, 700, 800), 0.0001f)
        assertEquals(PanelGeometry.MIN_SCALE, PanelGeometry.resizedScale(0.62f, -1000f, 0f, 700, 800))
        assertEquals(PanelGeometry.MAX_SCALE, PanelGeometry.resizedScale(0.62f, 2000f, 0f, 700, 800))
    }
}

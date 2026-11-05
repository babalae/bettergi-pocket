package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeleportMathTest {
    @Test
    fun `drag steps keep the total and front-load the distance`() {
        assertEquals(3, MapDrag.stepCount(0.0))
        assertEquals(5, MapDrag.stepCount(48.0))
        assertEquals(60, MapDrag.stepCount(10_000.0))
        val steps = MapDrag.generateSteps(100, 10)
        assertEquals(100, steps.sum())
        val fastCount = kotlin.math.ceil(10 * MapDrag.FAST_STEP_RATIO).toInt()
        val fastSum = steps.take(fastCount).sum()
        assertTrue(fastSum >= 80)
    }

    @Test
    fun `negative drag steps also sum to the delta`() {
        val steps = MapDrag.generateSteps(-37, 6)
        assertEquals(-37, steps.sum())
    }

    @Test
    fun `recognition anomaly matches the BetterGI thresholds`() {
        assertTrue(MapDrag.isRecognitionAnomaly(100.0, 100.0, 1.0, 1.0, 250.0))
        assertFalse(MapDrag.isRecognitionAnomaly(100.0, 100.0, 1.0, 1.0, 150.0))
        assertTrue(MapDrag.isRecognitionAnomaly(1300.0, 200.0, 0.4, 1.0, 0.0))
        assertTrue(MapDrag.isRecognitionAnomaly(1300.0, 200.0, 1.0, 0.2, 0.0))
        assertFalse(MapDrag.isRecognitionAnomaly(1300.0, 200.0, 1.0, 0.9, 10.0))
    }

    @Test
    fun `drag pixels point toward the target`() {
        val unit = 1.0 / MapDrag.MAP_SCALE_FACTOR
        val pixels = MapDrag.dragPixels(Point2f(0f, 0f), unit, -unit, 1.0)
        assertEquals(1, pixels.first)
        assertEquals(-1, pixels.second)
    }

    @Test
    fun `slider y and click projection use the 1080p formulas`() {
        assertEquals(6.0, zoomFromSliderY(612.0), 1e-9)
        assertEquals(1.0, zoomFromSliderY(468.0), 1e-9)
        val click = projectMapClick(10.0, 20.0, 0.0, 0.0, 100.0, 50.0, 200.0, 100.0)
        assertEquals(20.0, click.first, 1e-9)
        assertEquals(40.0, click.second, 1e-9)
    }

    @Test
    fun `tp json uses position z as x and position x as y`() {
        val points = TpCatalog.parse(
            """
            {"data":[{"mapName":"Teyvat","points":[
              {"id":1,"type":"TeleportWaypoint","name":"风起地","country":"蒙德","position":[10, 0, -20]},
              {"id":2,"type":"TeleportWaypoint","name":"璃月港","country":"璃月","position":[100, 0, 50]}
            ]}]}
            """.trimIndent(),
        )
        assertEquals(-20.0, points[0].x, 0.0)
        assertEquals(10.0, points[0].y, 0.0)
        val nearest = TpCatalog.nearest(points, -18.0, 12.0, 1).single()
        assertEquals("风起地", nearest.name)
        assertFalse(TpCatalog.shouldSwitchCountry(0.0, 0.0, 10.0, 10.0))
        assertTrue(TpCatalog.shouldSwitchCountry(0.0, 0.0, 100.0, 0.0))
    }

    @Test
    fun `overlapping list prefers the name with more shared characters`() {
        val names = listOf("七天神像-岩" to 10.0, "七天神像-风" to 40.0)
        assertEquals(1, TpCatalog.chooseCandidate(names, "七天神像-风"))
        assertEquals("渊下宫", TpCatalog.normalizeAreaName("渊下宮"))
        assertTrue(TpCatalog.nameOverlap("神像风", "七天神像-风") > TpCatalog.nameOverlap("璃月港", "七天神像-风"))
    }
}

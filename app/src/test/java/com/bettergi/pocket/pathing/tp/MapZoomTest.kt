package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.model.HandledException
import org.junit.Assert.*
import org.junit.Test

class MapZoomTest {
    @Test
    fun `slider corrections go directly to target and stay on track near both ends`() {
        val down = MapZoom.Slider(1.62, 93, 809, 762, 1142)
        assertEquals(836, down.targetY(1.973290626826965))
        val up = MapZoom.Slider(5.34, 93, 1092, 762, 1142)
        assertEquals(1060, up.targetY(4.92))
        // 接近轨道末端时也直接抵达端点，避免先反向移动再返回。
        assertEquals(1142, MapZoom.Slider(5.92, 93, 1136, 762, 1142).targetY(6.0))
        assertEquals(762, MapZoom.Slider(1.08, 93, 768, 762, 1142).targetY(1.0))
    }

    @Test
    fun `tablet small zoom correction drags to absolute slider position and verifies feedback`() {
        val slider = MapZoom.Slider(5.342105263157895, 94, 1092, 762, 1142)
        assertEquals(1060, slider.targetY(4.920374711586194))
        assertEquals(762, slider.targetY(0.0))
        assertEquals(1142, slider.targetY(7.0))
        var attempts = 0
        val actual = MapZoom.adjust(slider.level, 4.920374711586194) { _, goal ->
            attempts++
            1.0 + 5.0 * (slider.targetY(goal) - slider.zoomInY) / (slider.zoomOutY - slider.zoomInY)
        }
        assertEquals(1, attempts)
        assertEquals(4.920374711586194, actual, MapZoom.PRECISION)
    }

    @Test
    fun `zooming in spreads fingers and zooming out brings them together`() {
        for ((width, height) in listOf(3040 to 1904, 1920 to 1080, 1280 to 720)) {
            val zoomIn = MapZoom.pinchPlan(width, height, 6.0, 2.0)
            val zoomOut = MapZoom.pinchPlan(width, height, 2.0, 6.0)
            assertTrue(zoomIn.endSpan > zoomIn.startSpan)
            assertTrue(zoomOut.endSpan < zoomOut.startSpan)
            for (span in listOf(zoomIn.startSpan, zoomIn.endSpan, zoomOut.startSpan, zoomOut.endSpan)) {
                assertTrue(span > 0 && span < width)
            }
        }
    }

    @Test
    fun `smallest map zooms directly to visible level before center recognition`() {
        var actualZoom = 6.0
        val operations = mutableListOf<String>()
        val result = MapZoom.locateCenter(
            actualZoom,
            readCenter = {
                operations += "read:$actualZoom"
                if (actualZoom <= 4.4) Point2f(10f, 20f) else null
            },
            adjust = { target -> operations += "zoom:$target"; actualZoom = target; target },
        )!!
        assertEquals(Point2f(10f, 20f), result.first)
        assertEquals(3.0, result.second, 0.0)
        assertEquals(listOf("zoom:3.0", "read:3.0"), operations)
    }

    @Test
    fun `unrecognized center never causes alternating zoom recovery`() {
        var attempts = 0
        assertNull(MapZoom.locateCenter(6.0, { null }, { attempts++; it }))
        assertEquals(1, attempts)
    }

    @Test
    fun `already visible fine map is not shrunk before dragging`() {
        val center = Point2f(10f, 20f)
        val result = MapZoom.locateCenter(2.0, { center }, { fail("已经能看到传送点时不应缩小"); it })!!
        assertEquals(center, result.first)
        assertEquals(2.0, result.second, 0.0)
    }

    @Test
    fun `feedback uses measured levels and stops when gestures have no effect`() {
        var attempts = 0
        val final = MapZoom.adjust(6.0, 2.0) { current, target ->
            attempts++
            maxOf(target, current - 0.7)
        }
        assertEquals(2.0, final, MapZoom.PRECISION)
        assertTrue(attempts > 1)
        attempts = 0
        assertEquals(6.0, MapZoom.adjust(6.0, 2.0) { current, _ -> attempts++; current }, 0.0)
        assertEquals(2, attempts)
        MapZoom.adjust(2.0, 2.0) { _, _ -> fail("已经达到等级时不应再发手势"); 2.0 }
    }

    @Test(expected = HandledException::class)
    fun `invalid feedback never masquerades as maximum zoom`() {
        MapZoom.adjust(6.0, 2.0) { _, _ -> Double.NaN }
    }

    @Test
    fun `dense teleport points require more zoom before clicking`() {
        assertEquals(1.0, MapZoom.finalClickLevel(10.0), 0.0)
        assertEquals(2.0, MapZoom.finalClickLevel(72.0), 0.0)
        assertEquals(4.4, MapZoom.finalClickLevel(1000.0), 0.0)
    }

    @Test
    fun `near target at smallest map zooms in even without dragging`() {
        val zooms = mutableListOf<Double>()
        val center = Point2f(10f, 20f)
        val arrived = MapDrag.moveToward(
            center, 10.0, 20.0, 6.0,
            readCenter = { center },
            drag = { _, _ -> fail("地图中心已在目标上，不应拖动"); 0 to 0 },
            finalZoom = 2.0,
            adjustZoom = { zooms += it; it },
        )
        assertEquals(center, arrived)
        assertEquals(listOf(2.0), zooms)
    }

    @Test
    fun `long travel zooms out then zooms in and predicts using the measured scale`() {
        val zooms = mutableListOf<Double>()
        var actualZoom = 1.0
        var actualCenter = Point2f(0f, 0f)
        MapDrag.moveToward(
            actualCenter, 5000.0, 0.0, actualZoom,
            readCenter = { predicted ->
                assertEquals(actualCenter.x, predicted.x, 0.01f)
                actualCenter
            },
            drag = { dx, dy ->
                val sentX = dx.coerceIn(-500, 500)
                actualCenter = MapDrag.predictCenter(actualCenter, sentX, dy, actualZoom)
                sentX to dy
            },
            finalZoom = 2.0,
            adjustZoom = { target -> actualZoom = target; zooms += target; target },
        )
        assertEquals(6.0, zooms.first(), 0.0)
        assertEquals(2.0, zooms.last(), MapZoom.PRECISION)
        assertTrue(kotlin.math.abs(actualCenter.x - 5000f) < 200f)
    }
}

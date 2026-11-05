package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.navigation.Navigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Rect

/** 坐标换算与朝向计算，公式见 BGI `SceneBaseMap` / `Navigation`。 */
class MapCoordinateTest {
    private val map = TeyvatTemplateMatchMap(FileMapAssetSource(java.io.File(".")))

    @Test
    fun `game origin maps to teyvat image origin`() {
        assertEquals(Point2f(32768f, 24576f), map.convertGenshinMapCoordinatesToImageCoordinates(Point2f(0f, 0f)))
        // imgX = 32768 - 2 * gameX
        assertEquals(Point2f(32768f - 2 * -876f, 24576f - 2 * 2278f), map.convertGenshinMapCoordinatesToImageCoordinates(Point2f(-876f, 2278f)))
        assertEquals(Point2f(-876f, 2278f), map.convertImageCoordinatesToGenshinMapCoordinates(Point2f(34520f, 20020f)))
    }

    @Test
    fun `image origin is treated as recognition failure`() {
        assertNull(map.convertImageCoordinatesToGenshinMapCoordinates(Point2f(0f, 0f)))
        assertNull(map.convertImageCoordinatesToGenshinMapCoordinates(Rect(0, 0, 0, 0)))
    }

    @Test
    fun `rect conversion follows integer center and scale`() {
        // 中心 (32768 + 50, 24576 + 25) -> 游戏 (-25, -12.5)，宽高除以 2
        val game = map.convertImageCoordinatesToGenshinMapCoordinates(Rect(32768, 24576, 100, 50))!!
        assertEquals(Rect(-50, -25, 50, 25), game)
        // 反向换算时中心取 (-50 + 25, -25 + 25 / 2) = (-25, -13)，整数除法使 y 偏 1，与 BGI 一致
        assertEquals(Rect(32768, 24577, 100, 50), map.convertGenshinMapCoordinatesToImageCoordinates(game))
    }

    @Test
    fun `target orientation uses image axes clockwise from east`() {
        val origin = Point2f(100f, 100f)
        assertEquals(0, Navigation.getTargetOrientation(200.0, 100.0, origin))
        assertEquals(90, Navigation.getTargetOrientation(100.0, 200.0, origin))
        assertEquals(180, Navigation.getTargetOrientation(0.0, 100.0, origin))
        assertEquals(270, Navigation.getTargetOrientation(100.0, 0.0, origin))
        assertEquals(0, Navigation.getTargetOrientation(100.0, 100.0, origin))
        assertEquals(5.0, Navigation.getDistance(103.0, 104.0, origin), 1e-9)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = PathingTestData.ensureOpenCv()
    }
}

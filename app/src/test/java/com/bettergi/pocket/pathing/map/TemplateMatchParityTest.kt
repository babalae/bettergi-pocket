package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.pathing.common.Point2f
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 小地图 TemplateMatch 定位与 BGI 对拍。每张图依次执行：全局 → 以全局结果为 prev 的局部 → 偏移 prev 的局部，
 * 每张图开始前清空 prevSuccessResult，与 tools/bgi-golden 的调用顺序一致。
 *
 * 需要 assets/pathing 地图资源（tools/sync-pathing-assets.ps1），未同步时跳过。
 */
class TemplateMatchParityTest {
    @Test
    fun `template match positions match BetterGI`() {
        val map = TeyvatTemplateMatchMap(PathingTestData.requireAssets())
        try {
            val start = System.nanoTime()
            map.warmUp()
            println("TemplateMatch 图层 ${map.templateLayers.size} 个，加载 ${(System.nanoTime() - start) / 1_000_000} ms")
            val colorPixels = map.templateLayers.sumOf { it.coarseColorMatcher.sourceWidth.toLong() * it.coarseColorMatcher.sourceHeight }
            // 粗匹配彩图常驻 3 个 8 位通道
            println("原生内存：彩图 ${colorPixels * 3 / 1048576} MiB")
            assertEquals(342, map.templateLayers.size)

            val cases = PathingTestData.golden.getJSONArray("miniMaps")
            val report = StringBuilder()
            var worst = 0.0
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val file = case.getString("file")
                val miniMap = PathingTestData.readGolden(file)
                map.prevSuccessResult = SceneBaseMapByTemplateMatch.MatchResult()

                val t0 = System.nanoTime()
                val global = map.getMiniMapPosition(miniMap)
                val t1 = System.nanoTime()
                worst = maxOf(worst, check("$file global", case.optJSONObject("templateGlobal"), global, map, report))

                val local = map.getMiniMapPosition(miniMap, global.x, global.y)
                val t2 = System.nanoTime()
                worst = maxOf(worst, check("$file local", case.optJSONObject("templateLocal"), local, map, report))

                val offsetPrev = case.getJSONArray("templateLocalOffsetPrev")
                val localOffset = map.getMiniMapPosition(miniMap, offsetPrev.getDouble(0).toFloat(), offsetPrev.getDouble(1).toFloat())
                worst = maxOf(worst, check("$file localOffset", case.optJSONObject("templateLocalOffset"), localOffset, map, report))
                report.append("$file 全局 ${(t1 - t0) / 1_000_000} ms，局部 ${(t2 - t1) / 1_000_000} ms\n")
                miniMap.release()
            }
            println(report)
            println("最大坐标偏差 $worst，灰度图缓存 ${map.grayMapCache.loadedBytes() / 1048576} MiB")
        } finally {
            map.release()
        }
    }

    /** @return 坐标偏差（2048 级别图像像素） */
    private fun check(label: String, expected: JSONObject?, actual: Point2f, map: TeyvatTemplateMatchMap, report: StringBuilder): Double {
        if (expected == null) {
            assertTrue("$label BGI 匹配失败，Pocket 应同样失败，实际 $actual", actual.isDefault)
            report.append("$label 均失败\n")
            return 0.0
        }
        assertTrue("$label BGI 成功，Pocket 失败", !actual.isDefault)
        val dx = actual.x - expected.getDouble("x")
        val dy = actual.y - expected.getDouble("y")
        val distance = kotlin.math.hypot(dx, dy)
        val prev = map.prevSuccessResult
        report.append(
            "$label d=${"%.4f".format(distance)} conf=${"%.5f".format(prev.confidence)}/${"%.5f".format(expected.getDouble("confidence"))} " +
                "layer=${prev.layer?.layerId}/${expected.optString("layer")}\n",
        )
        assertEquals("$label 图层", expected.optString("layer"), prev.layer?.layerId)
        assertTrue("$label 坐标偏差 $distance 过大", distance <= POSITION_TOLERANCE)
        assertEquals("$label 置信度", expected.getDouble("confidence"), prev.confidence, CONFIDENCE_TOLERANCE)
        return distance
    }

    @Test
    fun `layer json keeps BetterGI defaults`() {
        val layers = BaseMapLayerByTemplateMatch.parseLayers(
            """[{"LayerId":"MapBack_1","Floor":0,"Scale":1,"Name":"提瓦特","Left":7168.0,"Top":6144.0},{"LayerId":"X"}]""",
        )
        assertEquals("MapBack_1", layers[0].layerId)
        assertEquals(7168f, layers[0].left)
        assertEquals(1f, layers[1].scale)
        assertEquals(false, layers[1].isOverSize)
        assertNull(null)
    }

    companion object {
        /** 2048 级别图像像素，0.5 像素 = 0.25 个游戏单位。 */
        private const val POSITION_TOLERANCE = 0.5
        private const val CONFIDENCE_TOLERANCE = 2e-3

        @JvmStatic
        @BeforeClass
        fun setUp() = PathingTestData.ensureOpenCv()

        @JvmStatic
        @AfterClass
        fun tearDown() = BigMapTeyvat256Layer.releaseInstance()
    }
}

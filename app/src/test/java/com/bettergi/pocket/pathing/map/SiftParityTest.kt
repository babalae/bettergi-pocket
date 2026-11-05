package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.pathing.common.Point2f
import org.json.JSONArray
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/**
 * SIFT 匹配与 BGI 对拍：小地图 2048 图层（局部 BruteForce、全局 FLANN），大地图 256 图层。
 * FLANN 是近似最近邻，不同 OpenCV 版本的结果可能有亚像素到数像素的差别，容差放宽。
 *
 * 需要 assets/pathing 地图资源，未同步时跳过。
 */
class SiftParityTest {
    @Test
    fun `mini map sift positions match BetterGI`() {
        val map = TeyvatMap(PathingTestData.requireAssets())
        try {
            val start = System.nanoTime()
            map.warmUp()
            println("SIFT 2048 特征点 ${map.layers[0].trainKeyPoints.size} 个，加载 ${(System.nanoTime() - start) / 1_000_000} ms")
            val cases = PathingTestData.golden.getJSONArray("miniMaps")
            val report = StringBuilder()
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val global = case.optJSONObject("templateGlobal") ?: continue
                val file = case.getString("file")
                val miniMap = PathingTestData.readGolden(file)

                val t0 = System.nanoTime()
                val local = map.getMiniMapPosition(miniMap, global.getDouble("x").toFloat(), global.getDouble("y").toFloat())
                report.append("$file local ${(System.nanoTime() - t0) / 1_000_000} ms ")
                checkPoint("$file siftLocal", case.optJSONArray("siftLocal"), local, LOCAL_TOLERANCE, report)

                if (case.has("siftGlobal")) {
                    val t1 = System.nanoTime()
                    val globalSift = map.getMiniMapPosition(miniMap)
                    report.append("$file global ${(System.nanoTime() - t1) / 1_000_000} ms ")
                    checkPoint("$file siftGlobal", case.optJSONArray("siftGlobal"), globalSift, FLANN_TOLERANCE, report)
                }
                miniMap.release()
            }
            println(report)
        } finally {
            map.release()
        }
    }

    @Test
    fun `big map sift matches BetterGI`() {
        val map = TeyvatTemplateMatchMap(PathingTestData.requireAssets())
        val cases = PathingTestData.golden.getJSONArray("bigMaps")
        val report = StringBuilder()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val file = case.getString("file")
            val crop = PathingTestData.readGolden(file, Imgcodecs.IMREAD_GRAYSCALE)
            // 与 tools/bgi-golden 相同：裁剪图线性放大 4 倍得到 1920x1080 合成大地图
            val screen = Mat()
            Imgproc.resize(crop, screen, Size(1920.0, 1080.0), 0.0, 0.0, Imgproc.INTER_LINEAR)

            checkPoint("$file position", case.optJSONArray("position"), map.getBigMapPosition(screen), FLANN_TOLERANCE, report)
            val expected = case.getJSONArray("expectedCenter")
            val withExpected = map.getBigMapPosition(screen, Point2f(expected.getDouble(0).toFloat(), expected.getDouble(1).toFloat()))
            checkPoint("$file positionWithExpected", case.optJSONArray("positionWithExpected"), withExpected, FLANN_TOLERANCE, report)

            val rect = map.getBigMapRect(screen)
            val expectedRect = case.getJSONArray("rect")
            report.append("$file rect=$rect expected=$expectedRect\n")
            assertTrue("$file rect", abs(rect.x - expectedRect.getInt(0)) <= 1 && abs(rect.y - expectedRect.getInt(1)) <= 1)
            assertTrue("$file rect size", abs(rect.width - expectedRect.getInt(2)) <= 1 && abs(rect.height - expectedRect.getInt(3)) <= 1)

            // 合成图来自 Teyvat_0_256.png，结果应落在裁剪中心附近
            val cropRect = case.getJSONArray("cropRect")
            val p = map.getBigMapPosition(screen)
            assertEquals(cropRect.getInt(0) + 240.0, p.x.toDouble(), 2.0)
            assertEquals(cropRect.getInt(1) + 135.0, p.y.toDouble(), 2.0)
            crop.release()
            screen.release()
        }
        println(report)
        map.release()
    }

    private fun checkPoint(label: String, expected: JSONArray?, actual: Point2f, tolerance: Double, report: StringBuilder) {
        if (expected == null) {
            report.append("$label BGI 失败，Pocket=$actual\n")
            assertTrue("$label BGI 匹配失败，Pocket 应同样失败，实际 $actual", actual.isDefault)
            return
        }
        assertTrue("$label BGI 成功，Pocket 失败", !actual.isDefault)
        val distance = hypot(actual.x - expected.getDouble(0), actual.y - expected.getDouble(1))
        report.append("$label d=${"%.4f".format(distance)}\n")
        assertTrue("$label 坐标偏差 $distance 超过 $tolerance", distance <= tolerance)
    }

    companion object {
        /** 2048 级别图像像素。BruteForce 是精确匹配，差异只来自 SIFT 特征提取。 */
        private const val LOCAL_TOLERANCE = 1.0
        private const val FLANN_TOLERANCE = 2.0

        @JvmStatic
        @BeforeClass
        fun setUp() = PathingTestData.ensureOpenCv()

        @JvmStatic
        @AfterClass
        fun tearDown() = BigMapTeyvat256Layer.releaseInstance()
    }
}

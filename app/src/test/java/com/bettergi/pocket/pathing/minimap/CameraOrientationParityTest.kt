package com.bettergi.pocket.pathing.minimap

import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.pathing.common.csRoundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 与 BGI 原实现对拍：视角角度、置信度、TemplateMatch 预处理结果。
 * 基准由 tools/bgi-golden 生成（BGI 1080p 截图裁出的 212×212 小地图）。
 */
class CameraOrientationParityTest {
    private val cases = PathingTestData.golden.getJSONArray("miniMaps")

    @Test
    fun `rotation angle and confidence match BetterGI`() {
        MiniMapPreprocessor().use { pre ->
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val miniMap = PathingTestData.readGolden(case.getString("file"))
                val (angle, confidence) = pre.predictRotationWithConfidence(miniMap)
                val expected = case.getJSONObject("rotation")
                assertEquals("${case.getString("file")} angle", expected.getDouble("angle"), angle.toDouble(), ANGLE_TOLERANCE)
                assertEquals("${case.getString("file")} confidence", expected.getDouble("confidence"), confidence.toDouble(), CONFIDENCE_TOLERANCE)
                assertEquals(
                    "${case.getString("file")} confidence fallback",
                    expected.getDouble("confidence") < CameraOrientation.MIN_CONFIDENCE,
                    confidence < CameraOrientation.MIN_CONFIDENCE,
                )
                miniMap.release()
            }
        }
    }

    @Test
    fun `camera orientation with gia fallback matches BetterGI`() {
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val miniMap = PathingTestData.readGolden(case.getString("file"))
            val copy = miniMap.clone()
            assertEquals(
                "${case.getString("file")} cameraOrientation",
                case.getDouble("cameraOrientation"),
                CameraOrientation.computeMiniMap(miniMap).toDouble(),
                ANGLE_TOLERANCE,
            )
            assertEquals(
                "${case.getString("file")} gia",
                case.getDouble("gia"),
                CameraOrientationFromGia.computeMiniMap(miniMap).toDouble(),
                ANGLE_TOLERANCE,
            )
            assertEquals("入参不应被修改", 0, diffCount(miniMap, copy))
            miniMap.release()
            copy.release()
        }
    }

    /**
     * 掩膜必须完全一致。预处理图只允许在视野扇区两条边界线上不同：
     * 扇区由 `Imgproc.ellipse` 以小数角度绘制，不同 OpenCV 版本（BGI 4.11、JVM 单测 4.9）对边界像素的光栅化不同。
     */
    @Test
    fun `mini map preprocessing matches BetterGI`() {
        val report = StringBuilder()
        MiniMapPreprocessor().use { pre ->
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val name = case.getString("file").removeSuffix(".png")
                val miniMap = PathingTestData.readGolden("$name.png")
                val (processed, mask) = pre.getMiniMapAndMask(miniMap)
                val expectedProcessed = PathingTestData.readGolden("${name}_pre.png")
                val expectedMask = PathingTestData.readGolden("${name}_mask.png", Imgcodecs.IMREAD_GRAYSCALE)
                assertEquals("$name 掩膜差异像素", 0, diffCount(mask, expectedMask))

                val angle = csRoundToInt(case.getJSONObject("rotation").getDouble("angle").toFloat())
                val boundaries = doubleArrayOf(angle + 45.5, angle + 314.5)
                val offBoundary = diffPixels(processed, expectedProcessed).filter { (x, y) ->
                    boundaries.none { distanceToRay(x, y, it) <= 2.0 }
                }
                report.append("$name 边界外差异像素 ${offBoundary.size}\n")
                assertTrue("$name 扇区边界外存在差异像素 $offBoundary", offBoundary.isEmpty())
                listOf(miniMap, processed, mask, expectedProcessed, expectedMask).forEach { it.release() }
            }
        }
        println(report)
    }

    /** 差异像素相对 156×156 中心 (78, 78) 的坐标。 */
    private fun diffPixels(a: Mat, b: Mat): List<Pair<Int, Int>> {
        val diff = Mat()
        Core.absdiff(a, b, diff)
        val size = MiniMapPreprocessorUtils.SIZE
        val data = ByteArray(size * size * 3).also { diff.get(0, 0, it) }
        diff.release()
        val result = ArrayList<Pair<Int, Int>>()
        for (i in 0 until size * size) {
            if (data[i * 3].toInt() != 0 || data[i * 3 + 1].toInt() != 0 || data[i * 3 + 2].toInt() != 0) {
                result.add((i % size - size / 2) to (i / size - size / 2))
            }
        }
        return result
    }

    /** 像素到从中心出发、角度为 [degrees]（图像坐标，顺时针）的射线的距离。 */
    private fun distanceToRay(x: Int, y: Int, degrees: Double): Double {
        val rad = Math.toRadians(degrees)
        val dx = cos(rad)
        val dy = sin(rad)
        val along = x * dx + y * dy
        if (along < -1.0) return Double.MAX_VALUE
        return abs(x * dy - y * dx)
    }

    private fun diffCount(a: Mat, b: Mat): Int {
        assertEquals(a.size(), b.size())
        assertEquals(a.type(), b.type())
        val diff = Mat()
        Core.absdiff(a, b, diff)
        val gray = if (diff.channels() > 1) {
            Mat().also { out ->
                val channels = ArrayList<Mat>()
                Core.split(diff, channels)
                out.create(diff.size(), channels[0].type())
                out.setTo(org.opencv.core.Scalar(0.0))
                channels.forEach { Core.max(out, it, out); it.release() }
            }
        } else {
            diff
        }
        val count = Core.countNonZero(gray)
        diff.release()
        if (gray !== diff) gray.release()
        return count
    }

    companion object {
        private const val ANGLE_TOLERANCE = 1e-3
        // BGI 4.11 与 JVM OpenCV 4.9 跨环境对拍：CI mm_16 的置信度差为 0.0015163。
        // 允许 0.2 个百分点的数值差异，同时保持角度、掩膜和是否触发 Gia 兜底的严格校验。
        private const val CONFIDENCE_TOLERANCE = 2e-3

        @JvmStatic
        @BeforeClass
        fun setUp() = PathingTestData.ensureOpenCv()
    }
}

package com.bettergi.pocket.pathing.minimap

import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import com.bettergi.pocket.recognition.SearchAnchorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class MiniMapLocatorTest {
    private val pcLocator = MiniMapLocator(MiniMapLocator.PC_1080P_LAYOUT)

    @Test
    fun `pc layout keeps BetterGI 1080p rect`() {
        assertEquals(IntRect(62, 19, 212, 212), pcLocator.nativeRect(1920, 1080))
    }

    @Test
    fun `auto anchor resolves top left and scales by the limiting side`() {
        val auto = MiniMapLocator(MiniMapLocator.PC_1080P_LAYOUT.copy(anchorMode = SearchAnchorMode.Auto))
        // 20:9：高度受限，scale = 1
        assertEquals(IntRect(62, 19, 212, 212), auto.nativeRect(2400, 1080))
        // 16:10 平板：宽度受限，scale = 2560 / 1920
        assertEquals(IntRect(83, 25, 283, 283), auto.nativeRect(2560, 1600))
        // 4K 16:9：scale = 2
        assertEquals(IntRect(124, 38, 424, 424), auto.nativeRect(3840, 2160))
    }

    @Test
    fun `rect outside the frame is rejected`() {
        val layout = MiniMapLayout(IntSize(1920, 1080), IntRect(1800, 19, 212, 212), SearchAnchorMode.TopLeft)
        assertNull(MiniMapLocator(layout).nativeRect(1920, 1080))
    }

    @Test
    fun `extract returns BetterGI standard size and keeps pixels at 1080p`() {
        val miniMap = PathingTestData.readGolden("mm_01.png")
        val frame = frameWith(miniMap, 1920, 1080)
        val extracted = pcLocator.extract(frame)!!
        assertEquals(Size(212.0, 212.0), extracted.size())
        val diff = Mat()
        Core.absdiff(extracted, miniMap, diff)
        assertEquals(0, Core.countNonZero(diff.reshape(1)))
        listOf(miniMap, frame, extracted, diff).forEach { it.release() }
    }

    @Test
    fun `second scaling brings high resolution mini map back to BetterGI scale`() {
        val cases = PathingTestData.golden.getJSONArray("miniMaps")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val miniMap = PathingTestData.readGolden(case.getString("file"))
            val frame1080 = frameWith(miniMap, 1920, 1080)
            // 模拟 4K 屏：整帧放大 2 倍，小地图 ROI 变为 424×424
            val frame4k = Mat()
            Imgproc.resize(frame1080, frame4k, Size(3840.0, 2160.0), 0.0, 0.0, Imgproc.INTER_CUBIC)
            val extracted = pcLocator.extract(frame4k)!!
            assertEquals(Size(212.0, 212.0), extracted.size())
            val angle = CameraOrientation.computeMiniMap(extracted)
            val expected = case.getDouble("cameraOrientation")
            val delta = Math.floorMod((angle - expected).toInt() + 180, 360) - 180
            assertTrue("${case.getString("file")} 视角 $angle，BGI $expected", kotlin.math.abs(delta) <= 3)
            listOf(miniMap, frame1080, frame4k, extracted).forEach { it.release() }
        }
    }

    @Test
    fun `layout json parses calibrated and empty values`() {
        val empty = PathingLayout.parse("""{"version":1,"referenceSize":null,"miniMap":null}""")
        assertNull(empty.miniMap)
        val calibrated = PathingLayout.parse(
            """{"version":1,"referenceSize":[3040,1904],"miniMap":{"bbox":"rect(100, 30, 336, 336)"}}""",
        )
        assertEquals(IntSize(3040, 1904), calibrated.referenceSize)
        assertEquals(IntRect(100, 30, 336, 336), calibrated.miniMap!!.bbox)
        assertEquals(SearchAnchorMode.Auto, calibrated.miniMap!!.anchorMode)
    }

    private fun frameWith(miniMap: Mat, width: Int, height: Int): Mat {
        val frame = Mat(height, width, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        val roi = frame.submat(Rect(62, 19, 212, 212))
        miniMap.copyTo(roi)
        roi.release()
        return frame
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = PathingTestData.ensureOpenCv()
    }
}

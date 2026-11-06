package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.pathing.vision.ScreenTemplate
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrResult
import com.bettergi.pocket.recognition.ocr.OcrResultRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs

class CountryRegionSelectorTest {
    @Test
    fun `left column country retains its first character and native click coordinates`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/pathing/phone-hud/region-country.png"))
            .use { it.readBytes() }
        val encoded = MatOfByte(*bytes)
        val word = try { Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE) } finally { encoded.release() }
        val frame = Mat(1904, 3040, CvType.CV_8UC1, Scalar(0.0))
        try {
            val target = frame.submat(Rect(1860, 840, word.cols(), word.rows()))
            try { word.copyTo(target) } finally { target.release() }
            val ocr = object : IOcrService {
                override fun recognize(mat: Mat): OcrResult {
                    // 使用真机文字裁图模拟 OCR 检测，首字被裁断时无法完整匹配。
                    val hit = ScreenTemplate.findBest(mat, word, null, 0.999) ?: return OcrResult.EMPTY
                    return OcrResult(listOf(OcrResultRegion(
                        IntRect(hit.x, hit.y, hit.width, hit.height), "挪 德 卡 莱", 1f)))
                }
            }
            assertEquals(1995 to 890, CountryRegionSelector.find(frame, "挪德卡莱", ocr))
        } finally {
            word.release(); frame.release()
        }
    }

    @Test
    fun `partial and repeated characters do not select the wrong country`() {
        val frame = Mat(100, 200, CvType.CV_8UC1, Scalar(0.0))
        try {
            for (text in listOf("德卡莱", "德德卡莱")) {
                val ocr = object : IOcrService {
                    override fun recognize(mat: Mat) = OcrResult(listOf(
                        OcrResultRegion(IntRect(10, 20, 40, 30), text, 1f)))
                }
                assertNull(CountryRegionSelector.find(frame, "挪德卡莱", ocr))
            }
        } finally { frame.release() }
    }

    @Test
    fun `selected country remains selectable when OCR combines the nearby icon`() {
        val frame = Mat(100, 200, CvType.CV_8UC1, Scalar(0.0))
        try {
            val ocr = object : IOcrService {
                override fun recognize(mat: Mat) = OcrResult(listOf(
                    OcrResultRegion(IntRect(10, 20, 40, 30), "挪德卡菜 工", 1f)))
            }
            assertEquals(142 to 35, CountryRegionSelector.find(frame, "挪德卡莱", ocr))
        } finally { frame.release() }
    }

    companion object {
        @JvmStatic @BeforeClass
        fun setUp() = PathingTestData.ensureOpenCv()
    }
}

package com.bettergi.pocket.recognition

import com.bettergi.pocket.pathing.vision.HudTemplateMask
import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.recognition.area.GameUiCalibrator
import com.bettergi.pocket.recognition.area.ReferenceSearch
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/** 24117RK2CC 真机局部截图，保留原始像素；不粘贴生产模板伪造正样本。 */
class GameUiCalibratorTest {
    private val reference = IntSize(3040, 1904)

    @Test fun `bright snow background still establishes world calibration`() {
        withDetector { detector ->
            val frame = Mat.zeros(1080,2400,CvType.CV_8UC1)
            try {
                paste(frame,"paimon-snow",85,0)
                val geometry = requireNotNull(detector.detect(frame))
                assertEquals(0.63,geometry.scale,0.015)
                assertEquals(90.0,geometry.leftInset,4.0)
            } finally { frame.release() }
        }
    }

    @Test fun `world hud measures scale and symmetric cutout inset`() {
        withDetector { detector ->
            val frame = Mat.zeros(1080, 2400, CvType.CV_8UC1)
            try {
                paste(frame, "paimon", 85, 0)
                val geometry = requireNotNull(detector.detect(frame))
                assertEquals(0.63, geometry.scale, 0.012)
                assertEquals(90.0, geometry.leftInset, 3.0)
                assertEquals(geometry.leftInset, geometry.rightInset, 0.0)
                val small = ReferenceSearch.mapReferenceRect(1920, 864, reference, IntRect(48,18,130,150),
                    uiGeometry = geometry)!!
                assertEquals(96.0, small.rect.x.toDouble(), 3.0)
                assertEquals(0.504, small.scale, 0.01)
                assertNull(geometry.mapping(1080,2400,reference,IntRect(1,1,10,10),SearchAnchorMode.Auto))
                assertEquals(geometry, detector.detect(frame, geometry))
            } finally { frame.release() }
        }
    }

    @Test fun `map measures left and right boundaries and template search uses same mapping`() {
        withDetector { detector ->
            val frame = Mat.zeros(1080, 2400, CvType.CV_8UC1)
            try {
                paste(frame, "map-settings", 100,955)
                paste(frame, "map-close", 2160,10)
                val geometry = requireNotNull(detector.detect(frame))
                assertEquals(0.63, geometry.scale, 0.012)
                assertEquals(90.0, geometry.leftInset, 3.0)
                assertEquals(90.0, geometry.rightInset, 3.0)
                val bbox = IntRect(2850,59,76,74)
                val mapped = ReferenceSearch.mapReferenceRect(2400,1080,reference,bbox,uiGeometry=geometry)!!
                assertEquals(2190.0,mapped.rect.x.toDouble(),3.0)
                assertEquals(37.0,mapped.rect.y.toDouble(),2.0)
                val search = ReferenceSearch.tryGetRegion(2400,1080,IntRect.EMPTY,reference,bbox,
                    SearchOptions(expandSize=IntSize(0,0)),true,RecognitionTypes.TemplateMatch,geometry)!!
                assertEquals(mapped.rect, search.effectiveRoi)
                assertEquals(mapped.scale,search.scale,0.0)
                // 手机旋转或其他参考画布不能套用这次标定。
                val pc = ReferenceSearch.mapReferenceRect(2400,1080,IntSize(1920,1080),IntRect(62,19,212,212),uiGeometry=geometry)!!
                assertEquals(62,pc.rect.x)
            } finally { frame.release() }
        }
    }

    @Test fun `blank and misplaced hud do not calibrate`() {
        withDetector { detector ->
            val frame = Mat.zeros(1080,2400,CvType.CV_8UC1)
            try {
                assertNull(detector.detect(frame))
                paste(frame,"paimon",85,110)
                assertNull(detector.detect(frame))
            } finally { frame.release() }
        }
    }

    @Test fun `map refines asymmetric right inset after world calibration`() {
        withDetector { detector ->
            val frame = Mat.zeros(1080,2400,CvType.CV_8UC1)
            try {
                paste(frame,"paimon",85,0)
                val previous = requireNotNull(detector.detect(frame))
                frame.setTo(org.opencv.core.Scalar(0.0))
                paste(frame,"map-settings",100,955)
                paste(frame,"map-close",2130,10)
                val measured = requireNotNull(detector.detect(frame,previous))
                assertEquals(previous.leftInset,measured.leftInset,0.0)
                assertEquals(120.0,measured.rightInset,3.0)
                val center = measured.mapping(2400,1080,reference,IntRect(1520,952,1,1),SearchAnchorMode.Center)!!
                assertEquals(1185.0,center.rect.x.toDouble(),3.0)
            } finally { frame.release() }
        }
    }

    @Test fun `changing boundary offset recalibrates without changing resolution`() {
        withDetector { detector ->
            val frame = Mat.zeros(1080,2400,CvType.CV_8UC1)
            val moved = Mat.zeros(1080,2400,CvType.CV_8UC1)
            try {
                paste(frame,"paimon",85,0)
                val previous = requireNotNull(detector.detect(frame))
                val source = frame.submat(Rect(90,0,2310,1080))
                val target = moved.submat(Rect(0,0,2310,1080))
                try { source.copyTo(target) } finally { source.release();target.release() }
                val measured = requireNotNull(detector.detect(moved,previous))
                assertEquals(previous.scale,measured.scale,0.012)
                assertEquals(0.0,measured.leftInset,3.0)
                assertEquals(0.0,measured.rightInset,3.0)
            } finally { frame.release();moved.release() }
        }
    }

    private fun paste(frame: Mat, name: String, x: Int, y: Int) {
        val sample = Imgcodecs.imread(File("src/test/resources/recognition/cutout/$name.png").absolutePath,0)
        assertFalse(sample.empty())
        val roi = frame.submat(Rect(x,y,sample.cols(),sample.rows()))
        try { sample.copyTo(roi) } finally { roi.release();sample.release() }
    }

    private fun withDetector(block: (GameUiCalibrator)->Unit) {
        val specs = listOf(
            Triple("main_paimon",IntRect(48,18,130,150),SearchAnchorMode.TopLeft),
            Triple("map_settings",IntRect(73,1754,66,64),SearchAnchorMode.BottomLeft),
            Triple("map_close",IntRect(2850,59,76,74),SearchAnchorMode.TopRight),
        )
        val templates = specs.map { Imgcodecs.imread(File("src/main/assets/recognition/Pathing/${it.first}.png").absolutePath,0) }
        try {
            templates.forEach { assertFalse(it.empty()) }
            val mask = HudTemplateMask.paimon(templates[0])
            try { block(GameUiCalibrator(specs.mapIndexed { i,s -> GameUiCalibrator.Anchor(templates[i],s.second,s.third,if(i==0) mask else null) })) }
            finally { mask.release() }
        } finally { templates.forEach { it.release() } }
    }

    companion object { @BeforeClass @JvmStatic fun init() = PathingTestData.ensureOpenCv() }
}

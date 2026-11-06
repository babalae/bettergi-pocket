package com.bettergi.pocket.pathing.vision

import com.bettergi.pocket.pathing.MapZoomLayout
import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.roundToInt

/**
 * 用 TB322FC 截图的固定裁图核对手机模板；样本随测试提交，模板在 assets 里。
 * 不依赖 artifacts 中的整屏截图，缺失或损坏的样本应让测试失败。
 */
class PhoneMapTemplateTest {
    @Test
    fun `region list is part of the map but an unrelated close button is not`() {
        val names = listOf("main_paimon.png", "map_close.png", "map_settings.png", "map_panel_close.png",
            "map_zoom_knob.png", "teleport_button.png", "icon_teleport_waypoint.png", "icon_domain.png", "map_region_title.png")
        val templates = names.map(::template)
        val frame = Mat(1904, 3040, CvType.CV_8UC1, Scalar(0.0))
        val close = sample("region-panel-close")
        val hud = PhoneHud(3040, 1904, templates[0], templates[1], templates[2], templates[3],
            templates[4], templates[5], templates[6], templates[7], PathingLayout(IntSize(3040, 1904), null),
            regionTitle = templates[8])
        try {
            val closeTarget = frame.submat(Rect(2500, 0, close.cols(), close.rows()))
            try { close.copyTo(closeTarget) } finally { closeTarget.release() }
            assertTrue(hud.closeButton(frame) != null)
            assertFalse(hud.isRegionPanelOpen(frame))
            assertFalse(hud.isInBigMap(frame))
            val titleTarget = frame.submat(Rect(1830, 35, templates[8].cols(), templates[8].rows()))
            try { templates[8].copyTo(titleTarget) } finally { titleTarget.release() }
            assertTrue(hud.isRegionPanelOpen(frame))
            assertTrue(hud.isInBigMap(frame))
            assertTrue(hud.isOpen(frame))
            assertFalse(hud.isWorldUi(frame))
        } finally {
            frame.release(); close.release(); templates.forEach { it.release() }
        }
    }

    @Test
    fun `paimon marks the main ui and not the map`() {
        val main = sample("main-paimon")
        val map = sample("map-paimon-negative")
        val paimon = template("main_paimon.png")
        try {
            val onMain = ScreenTemplate.findBest(main, paimon, IntRect(0, 0, 520, 420), Threshold.PAIMON)
            val onMap = ScreenTemplate.findBest(map, paimon, IntRect(0, 0, 520, 420), Threshold.PAIMON)
            assertTrue(onMain != null && onMain.score >= 0.90)
            assertNull(onMap)
        } finally {
            main.release(); map.release(); paimon.release()
        }
    }

    @Test
    fun `map chrome and zoom knob land on the measured centers`() {
        val mapSettings = sample("map-settings")
        val mapClose = sample("map-close")
        val zoomed = sample("zoom-knob")
        val settings = template("map_settings.png")
        val close = template("map_close.png")
        val knob = template("map_zoom_knob.png")
        try {
            val gear = ScreenTemplate.findBest(mapSettings, settings, null, Threshold.MAP_CHROME)
            val button = ScreenTemplate.findBest(mapClose, close, IntRect(200, 20, 330, 200), Threshold.MAP_CHROME)
            val knobHit = ScreenTemplate.findBest(zoomed, knob, null, Threshold.ZOOM_KNOB)
            assertTrue(gear != null && kotlin.math.abs(gear.centerX - 106) <= 8)
            assertTrue(button != null && kotlin.math.abs(button.centerX - 388) <= 8)
            assertTrue(button != null && kotlin.math.abs(button.centerY - 96) <= 8)
            assertTrue(knobHit != null && kotlin.math.abs(knobHit.centerY - 144) <= 8)
        } finally {
            listOf(mapSettings, mapClose, zoomed, settings, close, knob).forEach { it.release() }
        }
    }

    @Test
    fun `zoom level stays consistent after scaling and centered anchoring`() {
        val knob = template("map_zoom_knob.png")
        val reference = IntSize(3040, 1904)
        val layout = PathingLayout(reference, null, mapZoom = MapZoomLayout(94, 762, 1142))
        val hud = PhoneHud(
            referenceWidth = reference.width,
            referenceHeight = reference.height,
            paimon = knob, mapClose = knob, mapSettings = knob, panelClose = knob,
            zoomKnob = knob, teleportButton = knob, waypoint = knob, domain = knob,
            layout = layout,
        )
        val sizes = listOf(
            reference, IntSize(1520, 952), IntSize(6080, 3808),
            IntSize(2400, 1080), IntSize(1520, 1200),
        )
        try {
            for (size in sizes) {
                val scale = minOf(size.width / 3040.0, size.height / 1904.0)
                val offsetY = (size.height - 1904 * scale) / 2.0
                val scaledKnob = Mat()
                val frame = Mat(size.height, size.width, CvType.CV_8UC1, Scalar(0.0))
                try {
                    Imgproc.resize(
                        knob, scaledKnob, Size(knob.cols() * scale, knob.rows() * scale),
                        0.0, 0.0, if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC,
                    )
                    for ((referenceY, expected) in listOf(762 to 1.0, 857 to 2.25, 952 to 3.5, 1047 to 4.75, 1142 to 6.0)) {
                        frame.setTo(Scalar(0.0))
                        // 手工放置当前分辨率下的滑块，独立验证识别结果的反向坐标换算。
                        val x = (94 * scale).roundToInt() - scaledKnob.cols() / 2
                        val y = (referenceY * scale + offsetY).roundToInt() - scaledKnob.rows() / 2
                        val target = frame.submat(Rect(x, y, scaledKnob.cols(), scaledKnob.rows()))
                        try {
                            scaledKnob.copyTo(target)
                        } finally {
                            target.release()
                        }
                        val level = hud.zoomLevel(frame)
                        assertTrue("画面 $size 未识别到等级 $expected 的滑块", level != null)
                        assertEquals("画面 $size", expected, level!!, 0.03)
                    }
                } finally {
                    scaledKnob.release()
                    frame.release()
                }
            }
        } finally {
            knob.release()
        }
    }

    @Test
    fun `waypoint and teleport button stay above their negative samples`() {
        val pinsImage = sample("waypoints")
        val panel = sample("teleport-button")
        val map = sample("teleport-button-negative")
        val waypoint = template("icon_teleport_waypoint.png")
        val button = template("teleport_button.png")
        try {
            val pins = ScreenTemplate.findAll(pinsImage, waypoint, null, Threshold.WAYPOINT, 20)
            assertTrue("应识别五个不同的传送锚点，实际 ${pins.size}", pins.size == 5)
            val onPanel = ScreenTemplate.findBest(panel, button, null, Threshold.TELEPORT_BUTTON)
            val onMap = ScreenTemplate.findBest(map, button, null, Threshold.TELEPORT_BUTTON)
            assertTrue(onPanel != null && kotlin.math.abs(onPanel.centerX - 154) <= 12)
            assertNull(onMap)
        } finally {
            listOf(pinsImage, map, panel, waypoint, button).forEach { it.release() }
        }
    }

    private fun sample(name: String): Mat {
        val path = "/pathing/phone-hud/$name.png"
        val bytes = checkNotNull(javaClass.getResourceAsStream(path)) { "缺少样本 $path" }
            .use { it.readBytes() }
        val encoded = MatOfByte(*bytes)
        return try {
            Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE).also {
                assertTrue("无法读取样本 $name", !it.empty())
            }
        } finally {
            encoded.release()
        }
    }

    private fun template(name: String): Mat {
        val file = listOf(
            File("src/main/assets/recognition/Pathing/$name"),
            File("app/src/main/assets/recognition/Pathing/$name"),
        ).firstOrNull { it.isFile }
        assertTrue("缺少模板 $name", file != null)
        val mat = Imgcodecs.imread(file!!.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        assertTrue("无法读取模板 $name", !mat.empty())
        return mat
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = PathingTestData.ensureOpenCv()
    }
}

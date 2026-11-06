package com.bettergi.pocket.pathing.vision

import android.content.res.AssetManager
import com.bettergi.pocket.feature.autoskip.AutoSkipFeature
import com.bettergi.pocket.feature.autoskip.isDialogueScene
import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.pathing.tp.BigMapUi
import com.bettergi.pocket.pathing.tp.MapIcon
import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.area.ReferenceRectMapping
import com.bettergi.pocket.recognition.area.ReferenceSearch
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.min

/**
 * 用联想 TB322FC（3040×1904）截图裁出的模板识别主界面和大地图。
 * 阈值按这批截图的匹配分数留出间隔：正样本不低于 0.94，负样本不高于 0.72。
 */
class PhoneHud(
    private val referenceWidth: Int,
    private val referenceHeight: Int,
    private val paimon: Mat,
    private val mapClose: Mat,
    private val mapSettings: Mat,
    private val panelClose: Mat,
    private val zoomKnob: Mat,
    private val teleportButton: Mat,
    private val waypoint: Mat,
    private val domain: Mat,
    private val layout: PathingLayout,
    private val dialogue: DialogueReader? = null,
    private val regionTitle: Mat? = null,
) : PathingVision, BigMapUi {
    private var cachedScale = 1.0
    private val scaled = IdentityHashMap<Mat, Mat>()

    override fun motionStatus(frame: Mat): MotionStatus = MotionStatus.Unknown

    override fun isInMainUi(frame: Mat): Boolean = hit(frame, paimon, PAIMON_ROI, Threshold.PAIMON) != null

    override fun isInBigMap(frame: Mat): Boolean = mapChrome(frame) != null

    override fun closeButton(frame: Mat): Pair<Int, Int>? {
        val hit = hit(frame, mapClose, CLOSE_ROI, Threshold.MAP_CHROME)
            ?: hit(frame, panelClose, CLOSE_ROI, Threshold.MAP_CHROME)
            ?: return null
        return hit.centerX to hit.centerY
    }

    override fun inDialogue(frame: Mat): Boolean = dialogue?.inDialogue(frame) == true

    override fun lastDialogueOption(frame: Mat): Pair<Int, Int>? = dialogue?.lastOption(frame)

    override fun zhongliSkillVisible(frame: Mat): Boolean = false

    override fun gadgetCooldownSeconds(frame: Mat): Double? = null

    override fun isOpen(frame: Mat): Boolean = isInBigMap(frame)

    override fun isRegionPanelOpen(frame: Mat): Boolean = regionPanelClose(frame) != null

    override fun observedIcons(frame: Mat): List<MapIcon> = withGray(frame) { gray ->
        val icons = ArrayList<MapIcon>()
        for (hit in ScreenTemplate.findAll(gray, prepared(frame, waypoint), null, Threshold.WAYPOINT, ICON_LIMIT)) {
            icons += MapIcon(hit.centerX.toDouble(), hit.centerY.toDouble(), WAYPOINT_TYPES)
        }
        for (hit in ScreenTemplate.findAll(gray, prepared(frame, domain), null, Threshold.DOMAIN, ICON_LIMIT)) {
            icons += MapIcon(hit.centerX.toDouble(), hit.centerY.toDouble(), DOMAIN_TYPES)
        }
        icons
    }

    override fun teleportButton(frame: Mat): Pair<Int, Int>? {
        val hit = hit(frame, teleportButton, null, Threshold.TELEPORT_BUTTON) ?: return null
        return hit.centerX to hit.centerY
    }

    override fun zoomLevel(frame: Mat): Double? {
        val zoom = layout.mapZoom ?: return null
        val hit = hit(frame, zoomKnob, KNOB_ROI, Threshold.ZOOM_KNOB) ?: return null
        val mapping = referenceMapping(frame, KNOB_ROI) ?: return null
        // 与滑块搜索 ROI 共用锚点，把原生截图坐标还原到 layout.json 的参考画布。
        val referenceY = (hit.centerY - mapping.offsetY) / mapping.scale
        return zoom.level(referenceY)
    }

    override fun isWorldUi(frame: Mat): Boolean = isInMainUi(frame) && !isInBigMap(frame)

    private fun mapChrome(frame: Mat): TemplateHit? {
        return hit(frame, mapSettings, SETTINGS_ROI, Threshold.MAP_CHROME)
            ?: hit(frame, mapClose, CLOSE_ROI, Threshold.MAP_CHROME)
            ?: regionPanelClose(frame)
    }

    private fun regionPanelClose(frame: Mat): TemplateHit? {
        // 地区名单会隐藏普通地图按钮；同时核对“地图”标题，避免把其他页面的叉号当成大地图。
        val title = regionTitle ?: return null
        if (hit(frame, title, REGION_TITLE_ROI, Threshold.MAP_CHROME) == null) return null
        return hit(frame, panelClose, CLOSE_ROI, Threshold.MAP_CHROME)
    }

    private fun hit(frame: Mat, template: Mat, roi: IntRect?, threshold: Double): TemplateHit? {
        return withGray(frame) { gray ->
            ScreenTemplate.findBest(gray, prepared(frame, template), mapRoi(frame, roi), threshold)
        }
    }

    private fun <T> withGray(frame: Mat, block: (Mat) -> T): T {
        if (frame.empty() || frame.channels() < 3) return block(frame)
        val gray = Mat()
        return try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
            block(gray)
        } finally {
            gray.release()
        }
    }

    private fun prepared(frame: Mat, template: Mat): Mat {
        val scale = min(
            frame.cols() / referenceWidth.toDouble(),
            frame.rows() / referenceHeight.toDouble(),
        )
        if (scale <= 0.0 || abs(scale - 1.0) < 0.02) return template
        if (abs(scale - cachedScale) > 0.01) {
            scaled.values.forEach { it.release() }
            scaled.clear()
            cachedScale = scale
        }
        return scaled.getOrPut(template) {
            val dst = Mat()
            val interpolation = if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
            Imgproc.resize(
                template,
                dst,
                Size(template.cols() * scale, template.rows() * scale),
                0.0,
                0.0,
                interpolation,
            )
            dst
        }
    }

    private fun mapRoi(frame: Mat, roi: IntRect?): IntRect? {
        if (roi == null) return null
        val mapped = referenceMapping(frame, roi) ?: return roi
        return mapped.rect.clampTo(frame.cols(), frame.rows())
    }

    private fun referenceMapping(frame: Mat, roi: IntRect): ReferenceRectMapping? =
        ReferenceSearch.mapReferenceRect(
            srcWidth = frame.cols(),
            srcHeight = frame.rows(),
            referenceImageSize = IntSize(referenceWidth, referenceHeight),
            referenceRect = roi,
        )

    companion object {
        private const val DIR = "recognition/Pathing"
        private const val ICON_LIMIT = 48

        private val PAIMON_ROI = IntRect(0, 0, 520, 420)
        private val CLOSE_ROI = IntRect(2500, 0, 540, 280)
        private val SETTINGS_ROI = IntRect(0, 1550, 320, 354)
        private val KNOB_ROI = IntRect(30, 680, 160, 560)
        private val REGION_TITLE_ROI = IntRect(1760, 0, 260, 160)

        val WAYPOINT_TYPES: Set<String> = setOf("TeleportWaypoint")
        val DOMAIN_TYPES: Set<String> = setOf(
            "Domain",
            "OneTimeDomain",
            "BlessDomain",
            "ForgeryDomain",
            "MasteryDomain",
            "TrounceDomain",
        )

        fun load(assets: AssetManager, layout: PathingLayout, dialogue: RecognitionAssets? = null): PhoneHud {
            val size = layout.referenceSize ?: IntSize(3040, 1904)
            fun gray(name: String): Mat = decodeGray(assets, "$DIR/$name")
            return PhoneHud(
                referenceWidth = size.width,
                referenceHeight = size.height,
                paimon = gray("main_paimon.png"),
                mapClose = gray("map_close.png"),
                mapSettings = gray("map_settings.png"),
                panelClose = gray("map_panel_close.png"),
                zoomKnob = gray("map_zoom_knob.png"),
                teleportButton = gray("teleport_button.png"),
                waypoint = gray("icon_teleport_waypoint.png"),
                domain = gray("icon_domain.png"),
                layout = layout,
                dialogue = dialogue?.let(::DialogueReader),
                regionTitle = gray("map_region_title.png"),
            )
        }

        private fun decodeGray(assets: AssetManager, path: String): Mat {
            val bytes = assets.open(path).use { it.readBytes() }
            val encoded = Mat(1, bytes.size, CvType.CV_8UC1)
            encoded.put(0, 0, bytes)
            val color = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
            encoded.release()
            if (color.empty()) throw IllegalArgumentException("无法解码 $path")
            val gray = Mat()
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
            color.release()
            return gray
        }
    }
}

/** 真机模板分数。正样本来自 TB322FC 截图，阈值放在正负样本之间。 */
object Threshold {
    const val PAIMON = 0.80
    const val MAP_CHROME = 0.85
    const val WAYPOINT = 0.80
    const val DOMAIN = 0.90
    const val TELEPORT_BUTTON = 0.88
    const val ZOOM_KNOB = 0.75
}

/**
 * 剧情识别复用自动对话的模板。选项点最下面一个，和实时任务「点最上面」不同。
 */
class DialogueReader(
    private val assets: RecognitionAssets,
) {
    fun inDialogue(frame: Mat): Boolean = read(frame) { isDialogueScene(it, assets) }

    fun lastOption(frame: Mat): Pair<Int, Int>? = read(frame) { content ->
        if (!isDialogueScene(content, assets)) return@read null
        val icon = assets.get(AutoSkipFeature.TASK_NAME, "ChatIcon", content.captureRectArea)
        content.findMulti(icon).maxByOrNull { it.y }?.centerOnNativeCapture()
    }

    private fun <T> read(frame: Mat, block: (CaptureContent) -> T): T {
        val copy = frame.clone()
        val content = CaptureContent.fromBgr(copy, copy.cols(), copy.rows())
        return try {
            block(content)
        } finally {
            content.close()
        }
    }
}

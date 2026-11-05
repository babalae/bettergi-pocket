package com.bettergi.pocket.pathing.minimap

import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import com.bettergi.pocket.recognition.SearchAnchorMode
import com.bettergi.pocket.recognition.area.ReferenceSearch
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.round

/**
 * 小地图在参考画布上的位置。
 *
 * @param referenceSize 标定时的截图尺寸
 * @param bbox 小地图外接正方形。边长取「手机小地图圆直径 × 212 / PC 1080p 小地图圆直径」，
 *   中心取圆心，使二次缩放后与 BGI 的 212×212 ROI 覆盖相同画面范围
 * @param anchorMode 锚点规则，默认与 [ReferenceSearch] 一致按 bbox 中心自动判断（小地图在左上角，解析为左上）
 */
data class MiniMapLayout(
    val referenceSize: IntSize,
    val bbox: IntRect,
    val anchorMode: SearchAnchorMode = SearchAnchorMode.Auto,
) {
    init {
        require(bbox.width > 0 && bbox.width == bbox.height) { "小地图 bbox 必须是正方形：$bbox" }
    }
}

/**
 * 小地图取图：按参考画布自动锚定求出原生画面中的 ROI，裁剪后二次缩放到 BGI 标准尺寸 212×212。
 *
 * BGI 的输入是 1080p 截图中的 `MapAssets.MimiMapRect1080P = (62, 19, 212, 212)`；
 * 手机分辨率多样，所以先用 [ReferenceSearch.mapReferenceRect] 的缩放与锚点规则定位，再统一缩放。
 * 输出供 [CameraOrientation] 与地图匹配共用。
 */
class MiniMapLocator(private val layout: MiniMapLayout) {
    /**
     * @return 原生画面中的正方形 ROI；超出画面或参数无效时返回 null
     */
    fun nativeRect(frameWidth: Int, frameHeight: Int): IntRect? {
        val mapping = ReferenceSearch.mapReferenceRect(
            srcWidth = frameWidth,
            srcHeight = frameHeight,
            referenceImageSize = layout.referenceSize,
            referenceRect = layout.bbox,
            anchorMode = layout.anchorMode,
        ) ?: return null
        // 左上角与 ReferenceSearch.transformReferenceRect 同样取整；边长按缩放比例单独取整，保证正方形
        val x = round(mapping.offsetX + layout.bbox.x * mapping.scale).toInt()
        val y = round(mapping.offsetY + layout.bbox.y * mapping.scale).toInt()
        val side = max(1, round(layout.bbox.width * mapping.scale).toInt())
        if (x < 0 || y < 0 || x + side > frameWidth || y + side > frameHeight) {
            return null
        }
        return IntRect(x, y, side, side)
    }

    /**
     * @param frameBgr 原生分辨率的 BGR 整帧
     * @return 212×212 BGR 小地图（调用方释放）；ROI 无效时返回 null
     */
    fun extract(frameBgr: Mat): Mat? {
        val rect = nativeRect(frameBgr.cols(), frameBgr.rows()) ?: return null
        val roi = frameBgr.submat(Rect(rect.x, rect.y, rect.width, rect.height))
        try {
            val out = Mat()
            when {
                rect.width == STANDARD_SIZE -> roi.copyTo(out)
                rect.width > STANDARD_SIZE -> Imgproc.resize(roi, out, STANDARD_CV_SIZE, 0.0, 0.0, Imgproc.INTER_AREA)
                else -> Imgproc.resize(roi, out, STANDARD_CV_SIZE, 0.0, 0.0, Imgproc.INTER_CUBIC)
            }
            return out
        } finally {
            roi.release()
        }
    }

    companion object {
        /** BGI 1080p 小地图 ROI 边长（`MapAssets.MimiMapRect1080P`）。 */
        const val STANDARD_SIZE = 212

        private val STANDARD_CV_SIZE = Size(STANDARD_SIZE.toDouble(), STANDARD_SIZE.toDouble())

        /** BGI PC 1080p 的小地图位置，用于对拍测试，不适用于手机。 */
        val PC_1080P_LAYOUT = MiniMapLayout(
            referenceSize = IntSize(1920, 1080),
            bbox = IntRect(62, 19, STANDARD_SIZE, STANDARD_SIZE),
            anchorMode = SearchAnchorMode.TopLeft,
        )
    }
}

package com.bettergi.pocket.recognition.area

import com.bettergi.pocket.pathing.vision.HudTemplateMask
import com.bettergi.pocket.pathing.vision.ScreenTemplate
import com.bettergi.pocket.pathing.vision.TemplateHit
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.SearchAnchorMode
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** 从实际 HUD 同时测量缩放和安全边距，不能用系统 cutout 直接裁掉游戏画面。 */
class GameUiCalibrator(private val anchors: List<Anchor>) {
    data class Anchor(val template: Mat, val bounds: IntRect, val mode: SearchAnchorMode, val mask: Mat? = null)
    private data class Observation(val anchor: Anchor, val hit: TemplateHit, val scale: Double)

    fun detect(frame: Mat, previous: GameUiGeometry? = null): GameUiGeometry? {
        if (frame.empty() || frame.cols() <= frame.rows()) return null
        val gray = Mat()
        try {
            if (frame.channels() == 1) frame.copyTo(gray) else Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
            val base = min(frame.cols() / REFERENCE.width.toDouble(), frame.rows() / REFERENCE.height.toDouble())
            val compatible = previous?.takeIf { it.frameSize == IntSize(frame.cols(), frame.rows()) }
            // 已有标定只做单尺度复核；切换页面、对话和地图不会让旧锚点失效。
            if (compatible != null) {
                val observations = anchors.mapNotNull { observe(gray, it, compatible.scale) }
                if (observations.any { o ->
                    val mapped = compatible.mapping(frame.cols(), frame.rows(), REFERENCE, o.anchor.bounds, o.anchor.mode)!!
                    abs(o.hit.x - mapped.rect.x) <= 4 && abs(o.hit.y - mapped.rect.y) <= 4
                }) {
                    // 主界面只能看到左锚点；进入地图后补测右边，支持非对称安全区。
                    val right = observations.firstOrNull { it.anchor.mode == SearchAnchorMode.TopRight }
                    if (right != null && abs(right.hit.y - right.anchor.bounds.y * compatible.scale) <= 6) {
                        val measured = frame.cols() - right.hit.centerX -
                            (REFERENCE.width - right.anchor.bounds.x - right.anchor.bounds.width / 2.0) * compatible.scale
                        if (measured in 0.0..frame.cols() * 0.15 && abs(measured - compatible.rightInset) > 3) {
                            return compatible.copy(rightInset = measured)
                        }
                    }
                    return compatible
                }
            }

            for (anchor in anchors.filter { it.mode != SearchAnchorMode.TopRight }) {
                var best: Observation? = null
                fun probe(scale: Double) {
                    val observation = observe(gray, anchor, scale) ?: return
                    if (best == null || observation.hit.score > best!!.hit.score) best = observation
                }
                for (i in 0..32) probe(base * (0.75 + i * 0.025))
                val coarse = best ?: continue
                for (i in -6..6) probe(coarse.scale + base * i * 0.003)
                val observation = best ?: continue
                val s = observation.scale
                val hit = observation.hit
                // 上下贴边验证排除内容区相似图案；横向边距独立于缩放。
                val bottom = anchor.mode == SearchAnchorMode.BottomLeft
                val expectedY = if (bottom) frame.rows() - (REFERENCE.height - anchor.bounds.y) * s else anchor.bounds.y * s
                if (abs(hit.y - expectedY) > 6) continue
                val inset = hit.centerX - (anchor.bounds.x + anchor.bounds.width / 2.0) * s
                if (inset < -4 || inset > frame.cols() * 0.15) continue
                // 原神默认左右对称安全区；地图上同时观察右侧关闭按钮时分别测量两边。
                var rightInset = inset.coerceAtLeast(0.0)
                anchors.firstOrNull { it.mode == SearchAnchorMode.TopRight }?.let { right ->
                    observe(gray, right, s)?.let { r ->
                        val measured = frame.cols() - r.hit.centerX -
                            (REFERENCE.width - right.bounds.x - right.bounds.width / 2.0) * s
                        if (abs(r.hit.y - right.bounds.y * s) <= 6 && measured in 0.0..frame.cols() * 0.15) {
                            rightInset = measured
                        }
                    }
                }
                return GameUiGeometry(IntSize(frame.cols(), frame.rows()), REFERENCE, s,
                    inset.coerceAtLeast(0.0), rightInset)
            }
            return null
        } finally { gray.release() }
    }

    private fun observe(gray: Mat, anchor: Anchor, scale: Double): Observation? {
        val resized = Mat()
        val resizedMask = anchor.mask?.let { Mat() }
        try {
            val width = (anchor.template.cols() * scale).roundToInt().coerceAtLeast(1)
            val height = (anchor.template.rows() * scale).roundToInt().coerceAtLeast(1)
            Imgproc.resize(anchor.template, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0,
                if (scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
            if (resizedMask != null) Imgproc.resize(anchor.mask, resizedMask, resized.size(),0.0,0.0,Imgproc.INTER_NEAREST)
            val w = (gray.cols() * 0.30).roundToInt()
            val h = (gray.rows() * 0.24).roundToInt()
            val x = if (anchor.mode == SearchAnchorMode.TopRight) gray.cols() - w else 0
            val y = if (anchor.mode == SearchAnchorMode.BottomLeft) gray.rows() - h else 0
            val hit = ScreenTemplate.findBest(gray, resized, IntRect(x, y, w, h), MIN_SCORE, resizedMask) ?: return null
            return Observation(anchor, hit, scale)
        } finally { resized.release();resizedMask?.release() }
    }

    companion object {
        val REFERENCE = IntSize(3040, 1904)
        private const val MIN_SCORE = 0.90

        fun load(assets: RecognitionAssets): GameUiCalibrator {
            fun anchor(task: String, name: String, mode: SearchAnchorMode): Anchor {
                val ro = assets.get(task, name, REFERENCE.width, REFERENCE.height)
                return Anchor(requireNotNull(ro.templateImageGreyMat), requireNotNull(ro.referenceBoundingBox), mode,
                    if (name == "MainPaimon") HudTemplateMask.paimon(requireNotNull(ro.templateImageGreyMat)) else null)
            }
            return GameUiCalibrator(listOf(
                anchor("Pathing", "MainPaimon", SearchAnchorMode.TopLeft),
                anchor("Pathing", "MapSettings", SearchAnchorMode.BottomLeft),
                anchor("AutoSkip", "TalkHistory", SearchAnchorMode.TopLeft),
                anchor("Pathing", "MapClose", SearchAnchorMode.TopRight),
            ))
        }
    }
}

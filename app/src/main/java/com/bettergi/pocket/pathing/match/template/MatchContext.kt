package com.bettergi.pocket.pathing.match.template

import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.EXACT_SIZE
import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.ROUGH_SIZE
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 一次小地图匹配的输入准备：粗匹配用 52×52 彩图，精匹配用 260×260 灰度图。
 * 来源：BGI `Core/Recognition/OpenCv/TemplateMatch/MatchContext.cs`。
 *
 * BGI 中的 NormalizerRoughChan / TplSumSqChan 只给未启用的 RoughMatchLocalChan 使用，这里不移植。
 *
 * @param miniMap 156×156 BGR（MaskCalculator.Process2 的输出）
 * @param mask 156×156 8UC1，255 为有效像素
 */
class MatchContext(miniMap: Mat, mask: Mat) : AutoCloseable {
    val maskedMiniMapRoughs: List<Mat>
    val maskRoughF: Mat
    val miniMapExact = Mat()
    val maskExact = Mat()
    val normalizerRough: TemplateMatchNormalizer
    val normalizerExact: TemplateMatchNormalizer
    val tplSumSq: Double

    init {
        // 粗匹配
        val miniMapRough = Mat()
        val maskRough = Mat()
        try {
            Imgproc.resize(miniMap, miniMapRough, Size(ROUGH_SIZE.toDouble(), ROUGH_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(mask, maskRough, Size(ROUGH_SIZE.toDouble(), ROUGH_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            val (masked, maskF) = FastSqDiffMatcher.preProcess(miniMapRough, maskRough)
            maskedMiniMapRoughs = masked
            maskRoughF = maskF
            tplSumSq = FastSqDiffMatcher.getTplSumSq(masked)
            normalizerRough = TemplateMatchNormalizer.forTemplates(masked, maskRough)
        } finally {
            miniMapRough.release()
            maskRough.release()
        }

        // 精确匹配
        val miniMapGray = Mat()
        try {
            Imgproc.cvtColor(miniMap, miniMapGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.resize(miniMapGray, miniMapExact, Size(EXACT_SIZE.toDouble(), EXACT_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
            Imgproc.resize(mask, maskExact, Size(EXACT_SIZE.toDouble(), EXACT_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            normalizerExact = TemplateMatchNormalizer.forTemplate(miniMapExact, maskExact)
        } finally {
            miniMapGray.release()
        }
    }

    override fun close() {
        maskedMiniMapRoughs.forEach { it.release() }
        maskRoughF.release()
        miniMapExact.release()
        maskExact.release()
    }
}

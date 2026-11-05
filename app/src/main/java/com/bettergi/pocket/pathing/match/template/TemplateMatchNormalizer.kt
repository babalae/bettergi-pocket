package com.bettergi.pocket.pathing.match.template

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * 模板匹配结果归一化：记录最优值，换算成 [0, 1] 置信度。
 * 来源：BGI `Core/Recognition/OpenCv/TemplateMatch/TemplateMatchNormalizer.cs`。
 *
 * [mode] 使用 OpenCV 常量 `Imgproc.TM_*`。
 */
class TemplateMatchNormalizer private constructor(
    val mode: Int,
    private val bestMatchValue: Double,
    private val worstMatchValue: Double,
) {
    val sign: Int = if (mode == Imgproc.TM_SQDIFF || mode == Imgproc.TM_SQDIFF_NORMED) -1 else 1

    var value: Double = 0.0

    init {
        reset()
    }

    /**
     * 只接受比当前更优、且落在 [best, worst] 范围内的值。
     * 与 BGI 一致：只拦截正负无穷，NaN 会被接受。
     */
    fun update(value: Double): Boolean {
        if (value == Double.POSITIVE_INFINITY || value == Double.NEGATIVE_INFINITY) return false
        if (value > max(bestMatchValue, worstMatchValue) || value < min(bestMatchValue, worstMatchValue)) return false
        if (sign * (this.value - value) >= 0) return false
        this.value = value
        return true
    }

    fun reset() {
        value = if (sign > 0) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    }

    fun confidence(): Double {
        return if (bestMatchValue == worstMatchValue) {
            0.0
        } else {
            (value - worstMatchValue) / (bestMatchValue - worstMatchValue)
        }
    }

    companion object {
        /** BGI `TemplateMatchNormalizer(Mat template, Mat? mask, mode)`。 */
        fun forTemplate(template: Mat, mask: Mat? = null, mode: Int = Imgproc.TM_SQDIFF): TemplateMatchNormalizer {
            val (best, worst) = when (mode) {
                Imgproc.TM_SQDIFF -> sqDiffMatchValue(template, mask)
                Imgproc.TM_SQDIFF_NORMED -> 0.0 to 1.0
                Imgproc.TM_CCORR -> cCorrMatchValue(template, mask)
                Imgproc.TM_CCORR_NORMED -> 1.0 to 0.0
                Imgproc.TM_CCOEFF -> cCoeffMatchValue(template, mask)
                Imgproc.TM_CCOEFF_NORMED -> 1.0 to -1.0
                else -> throw IllegalArgumentException("未知的模板匹配模式: $mode")
            }
            return TemplateMatchNormalizer(mode, best, worst)
        }

        /** BGI `TemplateMatchNormalizer(Mat[] templates, Mat? mask, mode, channels)`：多通道各自求值后相加。 */
        fun forTemplates(
            templates: List<Mat>,
            mask: Mat? = null,
            mode: Int = Imgproc.TM_SQDIFF,
            channels: IntArray? = null,
        ): TemplateMatchNormalizer {
            val (best, worst) = when (mode) {
                Imgproc.TM_SQDIFF -> sumMatchValue(templates, mask, ::sqDiffMatchValue, channels)
                Imgproc.TM_SQDIFF_NORMED -> 0.0 to 1.0
                Imgproc.TM_CCORR -> sumMatchValue(templates, mask, ::cCorrMatchValue, channels)
                Imgproc.TM_CCORR_NORMED -> 1.0 to 0.0
                Imgproc.TM_CCOEFF -> sumMatchValue(templates, mask, ::cCoeffMatchValue, channels)
                Imgproc.TM_CCOEFF_NORMED -> 1.0 to -1.0
                else -> throw IllegalArgumentException("未知的模板匹配模式: $mode")
            }
            return TemplateMatchNormalizer(mode, best, worst)
        }

        fun of(bestMatchValue: Double, worstMatchValue: Double, mode: Int = Imgproc.TM_SQDIFF): TemplateMatchNormalizer {
            return TemplateMatchNormalizer(mode, bestMatchValue, worstMatchValue)
        }

        fun sumMatchValue(
            templates: List<Mat>,
            mask: Mat?,
            getValue: (Mat, Mat?) -> Pair<Double, Double>,
            channels: IntArray? = null,
        ): Pair<Double, Double> {
            var best = 0.0
            var worst = 0.0
            for (template in FastSqDiffMatcher.selectByIndex(templates, channels)) {
                val (b, w) = getValue(template, mask)
                best += b
                worst += w
            }
            return best to worst
        }

        /** 最差值：每个像素取 max(t, 255 - t) 后的 L2 平方和（8 位单通道模板）。 */
        fun sqDiffMatchValue(template: Mat, mask: Mat? = null): Pair<Double, Double> {
            val inverted = Mat()
            try {
                // C# Cv2.Subtract(255, template) 在 8 位上等于按位取反
                Core.bitwise_not(template, inverted)
                Core.max(template, inverted, inverted)
                val worst = if (mask != null) {
                    Core.norm(inverted, Core.NORM_L2SQR, mask)
                } else {
                    Core.norm(inverted, Core.NORM_L2SQR)
                }
                return 0.0 to worst
            } finally {
                inverted.release()
            }
        }

        fun cCorrMatchValue(template: Mat, mask: Mat? = null): Pair<Double, Double> {
            val best = if (mask != null) {
                Core.norm(template, Core.NORM_L2SQR, mask)
            } else {
                Core.norm(template, Core.NORM_L2SQR)
            }
            return best to 0.0
        }

        /**
         * BGI 用 `result.At<double>(0, 0)` 读取 CV_32F 结果，会按 8 字节错读。
         * 该分支在地图追踪中没有用到，这里按正确的 float 读取。
         */
        fun cCoeffMatchValue(template: Mat, mask: Mat? = null): Pair<Double, Double> {
            val result = Mat()
            try {
                if (mask != null) {
                    Imgproc.matchTemplate(template, template, result, Imgproc.TM_CCOEFF, mask)
                } else {
                    Imgproc.matchTemplate(template, template, result, Imgproc.TM_CCOEFF)
                }
                val best = result.get(0, 0)[0]
                return best to -best
            } finally {
                result.release()
            }
        }
    }
}

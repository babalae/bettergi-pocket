package com.bettergi.pocket.pathing.input

import com.bettergi.pocket.pathing.common.csRoundToInt
import com.bettergi.pocket.recognition.IntRect
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * 视角闭环的纯计算。来源：BGI `CameraRotateTask.RotateToApproach`。
 *
 * BGI 输出鼠标横移 `-ratio·diff·dpi`。手机输出视角区内的横向滑动 `-ratio·diff·c`，
 * `c` 是每度对应的滑动像素（`q_pc / q_m`）。增益表与 BGI 相同。
 */
object CameraControl {
    /** 闭环里单次滑动的时长。标定滑动另用 [CALIBRATION_SWIPE_MS]。 */
    const val SWIPE_MS = 80L

    /** 灵敏度标定的单次滑动时长，末段再停留，避免惯性。 */
    const val CALIBRATION_SWIPE_MS = 300L
    const val CALIBRATION_SETTLE_MS = 300L
    const val CALIBRATION_HOLD_MS = 100L

    /**
     * PC 上每鼠标像素对应的视角度数。需要在默认镜头灵敏度下实测一次后填入。
     * 未测量前为 null，不使用猜测值。
     */
    val Q_PC_DEGREES_PER_PIXEL: Double? = null

    /** `(current - target + 180) % 360 - 180`，小于 -180 时加 360。角度单位为度。 */
    fun angleDiff(current: Float, target: Float): Float {
        var diff = (current - target + 180f) % 360f - 180f
        if (diff < -180f) diff += 360f
        return diff
    }

    /** |diff| > 90 取 4，> 30 取 3，> 5 取 2，否则 1。 */
    fun controlRatio(diff: Float): Double {
        val magnitude = abs(diff)
        return when {
            magnitude > 90f -> 4.0
            magnitude > 30f -> 3.0
            magnitude > 5f -> 2.0
            else -> 1.0
        }
    }

    /** 本次应滑动的像素。0 差角不滑动。舍入与 C# `Math.Round` 一致。 */
    fun swipePixels(diff: Float, gainPixelsPerDegree: Double): Int {
        if (diff == 0f) return 0
        return csRoundToInt(-controlRatio(diff) * diff.toDouble() * gainPixelsPerDegree)
    }

    fun gain(qPcDegreesPerPixel: Double, qMDegreesPerPixel: Double): Double {
        return qPcDegreesPerPixel / qMDegreesPerPixel
    }

    /**
     * 把横向滑动限制在视角区内，并让整段都落在矩形里。
     * @return 起点、终点、实际滑动像素
     */
    fun clampSwipe(area: IntRect, dx: Int): SwipeSegment {
        if (area.width <= 1 || area.height <= 0 || dx == 0) {
            val x = area.centerX
            val y = area.centerY
            return SwipeSegment(x, y, x, y, 0)
        }
        val limit = area.width - 1
        val applied = dx.coerceIn(-limit, limit)
        val y = area.centerY
        var x1 = area.centerX
        var x2 = x1 + applied
        if (x2 < area.left) {
            val shift = area.left - x2
            x1 += shift
            x2 += shift
        } else if (x2 > area.right - 1) {
            val shift = x2 - (area.right - 1)
            x1 -= shift
            x2 -= shift
        }
        x1 = x1.coerceIn(area.left, area.right - 1)
        x2 = x2.coerceIn(area.left, area.right - 1)
        return SwipeSegment(x1, y, x2, y, x2 - x1)
    }

    /** 过原点最小二乘：`q = Σ(d·Δθ) / Σd²`。 */
    fun fitQm(samples: List<GainSample>): Double {
        var numerator = 0.0
        var denominator = 0.0
        for (sample in samples) {
            numerator += sample.pixels * sample.deltaDegrees
            denominator += sample.pixels * sample.pixels
        }
        if (denominator == 0.0) return 0.0
        return numerator / denominator
    }

    /**
     * 各点残差 < 2°，且左右斜率相差 < 10%。
     * 左右任一侧没有样本时不通过。
     */
    fun linearity(samples: List<GainSample>, qM: Double): Linearity {
        if (samples.isEmpty() || qM == 0.0) return Linearity(false, "样本不足")
        val worst = samples.maxOf { abs(it.deltaDegrees - qM * it.pixels) }
        if (worst >= 2.0) return Linearity(false, "残差 ${format(worst)}°")
        val left = slope(samples.filter { it.pixels < 0 })
        val right = slope(samples.filter { it.pixels > 0 })
        if (left == null || right == null || left == 0.0) return Linearity(false, "左右样本不足")
        val gap = abs(left - right) / min(abs(left), abs(right))
        if (gap >= 0.10) return Linearity(false, "左右斜率相差 ${format(gap * 100, 0)}%")
        return Linearity(true, null)
    }

    /** 把角度差折到 (-180, 180]。 */
    fun wrapDegrees(delta: Float): Float {
        var value = delta % 360f
        if (value <= -180f) value += 360f
        if (value > 180f) value -= 360f
        return value
    }

    fun circularMedian(values: List<Float>): Float {
        require(values.isNotEmpty())
        var best = values.first()
        var bestCost = Double.POSITIVE_INFINITY
        for (candidate in values) {
            val cost = values.sumOf { abs(wrapDegrees(it - candidate).toDouble()) }
            if (cost < bestCost) {
                bestCost = cost
                best = candidate
            }
        }
        return best
    }

    private fun format(value: Double, digits: Int = 2): String {
        return String.format(Locale.US, "%.${digits}f", value)
    }

    private fun slope(samples: List<GainSample>): Double? {
        if (samples.isEmpty()) return null
        return fitQm(samples)
    }
}

data class SwipeSegment(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val appliedPixels: Int,
)

data class GainSample(
    val pixels: Double,
    val deltaDegrees: Double,
)

data class Linearity(
    val ok: Boolean,
    val reason: String?,
)

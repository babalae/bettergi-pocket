package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.model.HandledException
import kotlin.math.abs
import kotlin.math.roundToInt

/** BGI TpTask 的缩放策略；Android 用双指开合代替鼠标滚轮，每次操作后重新读滑块。 */
object MapZoom {
    const val PRECISION = 0.15
    const val ZOOM_OUT_DISTANCE = 1000.0
    const val ZOOM_IN_DISTANCE = 400.0
    const val DISPLAY_TP_LEVEL = 4.4
    const val GESTURE_MS = 400L
    const val SETTLE_MS = 250L

    data class Pinch(val startSpan: Int, val endSpan: Int)

    fun pinchPlan(width: Int, height: Int, current: Double, target: Double): Pinch {
        // 等级越小地图越大：放大时两指分开，缩小时两指靠拢。
        // 单次倍率限制在 0.5–2，触点始终位于地图中央，避开两侧控件。
        val ratio = (current / target).coerceIn(0.5, 2.0)
        val start = (minOf(width, height) * 0.24).roundToInt().coerceAtLeast(2)
        return Pinch(start, (start * ratio).roundToInt().coerceAtLeast(1))
    }

    /** 来源：BGI GetTeleportFinalClickZoomLevel，邻近点越密集，点击前放得越大。 */
    fun finalClickLevel(neighborDistance: Double): Double = (neighborDistance / 36.0).coerceIn(1.0, DISPLAY_TP_LEVEL)

    /** 缩到最小时可能无法定位，先按 BGI 的 1 级步长向传送点可见比例恢复，再重新识别。 */
    fun locateCenter(
        startZoom: Double,
        readCenter: () -> Point2f?,
        adjust: (target: Double) -> Double,
    ): Pair<Point2f, Double>? {
        var zoom = startZoom
        readCenter()?.let { return it to zoom }
        repeat(3) {
            val target = when {
                abs(zoom - DISPLAY_TP_LEVEL) <= PRECISION -> zoom - 1.0
                zoom > DISPLAY_TP_LEVEL -> maxOf(DISPLAY_TP_LEVEL, zoom - 1.0)
                else -> minOf(DISPLAY_TP_LEVEL, zoom + 1.0)
            }.coerceIn(1.0, 6.0)
            zoom = adjust(target)
            readCenter()?.let { return it to zoom }
        }
        return null
    }

    /** 双指响应不假定固定等级变化；用真实读数闭环，连续两次没有接近目标就停止。 */
    fun adjust(start: Double, target: Double, apply: (current: Double, target: Double) -> Double): Double {
        val goal = target.coerceIn(1.0, 6.0)
        var current = start.coerceIn(1.0, 6.0)
        var noProgress = 0
        repeat(12) {
            if (abs(current - goal) <= PRECISION) return current
            val before = current
            current = apply(before, goal)
            if (!current.isFinite() || current !in 1.0..6.0) throw HandledException("大地图缩放等级读数无效")
            if (abs(current - goal) >= abs(before - goal) - PRECISION / 2) {
                noProgress++
                if (noProgress >= 2) return current
            } else noProgress = 0
        }
        return current
    }
}

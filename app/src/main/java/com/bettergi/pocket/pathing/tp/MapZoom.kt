package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.model.HandledException
import kotlin.math.abs
import kotlin.math.roundToInt

/** BGI TpTask 的缩放策略；Android 直接拖动缩放滑块代替鼠标滚轮，每次操作后重新读滑块。 */
object MapZoom {
    const val PRECISION = 0.15
    const val ZOOM_OUT_DISTANCE = 1000.0
    const val ZOOM_IN_DISTANCE = 400.0
    const val DISPLAY_TP_LEVEL = 4.4
    /** Android 真机上 4.4 时普通锚点仍会隐藏，统一放到 3.0 后保持比例拖图。 */
    const val TELEPORT_VIEW_LEVEL = 3.0
    const val GESTURE_MS = 400L
    const val SETTLE_MS = 250L
    const val GRIP_MS = 80L

    data class Pinch(val startSpan: Int, val endSpan: Int)

    /** 截图中识别到的滑块及轨道端点，均为同一锚定映射下的原生坐标。 */
    data class Slider(val level: Double, val x: Int, val y: Int, val zoomInY: Int, val zoomOutY: Int) {
        /** 直接到目标位置，小行程也保持单向拖动，避免先越过目标再返回的往返缩放。 */
        fun targetY(target: Double): Int =
            (zoomInY + (zoomOutY - zoomInY) * (target.coerceIn(1.0, 6.0) - 1.0) / 5.0).roundToInt()
    }

    fun pinchPlan(width: Int, height: Int, current: Double, target: Double): Pinch {
        // 等级越小地图越大：放大时两指分开，缩小时两指靠拢。
        // 单次倍率限制在 0.5–2，触点始终位于地图中央，避开两侧控件。
        val ratio = (current / target).coerceIn(0.5, 2.0)
        val start = (minOf(width, height) * 0.24).roundToInt().coerceAtLeast(2)
        return Pinch(start, (start * ratio).roundToInt().coerceAtLeast(1))
    }

    /** 来源：BGI GetTeleportFinalClickZoomLevel，邻近点越密集，点击前放得越大。 */
    fun finalClickLevel(neighborDistance: Double): Double = (neighborDistance / 36.0).coerceIn(1.0, DISPLAY_TP_LEVEL)

    /** 缩到最小时可能无法定位，先统一放到传送点可见比例再识别；已放得更大则保留当前比例。 */
    fun locateCenter(
        startZoom: Double,
        readCenter: () -> Point2f?,
        adjust: (target: Double) -> Double,
    ): Pair<Point2f, Double>? {
        val zoom = if (startZoom > TELEPORT_VIEW_LEVEL + PRECISION) adjust(TELEPORT_VIEW_LEVEL) else startZoom
        return readCenter()?.let { it to zoom }
    }

    /** 缩放响应不假定固定等级变化；用真实读数闭环，连续两次没有接近目标就停止。 */
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

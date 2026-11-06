package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.common.csRoundToInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 大地图拖动的步进和跳变判定。来源：BGI `TpTask.GenerateSteps`、`IsMapMoveRecognitionAnomaly`。
 */
object MapDrag {
    const val PIXELS_PER_STEP = 48.0
    const val FAST_STEP_RATIO = 0.42
    const val FAST_DISTANCE_RATIO = 0.85
    const val MAP_SCALE_FACTOR = 2.361
    const val TOLERANCE = 200.0
    const val MAX_ITERATIONS = 30
    const val STEP_MS = 20L
    const val RELEASE_HOLD_MS = 60L

    fun stepCount(length: Double): Int {
        if (length <= 0.0) return 3
        return ceil(length / PIXELS_PER_STEP).toInt().coerceIn(5, 60)
    }

    fun generateSteps(delta: Int, steps: Int): IntArray {
        if (steps <= 1) return intArrayOf(delta)
        val fastSteps = ceil(steps * FAST_STEP_RATIO).toInt().coerceIn(1, steps)
        val slowSteps = steps - fastSteps
        if (slowSteps == 0) return distribute(delta, steps) { 1.0 }
        val fastDelta = csRoundToInt(delta * FAST_DISTANCE_RATIO)
        val slowDelta = delta - fastDelta
        val fast = distribute(fastDelta, fastSteps) { 1.0 }
        val slow = distribute(slowDelta, slowSteps) { index ->
            val t = if (slowSteps <= 1) 1.0 else index.toDouble() / (slowSteps - 1)
            0.2 + 0.8 * cos(t * Math.PI / 2.0)
        }
        return fast + slow
    }

    fun directionCos(expectedX: Double, expectedY: Double, actualX: Double, actualY: Double): Double {
        val expectedLen = sqrt(expectedX * expectedX + expectedY * expectedY)
        val actualLen = sqrt(actualX * actualX + actualY * actualY)
        if (expectedLen <= 1.0 || actualLen <= 1.0) return 1.0
        return (expectedX * actualX + expectedY * actualY) / (expectedLen * actualLen)
    }

    fun isRecognitionAnomaly(
        expectedMoveLen: Double,
        actualMoveLen: Double,
        moveRatio: Double,
        moveDirectionCos: Double,
        jumpDistance: Double,
    ): Boolean {
        if (jumpDistance > max(200.0, expectedMoveLen * 2)) return true
        if (expectedMoveLen > 1200 && (moveRatio < 0.55 || moveRatio > 1.85)) return true
        return expectedMoveLen > 1200 && actualMoveLen > 120 && moveDirectionCos < 0.65
    }

    /** 缩放 1 级时，游戏坐标差换算成 1080p 拖动像素。 */
    fun mouseDelta(gameOffset: Double, zoom: Double): Double {
        return MAP_SCALE_FACTOR * abs(gameOffset) / zoom
    }

    /** 把目标相对地图中心的游戏坐标差换成拖动像素，符号指向目标。 */
    fun dragPixels(center: Point2f, targetX: Double, targetY: Double, zoom: Double): Pair<Int, Int> {
        val offsetX = targetX - center.x
        val offsetY = targetY - center.y
        val x = csRoundToInt(mouseDelta(offsetX, zoom)) * if (offsetX >= 0) 1 else -1
        val y = csRoundToInt(mouseDelta(offsetY, zoom)) * if (offsetY >= 0) 1 else -1
        return x to y
    }

    fun predictCenter(center: Point2f, sentX: Int, sentY: Int, zoom: Double): Point2f {
        return Point2f(
            center.x + (sentX * zoom / MAP_SCALE_FACTOR).toFloat(),
            center.y + (sentY * zoom / MAP_SCALE_FACTOR).toFloat(),
        )
    }

    /** 边缘留白，1080p 像素。来源：BGI `MouseMoveMap` 的 `edgePadding`。 */
    const val EDGE_PADDING = 115.0

    /** 大地图左上角 UI 遮挡区，1080p 像素。PC 值，手机待按截图重新量取。来源：BGI `MapUiForbiddenAreaWidth/Height`。 */
    const val FORBIDDEN_WIDTH = 360.0
    const val FORBIDDEN_HEIGHT = 430.0

    /**
     * 一次拖动的计划。[sentX]/[sentY] 为实际发送的 1080p 像素（可能因屏幕放不下而按比例缩短），
     * [startX]/[startY] 为原生屏幕上的起点。
     */
    data class DragPlan(val sentX: Int, val sentY: Int, val startX: Double, val startY: Double)

    /**
     * 来源：BGI `MouseMoveMap` 的起点和缩短规则：留白 115·k，整段放不下时按 0.85 逐次缩短，
     * 起点在屏幕中心减去半段位移附近抖动 ±W/24、±H/24，并且起终点都不进左上遮挡区。
     *
     * @param scale 原生 / 1080p 的比例 `k`
     * @param random 返回 [0, 1) 的随机数
     */
    fun planDrag(
        pixelDeltaX: Int,
        pixelDeltaY: Int,
        width: Double,
        height: Double,
        scale: Double,
        random: () -> Double,
    ): DragPlan {
        val expectedX = pixelDeltaX * scale
        val expectedY = pixelDeltaY * scale
        val edge = max(8.0 * scale, EDGE_PADDING * scale)
        val minX = edge
        val maxX = width - edge
        val minY = edge
        val maxY = height - edge
        val availableX = max(0.0, maxX - minX)
        val availableY = max(0.0, maxY - minY)
        val ratioX = if (expectedX == 0.0) 1.0 else availableX / abs(expectedX)
        val ratioY = if (expectedY == 0.0) 1.0 else availableY / abs(expectedY)
        var ratio = min(1.0, min(ratioX, ratioY))
        val forbiddenWidth = FORBIDDEN_WIDTH * scale
        val forbiddenHeight = FORBIDDEN_HEIGHT * scale
        val avoidPadding = max(4.0, 6.0 * scale)
        repeat(12) {
            val sentX = (pixelDeltaX * ratio).toInt()
            val sentY = (pixelDeltaY * ratio).toInt()
            val actualX = sentX * scale
            val actualY = sentY * scale
            val startMinX = max(minX, minX - actualX)
            val startMaxX = min(maxX, maxX - actualX)
            val startMinY = max(minY, minY - actualY)
            val startMaxY = min(maxY, maxY - actualY)
            if (startMinX <= startMaxX && startMinY <= startMaxY) {
                val preferredX = width / 2.0 - actualX / 2.0 + (random() - 0.5) * width / 12.0
                val preferredY = height / 2.0 - actualY / 2.0 + (random() - 0.5) * height / 12.0
                val start = pickSafeStart(
                    preferredX, preferredY,
                    startMinX, startMaxX, startMinY, startMaxY,
                    actualX, actualY,
                    minX, maxX, minY, maxY,
                    forbiddenWidth, forbiddenHeight, avoidPadding,
                )
                if (start != null) return DragPlan(sentX, sentY, start.first, start.second)
            }
            ratio *= 0.85
        }
        return DragPlan(0, 0, (width / 2.0).coerceIn(minX, max(minX, maxX)), (height / 2.0).coerceIn(minY, max(minY, maxY)))
    }

    /** 来源：BGI `TryPickSafeMapDragStart`。 */
    private fun pickSafeStart(
        preferredX: Double,
        preferredY: Double,
        startMinX: Double,
        startMaxX: Double,
        startMinY: Double,
        startMaxY: Double,
        deltaX: Double,
        deltaY: Double,
        safeMinX: Double,
        safeMaxX: Double,
        safeMinY: Double,
        safeMaxY: Double,
        forbiddenWidth: Double,
        forbiddenHeight: Double,
        avoidPadding: Double,
    ): Pair<Double, Double>? {
        fun safe(x: Double, y: Double): Boolean {
            return x in safeMinX..safeMaxX && y in safeMinY..safeMaxY &&
                !(x < forbiddenWidth && y < forbiddenHeight)
        }
        var best: Pair<Double, Double>? = null
        var bestDistance = Double.MAX_VALUE
        val safeForbiddenX = forbiddenWidth + avoidPadding
        val safeForbiddenY = forbiddenHeight + avoidPadding
        val xs = doubleArrayOf(preferredX, startMinX, startMaxX, safeForbiddenX, safeForbiddenX - deltaX)
        val ys = doubleArrayOf(preferredY, startMinY, startMaxY, safeForbiddenY, safeForbiddenY - deltaY)
        for (rawX in xs) {
            for (rawY in ys) {
                val x = rawX.coerceIn(startMinX, startMaxX)
                val y = rawY.coerceIn(startMinY, startMaxY)
                if (!safe(x, y) || !safe(x + deltaX, y + deltaY)) continue
                val distance = (x - preferredX) * (x - preferredX) + (y - preferredY) * (y - preferredY)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = x to y
                }
            }
        }
        return best
    }

    /**
     * 把地图中心拖向目标。识别跳变超过 5 次就放弃，其余时候用预测中心继续。
     * 来源：BGI `MoveMapToCore` 的迭代，缩放调整由 [adjustZoom] 单独做。
     * 提供缩放回调时，远距离先缩小，接近目标后放大；不提供时保留固定比例拖图。
     */
    fun moveToward(
        start: Point2f,
        targetX: Double,
        targetY: Double,
        zoom: Double,
        readCenter: (predicted: Point2f) -> Point2f?,
        drag: (dx: Int, dy: Int) -> Pair<Int, Int>,
        finalZoom: Double = 2.0,
        adjustZoom: ((target: Double) -> Double)? = null,
    ): Point2f {
        var center = start
        var currentZoom = zoom
        var failures = 0
        repeat(MAX_ITERATIONS) { iteration ->
            val distance = distance(
                mouseDelta(targetX - center.x, currentZoom),
                mouseDelta(targetY - center.y, currentZoom),
            )
            val goal = finalZoom.coerceIn(1.0, MapZoom.DISPLAY_TP_LEVEL)
            val targetZoom = when {
                iteration == 0 && distance > MapZoom.ZOOM_OUT_DISTANCE ->
                    (currentZoom * distance / MapZoom.ZOOM_OUT_DISTANCE).coerceIn(1.0, 6.0)
                distance < MapZoom.ZOOM_IN_DISTANCE && currentZoom > goal + MapZoom.PRECISION ->
                    maxOf(goal, currentZoom * distance / MapZoom.ZOOM_IN_DISTANCE)
                else -> currentZoom
            }
            if (adjustZoom != null && kotlin.math.abs(targetZoom - currentZoom) > MapZoom.PRECISION) {
                currentZoom = adjustZoom(targetZoom)
                // 双指中心与实际地图缩放中心可能有偏移，缩放后必须重新定位，不能沿用旧中心。
                center = readCenter(center)
                    ?: throw com.bettergi.pocket.pathing.model.HandledException("缩放后大地图中心没有识别出来")
                failures = 0
            }
            val offsetX = targetX - center.x
            val offsetY = targetY - center.y
            val mouseX = mouseDelta(offsetX, currentZoom)
            val mouseY = mouseDelta(offsetY, currentZoom)
            if (sqrt(mouseX * mouseX + mouseY * mouseY) < TOLERANCE) {
                // 缩放后中心可能偏移到目标附近，仍需达到点击比例后才能结束。
                if (adjustZoom != null && currentZoom > goal + MapZoom.PRECISION) return@repeat
                return center
            }
            val (dx, dy) = dragPixels(center, targetX, targetY, currentZoom)
            val (sentX, sentY) = drag(dx, dy)
            val predicted = predictCenter(center, sentX, sentY, currentZoom)
            val observed = readCenter(predicted)
            val expectedLen = sqrt((sentX * sentX + sentY * sentY).toDouble()) * currentZoom / MAP_SCALE_FACTOR
            if (observed == null || movedAnomaly(center, predicted, observed, expectedLen)) {
                failures++
                if (failures > 5) {
                    throw com.bettergi.pocket.pathing.model.HandledException("多次中心点识别失败或异常，惯性推算失效")
                }
                center = predicted
            } else {
                failures = 0
                center = observed
            }
        }
        return center
    }

    /**
     * 缩放调整：按每格缩放量估算步数，连续两次没有更接近就停。
     * 来源：BGI `AdjustMapZoomLevel`。手机上每格改成一次滑条拖动或捏合。
     */
    fun adjustZoom(
        start: Double,
        target: Double,
        perStep: Double,
        precision: Double = 0.05,
        apply: (steps: Int) -> Double,
    ): Double {
        val goal = target.coerceIn(1.0, 6.0)
        var current = start.coerceIn(1.0, 6.0)
        var stepSize = perStep.coerceAtLeast(precision)
        var noProgress = 0
        while (abs(current - goal) > max(precision, stepSize * 0.75)) {
            val direction = if (goal > current) 1 else -1
            val steps = csRoundToInt(abs(goal - current) / stepSize).coerceIn(1, 16)
            val before = current
            current = apply(direction * steps).coerceIn(1.0, 6.0)
            val delta = current - before
            if (steps != 0 && delta != 0.0) {
                stepSize = stepSize * 0.7 + abs(delta) / abs(steps) * 0.3
            }
            val stillFar = abs(delta) < precision / 2.0 ||
                abs(goal - current) >= abs(goal - before) - precision / 2.0
            if (stillFar) {
                noProgress++
                if (noProgress >= 2) break
            } else {
                noProgress = 0
            }
        }
        return current
    }

    private fun movedAnomaly(
        before: Point2f,
        predicted: Point2f,
        observed: Point2f,
        expectedLen: Double,
    ): Boolean {
        val jump = distance((observed.x - predicted.x).toDouble(), (observed.y - predicted.y).toDouble())
        val actualX = (observed.x - before.x).toDouble()
        val actualY = (observed.y - before.y).toDouble()
        val actualLen = distance(actualX, actualY)
        val ratio = if (expectedLen > 0) actualLen / expectedLen else 0.0
        val cosine = directionCos(
            (predicted.x - before.x).toDouble(),
            (predicted.y - before.y).toDouble(),
            actualX,
            actualY,
        )
        return isRecognitionAnomaly(expectedLen, actualLen, ratio, cosine, jump)
    }

    private fun distance(x: Double, y: Double): Double = sqrt(x * x + y * y)

    private fun distribute(delta: Int, steps: Int, weight: (Int) -> Double): IntArray {
        val result = IntArray(steps)
        if (steps == 0) return result
        val weights = DoubleArray(steps) { max(0.001, weight(it)) }
        val sum = weights.sum()
        var remaining = delta
        for (i in 0 until steps) {
            result[i] = (delta * weights[i] / sum).toInt()
            remaining -= result[i]
        }
        val sign = if (remaining > 0) 1 else -1
        repeat(abs(remaining)) { index ->
            result[index % steps] += sign
        }
        return result
    }
}

/** 滑块中心在 1080p 纵坐标上的缩放读数。来源：BGI `zoom = 6 - 5·(612-y)/(612-468)`。 */
fun zoomFromSliderY(sliderCenterY: Double): Double {
    return 6.0 - 5.0 * (612.0 - sliderCenterY) / (612.0 - 468.0)
}

/** 大地图点击：图像矩形内的点按宽高比例投到截图。 */
fun projectMapClick(
    picX: Double,
    picY: Double,
    rectX: Double,
    rectY: Double,
    rectWidth: Double,
    rectHeight: Double,
    captureWidth: Double,
    captureHeight: Double,
): Pair<Double, Double> {
    val x = (picX - rectX) / rectWidth * captureWidth
    val y = (picY - rectY) / rectHeight * captureHeight
    return x to y
}

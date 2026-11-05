package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.common.csRoundToInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
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

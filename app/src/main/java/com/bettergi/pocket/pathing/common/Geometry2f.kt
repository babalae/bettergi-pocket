package com.bettergi.pocket.pathing.common

import org.opencv.core.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 对应 OpenCvSharp `Point2f`：单精度坐标，`default` 为 (0, 0)。
 *
 * BGI 大量用 `p == default` 表示识别失败，这里用 [isDefault] 表达同样含义。
 * 相等性按 C# `float.Equals` 语义：0f 与 -0f 相等，NaN 与 NaN 相等。
 */
class Point2f(val x: Float, val y: Float) {
    val isDefault: Boolean get() = x == 0f && y == 0f

    operator fun plus(other: Point2f): Point2f = Point2f(x + other.x, y + other.y)

    /** OpenCvSharp `Point2f.DistanceTo`：`Math.Sqrt(Math.Pow(X - p.X, 2) + Math.Pow(Y - p.Y, 2))`。 */
    fun distanceTo(other: Point2f): Double {
        return sqrt((x - other.x).toDouble().pow(2) + (y - other.y).toDouble().pow(2))
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Point2f) return false
        return floatEquals(x, other.x) && floatEquals(y, other.y)
    }

    override fun hashCode(): Int = 31 * normalize(x).hashCode() + normalize(y).hashCode()

    override fun toString(): String = "($x, $y)"

    companion object {
        val DEFAULT = Point2f(0f, 0f)

        private fun floatEquals(a: Float, b: Float): Boolean = a == b || (a.isNaN() && b.isNaN())

        private fun normalize(v: Float): Float = if (v == 0f) 0f else v
    }
}

/** 对应 OpenCvSharp `Point`（整型）。 */
data class PointI(val x: Int, val y: Int) {
    companion object {
        fun of(p: org.opencv.core.Point): PointI = PointI(p.x.toInt(), p.y.toInt())
    }
}

/**
 * C# `Math.Round(double)` 默认是银行家舍入（MidpointRounding.ToEven），对应 Java `Math.rint`。
 * 移植 BGI 的 `Math.Round` 统一走这里；不要用 `java.lang.Math.round`（它是 0.5 向上取整）。
 */
fun csRound(value: Double): Double = Math.rint(value)

fun csRoundToInt(value: Double): Int = Math.rint(value).toInt()

fun csRoundToInt(value: Float): Int = Math.rint(value.toDouble()).toInt()

/** OpenCvSharp `Rect.Intersect`：无交集时返回空矩形。 */
fun Rect.intersectCs(other: Rect): Rect {
    val x1 = max(x, other.x)
    val x2 = min(x + width, other.x + other.width)
    val y1 = max(y, other.y)
    val y2 = min(y + height, other.y + other.height)
    return if (x2 >= x1 && y2 >= y1) Rect(x1, y1, x2 - x1, y2 - y1) else Rect(0, 0, 0, 0)
}

/** OpenCvSharp `rect == default`。 */
val Rect.isDefault: Boolean get() = x == 0 && y == 0 && width == 0 && height == 0

/**
 * BGI `CommonExtension.GetCenterPoint(this Rect)`：整数除法求中心，空矩形抛异常。
 */
fun Rect.centerPointCs(): PointI {
    require(!isDefault) { "rectangle is empty" }
    return PointI(x + width / 2, y + height / 2)
}

package com.bettergi.pocket.pathing.tp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

data class MapIcon(
    val x: Double,
    val y: Double,
    val types: Set<String>,
)

data class MapIconAlignment(
    val offsetX: Double,
    val offsetY: Double,
    val pairCount: Int,
    val meanError: Double,
) {
    fun apply(x: Double, y: Double): Pair<Double, Double> = x + offsetX to y + offsetY
}

/**
 * 大地图图标对齐。模板匹配负责给出观测点，这里只做平移假设、匈牙利分配和中位数精修。
 * 来源：BGI `TpTask.EstimateAbsoluteMapIconAlignment`。尺度默认按 1080p，`scale` 留给手机内容缩放。
 */
object IconAlignment {
    const val MAX_CORRECTION = 60.0
    const val BUCKET = 4.0
    const val MAX_HYPOTHESES = 64
    const val INLIER_RADIUS = 14.0
    const val COST_SCALE = 1000
    const val DUMMY_COST = 1_000_000
    const val INVALID_COST = 10_000_000
    const val BASE_UNCERTAINTY = 16.0
    const val NEIGHBOR_ERROR_RATIO = 0.25
    const val SAME_ICON_DISTANCE = 18.0

    fun align(expected: List<MapIcon>, observed: List<MapIcon>, scale: Double = 1.0): MapIconAlignment {
        val identity = MapIconAlignment(0.0, 0.0, 0, 0.0)
        val inlier = INLIER_RADIUS * scale
        val identityPairs = assign(expected, observed, 0.0, 0.0, inlier)
        if (expected.isEmpty() || observed.isEmpty()) return finish(0.0, 0.0, identityPairs)
        var bestOffset = 0.0 to 0.0
        var bestPairs = identityPairs
        for (hypothesis in hypotheses(expected, observed, scale)) {
            val pairs = assign(expected, observed, hypothesis.first, hypothesis.second, inlier)
            if (better(hypothesis, pairs, bestOffset, bestPairs)) {
                bestOffset = hypothesis
                bestPairs = pairs
            }
        }
        val correction = distance(0.0, 0.0, bestOffset.first, bestOffset.second)
        val onePoint = 8.0 * scale
        val twoPoint = 10.0 * scale
        if (bestPairs.isEmpty() ||
            (bestPairs.size == 1 && correction > onePoint) ||
            (bestPairs.size == 2 && correction > twoPoint)
        ) {
            return finish(0.0, 0.0, identityPairs)
        }
        val refined = medianOffset(bestPairs)
        val refinedPairs = assign(expected, observed, refined.first, refined.second, inlier)
        if (better(refined, refinedPairs, bestOffset, bestPairs)) {
            bestOffset = refined
            bestPairs = refinedPairs
        }
        return finish(bestOffset.first, bestOffset.second, bestPairs)
    }

    fun uncertainty(alignment: MapIconAlignment, scale: Double = 1.0): Double {
        return when (alignment.pairCount) {
            0 -> BASE_UNCERTAINTY * scale
            1 -> max(10.0 * scale, alignment.meanError + 6.0 * scale)
            2 -> max(7.0 * scale, alignment.meanError + 4.0 * scale)
            else -> max(4.0 * scale, alignment.meanError + 3.0 * scale)
        }
    }

    fun allowedUncertainty(neighborDistance: Double, scale: Double = 1.0): Double {
        if (!neighborDistance.isFinite() || neighborDistance <= 0.0) return Double.POSITIVE_INFINITY
        return max(8.0 * scale, neighborDistance * NEIGHBOR_ERROR_RATIO)
    }

    /** 18 像素内的同类图标合并成一个。 */
    fun mergeNearby(icons: List<MapIcon>): List<MapIcon> {
        val merged = ArrayList<MapIcon>()
        for (icon in icons) {
            val same = merged.firstOrNull {
                it.types.intersect(icon.types).isNotEmpty() &&
                    distance(it.x, it.y, icon.x, icon.y) <= SAME_ICON_DISTANCE
            }
            if (same == null) merged += icon
        }
        return merged
    }

    private data class Pairing(val expected: MapIcon, val observed: MapIcon, val error: Double)

    private fun hypotheses(expected: List<MapIcon>, observed: List<MapIcon>, scale: Double): List<Pair<Double, Double>> {
        val maxCorrection = MAX_CORRECTION * scale
        val bucket = max(1.0, BUCKET * scale)
        val buckets = LinkedHashMap<Pair<Int, Int>, ArrayList<Pair<Double, Double>>>()
        for (want in expected) {
            for (seen in observed) {
                if (want.types.intersect(seen.types).isEmpty()) continue
                val dx = seen.x - want.x
                val dy = seen.y - want.y
                if (abs(dx) > maxCorrection || abs(dy) > maxCorrection) continue
                val key = round(dx / bucket).toInt() to round(dy / bucket).toInt()
                buckets.getOrPut(key) { ArrayList() }.add(dx to dy)
            }
        }
        val ranked = buckets.values.sortedWith(
            compareByDescending<List<Pair<Double, Double>>> { it.size }
                .thenBy { offsets -> offsets.map { distance(0.0, 0.0, it.first, it.second) }.average() },
        ).take(MAX_HYPOTHESES)
        return listOf(0.0 to 0.0) + ranked.map { offsets ->
            offsets.map { it.first }.average() to offsets.map { it.second }.average()
        }
    }

    private fun assign(
        expected: List<MapIcon>,
        observed: List<MapIcon>,
        offsetX: Double,
        offsetY: Double,
        maxError: Double,
    ): List<Pairing> {
        if (expected.isEmpty() || observed.isEmpty()) return emptyList()
        val rows = expected.size
        val observedColumns = observed.size
        val columns = observedColumns + rows
        val costs = Array(rows) { IntArray(columns) }
        for (row in expected.indices) {
            val want = expected[row]
            val x = want.x + offsetX
            val y = want.y + offsetY
            for (column in observed.indices) {
                val seen = observed[column]
                costs[row][column] = if (want.types.intersect(seen.types).isEmpty()) {
                    INVALID_COST
                } else {
                    val error = distance(x, y, seen.x, seen.y)
                    if (error <= maxError) minOf(DUMMY_COST - 1, round(error * COST_SCALE).toInt()) else INVALID_COST
                }
            }
            for (column in observedColumns until columns) costs[row][column] = DUMMY_COST
        }
        val assignment = Hungarian.assign(costs)
        val pairs = ArrayList<Pairing>()
        for (row in expected.indices) {
            val column = assignment.getOrElse(row) { -1 }
            if (column !in observed.indices || costs[row][column] >= DUMMY_COST) continue
            val want = expected[row]
            val seen = observed[column]
            pairs += Pairing(want, seen, distance(want.x + offsetX, want.y + offsetY, seen.x, seen.y))
        }
        return pairs
    }

    private fun better(
        candidateOffset: Pair<Double, Double>,
        candidate: List<Pairing>,
        currentOffset: Pair<Double, Double>,
        current: List<Pairing>,
    ): Boolean {
        if (candidate.size != current.size) return candidate.size > current.size
        val candidateError = candidate.map { it.error }.average().takeUnless { candidate.isEmpty() } ?: Double.MAX_VALUE
        val currentError = current.map { it.error }.average().takeUnless { current.isEmpty() } ?: Double.MAX_VALUE
        if (abs(candidateError - currentError) > 0.25) return candidateError < currentError
        return distance(0.0, 0.0, candidateOffset.first, candidateOffset.second) <
            distance(0.0, 0.0, currentOffset.first, currentOffset.second)
    }

    private fun medianOffset(pairs: List<Pairing>): Pair<Double, Double> {
        return median(pairs.map { it.observed.x - it.expected.x }) to median(pairs.map { it.observed.y - it.expected.y })
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val ordered = values.sorted()
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 0) (ordered[middle - 1] + ordered[middle]) / 2.0 else ordered[middle]
    }

    private fun finish(offsetX: Double, offsetY: Double, pairs: List<Pairing>): MapIconAlignment {
        return MapIconAlignment(
            offsetX = offsetX,
            offsetY = offsetY,
            pairCount = pairs.size,
            meanError = if (pairs.isEmpty()) 0.0 else pairs.map { it.error }.average(),
        )
    }

    private fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}

package com.bettergi.pocket.pathing.match.feature

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.recognition.IntSize
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** BGI `DescriptorMatcherType`。 */
enum class DescriptorMatcherType(val cvType: Int) {
    BruteForce(DescriptorMatcher.BRUTEFORCE),
    FlannBased(DescriptorMatcher.FLANNBASED),
}

/**
 * SIFT 特征匹配。
 * 来源：BGI `Core/Recognition/OpenCv/FeatureMatch/Feature2DExtensions.cs`（Feature2D 的扩展方法）
 * 与 `Feature2DFactory.cs`（SIFT.Create() 默认参数）。
 *
 * 失败时返回 [Point2f.DEFAULT] / 空矩形，与 BGI 返回 `default` 一致。
 * [match] 在匹配点不足 4 个或单应矩阵为空时会抛出 OpenCV 异常，BGI 同样不处理，由调用方捕获。
 * 不是线程安全的，每个线程应持有自己的实例。
 */
class SiftMatcher {
    private val sift: SIFT = SIFT.create()
    private val matchers = HashMap<DescriptorMatcherType, DescriptorMatcher>()

    private fun matcher(type: DescriptorMatcherType): DescriptorMatcher {
        return matchers.getOrPut(type) { DescriptorMatcher.create(type.cvType) }
    }

    /**
     * 普通匹配：保留距离小于 max(2 × 最小距离, 0.02) 的匹配，RANSAC 求单应矩阵，返回查询图中心在训练图中的位置。
     */
    fun match(
        trainKeyPoints: KeyPointPoints,
        trainDescriptors: Mat,
        queryMat: Mat,
        queryMatMask: Mat? = null,
        matcherType: DescriptorMatcherType = DescriptorMatcherType.FlannBased,
    ): Point2f {
        val queryKeyPointsMat = MatOfKeyPoint()
        val queryDescriptors = Mat()
        val matchesMat = MatOfDMatch()
        val outMask = Mat()
        val mask = queryMatMask ?: Mat()
        try {
            sift.detectAndCompute(queryMat, mask, queryKeyPointsMat, queryDescriptors)
            val queryKeyPoints = queryKeyPointsMat.toArray()
            matcher(matcherType).match(queryDescriptors, trainDescriptors, matchesMat)
            val matches = matchesMat.toArray()

            var minDistance = 1000.0
            var maxDistance = 0.0
            for (i in 0 until queryDescriptors.rows()) {
                val distance = matches[i].distance.toDouble()
                if (distance > maxDistance) maxDistance = distance
                if (distance < minDistance) minDistance = distance
            }

            val pointsQuery = ArrayList<org.opencv.core.Point>()
            val pointsTrain = ArrayList<org.opencv.core.Point>()
            for (i in 0 until queryDescriptors.rows()) {
                val distance = matches[i].distance.toDouble()
                if (distance < max(minDistance * 2, 0.02)) {
                    pointsQuery.add(queryKeyPoints[matches[i].queryIdx].pt)
                    val t = matches[i].trainIdx
                    pointsTrain.add(org.opencv.core.Point(trainKeyPoints.xs[t].toDouble(), trainKeyPoints.ys[t].toDouble()))
                }
            }

            if (pointsQuery.isNotEmpty() && pointsTrain.isNotEmpty()) {
                val src = MatOfPoint2f(*pointsQuery.toTypedArray())
                val dst = MatOfPoint2f(*pointsTrain.toTypedArray())
                try {
                    val hMat = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3.0, outMask)
                    try {
                        val center = Point2f(queryMat.cols() / 2f, queryMat.rows() / 2f)
                        return perspectiveTransform(listOf(center), hMat)[0]
                    } finally {
                        hMat.release()
                    }
                } finally {
                    src.release()
                    dst.release()
                }
            }
            return Point2f.DEFAULT
        } finally {
            queryKeyPointsMat.release()
            queryDescriptors.release()
            matchesMat.release()
            outMask.release()
            if (queryMatMask == null) mask.release()
        }
    }

    /**
     * Knn 匹配：k = 2，比值 0.75，好匹配不少于 7 个，RANSAC 阈值 3.0，返回查询图中心在训练图中的位置。
     */
    fun knnMatch(
        trainKeyPoints: KeyPointPoints,
        trainDescriptors: Mat,
        queryMat: Mat,
        queryMatMask: Mat? = null,
        matcherType: DescriptorMatcherType = DescriptorMatcherType.FlannBased,
    ): Point2f {
        val h = knnHomography(trainKeyPoints, trainDescriptors, queryMat, queryMatMask, matcherType) ?: return Point2f.DEFAULT
        try {
            val center = Point2f(queryMat.cols() / 2f, queryMat.rows() / 2f)
            return perspectiveTransform(listOf(center), h)[0]
        } finally {
            h.release()
        }
    }

    /**
     * 在搜索矩形覆盖的网格（向外扩 [expandCells] 格）内做 Knn 匹配。任何异常都视为失败。
     */
    fun knnMatchLocal(
        splitBlocks: Array<Array<KeyPointFeatureBlock?>>,
        trainKeyPoints: KeyPointPoints,
        trainDescriptors: DescriptorMatrix,
        trainImageSize: IntSize,
        searchRect: Rect,
        queryMat: Mat,
        queryMatMask: Mat? = null,
        expandCells: Int = 2,
    ): Point2f {
        if (splitBlocks.isEmpty() || splitBlocks[0].isEmpty()) {
            return Point2f.DEFAULT
        }
        return try {
            val range = KeyPointFeatureBlockHelper.getCellRange(trainImageSize, splitBlocks.size, splitBlocks[0].size, searchRect)
            KeyPointFeatureBlockHelper.mergeFeaturesInRange(
                splitBlocks,
                trainKeyPoints,
                trainDescriptors,
                range[0] - expandCells,
                range[1] + expandCells,
                range[2] - expandCells,
                range[3] + expandCells,
            ).use { merged ->
                if (merged.isEmpty || merged.descriptor.empty()) {
                    Point2f.DEFAULT
                } else {
                    knnMatch(merged.keyPoints, merged.descriptor, queryMat, queryMatMask)
                }
            }
        } catch (_: Exception) {
            Point2f.DEFAULT
        }
    }

    /** 查询图四个角在训练图中的位置；失败返回空列表。 */
    fun knnMatchCorners(
        trainKeyPoints: KeyPointPoints,
        trainDescriptors: Mat,
        queryMat: Mat,
        queryMatMask: Mat? = null,
        matcherType: DescriptorMatcherType = DescriptorMatcherType.FlannBased,
    ): List<Point2f> {
        val h = knnHomography(trainKeyPoints, trainDescriptors, queryMat, queryMatMask, matcherType) ?: return emptyList()
        try {
            val w = queryMat.cols().toFloat()
            val hgt = queryMat.rows().toFloat()
            return perspectiveTransform(
                listOf(Point2f(0f, 0f), Point2f(0f, hgt), Point2f(w, hgt), Point2f(w, 0f)),
                h,
            )
        } finally {
            h.release()
        }
    }

    /** 四角投影的外接矩形；失败返回 (0, 0, 0, 0)。 */
    fun knnMatchRect(
        trainKeyPoints: KeyPointPoints,
        trainDescriptors: Mat,
        queryMat: Mat,
        queryMatMask: Mat? = null,
    ): Rect {
        val corners = knnMatchCorners(trainKeyPoints, trainDescriptors, queryMat, queryMatMask)
        if (corners.isEmpty()) return Rect(0, 0, 0, 0)
        val points = MatOfPoint2f(*corners.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        try {
            return Imgproc.boundingRect(points)
        } finally {
            points.release()
        }
    }

    /** KnnMatch 与 KnnMatchCorners 的公共部分：返回单应矩阵，失败返回 null。 */
    private fun knnHomography(
        trainKeyPoints: KeyPointPoints,
        trainDescriptors: Mat,
        queryMat: Mat,
        queryMatMask: Mat?,
        matcherType: DescriptorMatcherType,
    ): Mat? {
        val queryKeyPointsMat = MatOfKeyPoint()
        val queryDescriptors = Mat()
        val mask = queryMatMask ?: Mat()
        val knnMatches = ArrayList<MatOfDMatch>()
        val ransacMask = Mat()
        try {
            sift.detectAndCompute(queryMat, mask, queryKeyPointsMat, queryDescriptors)
            if (queryKeyPointsMat.empty() || queryDescriptors.empty()) {
                // 查询图没有特征点时，空描述子会让 knnMatch 抛异常，直接按失败处理
                return null
            }
            val queryKeyPoints = queryKeyPointsMat.toArray()
            matcher(matcherType).knnMatch(queryDescriptors, trainDescriptors, knnMatches, 2)

            // 比值测试
            val goodMatches = ArrayList<DMatch>()
            for (m in knnMatches) {
                val pair = m.toArray()
                if (pair.size == 2 && pair[0].distance < 0.75 * pair[1].distance) {
                    goodMatches.add(pair[0])
                }
            }
            if (goodMatches.size < 7) {
                return null
            }

            val src = MatOfPoint2f(*goodMatches.map { queryKeyPoints[it.queryIdx].pt }.toTypedArray())
            val dst = MatOfPoint2f(
                *goodMatches.map {
                    org.opencv.core.Point(trainKeyPoints.xs[it.trainIdx].toDouble(), trainKeyPoints.ys[it.trainIdx].toDouble())
                }.toTypedArray(),
            )
            try {
                val hMat = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3.0, ransacMask)
                if (hMat.empty()) {
                    hMat.release()
                    return null
                }
                return hMat
            } finally {
                src.release()
                dst.release()
            }
        } finally {
            queryKeyPointsMat.release()
            queryDescriptors.release()
            if (queryMatMask == null) mask.release()
            knnMatches.forEach { it.release() }
            ransacMask.release()
        }
    }

    private fun perspectiveTransform(points: List<Point2f>, h: Mat): List<Point2f> {
        val src = MatOfPoint2f(*points.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val dst = MatOfPoint2f()
        try {
            Core.perspectiveTransform(src, dst, h)
            return dst.toArray().map { Point2f(it.x.toFloat(), it.y.toFloat()) }
        } finally {
            src.release()
            dst.release()
        }
    }
}

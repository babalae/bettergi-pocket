package com.bettergi.pocket.pathing.match.template

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.common.PointI
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.abs

/**
 * 取极值点 3×3 邻域做二次曲面拟合，得到亚像素位置。
 * 来源：BGI `Core/Recognition/OpenCv/TemplateMatch/SubPixMatch.cs`。
 */
object SubPixMatch {
    private val FEATURES = doubleArrayOf(
        1 / 6.0, -(1 / 3.0), 1 / 6.0, 1 / 6.0, -(1 / 3.0), 1 / 6.0, 1 / 6.0, -(1 / 3.0), 1 / 6.0,
        1 / 6.0, 1 / 6.0, 1 / 6.0, -(1 / 3.0), -(1 / 3.0), -(1 / 3.0), 1 / 6.0, 1 / 6.0, 1 / 6.0,
        1 / 4.0, 0.0, -(1 / 4.0), 0.0, 0.0, 0.0, -(1 / 4.0), 0.0, 1 / 4.0,
        -(1 / 6.0), 0.0, 1 / 6.0, -(1 / 6.0), 0.0, 1 / 6.0, -(1 / 6.0), 0.0, 1 / 6.0,
        -(1 / 6.0), -(1 / 6.0), -(1 / 6.0), 0.0, 0.0, 0.0, 1 / 6.0, 1 / 6.0, 1 / 6.0,
        -(1 / 9.0), 2 / 9.0, -(1 / 9.0), 2 / 9.0, 5 / 9.0, 2 / 9.0, -(1 / 9.0), 2 / 9.0, -(1 / 9.0),
    )

    private const val EPSILON = 1e-20

    /** 读取最值点周围 3x3 区域进行二次拟合。 */
    fun fit(src: Mat, loc: PointI): Point2f {
        if (src.empty()) throw IllegalArgumentException("输入矩阵为空")
        if (src.cols() < 3 || src.rows() < 3) throw IllegalArgumentException("输入矩阵过小")
        if (loc.x < 0 || loc.y < 0 || loc.x >= src.cols() || loc.y >= src.rows()) {
            throw IllegalArgumentException("输入点位超出范围")
        }

        // 边界约束：确保 3x3 邻域有效
        val clampedX = loc.x.coerceIn(1, src.cols() - 2)
        val clampedY = loc.y.coerceIn(1, src.rows() - 2)

        val features = Mat(6, 9, CvType.CV_64F)
        val roi = src.submat(Rect(clampedX - 1, clampedY - 1, 3, 3))
        val neighborhood = Mat()
        val neighborhood64 = Mat()
        val empty = Mat()
        val coefficientMatrix = Mat()
        try {
            features.put(0, 0, *FEATURES)
            roi.copyTo(neighborhood)
            val vector = neighborhood.reshape(0, 9) // 展平为 9x1 向量
            vector.convertTo(neighborhood64, CvType.CV_64FC1)
            vector.release()
            // C# 中的 `Features * neighborhoodMatrix` 即 gemm
            Core.gemm(features, neighborhood64, 1.0, empty, 0.0, coefficientMatrix)
            val c = DoubleArray(6)
            coefficientMatrix.get(0, 0, c)

            // 二次方程判别式
            val discriminant = c[2] * c[2] - 4 * c[0] * c[1]
            if (abs(discriminant) < EPSILON) {
                return Point2f(loc.x.toFloat(), loc.y.toFloat())
            }
            val offsetX = ((2 * c[1] * c[3] - c[2] * c[4]) / discriminant).coerceIn(-1.0, 1.0)
            val offsetY = ((2 * c[0] * c[4] - c[2] * c[3]) / discriminant).coerceIn(-1.0, 1.0)
            return Point2f((offsetX + clampedX).toFloat(), (offsetY + clampedY).toFloat())
        } finally {
            features.release()
            roi.release()
            neighborhood.release()
            neighborhood64.release()
            empty.release()
            coefficientMatrix.release()
        }
    }
}

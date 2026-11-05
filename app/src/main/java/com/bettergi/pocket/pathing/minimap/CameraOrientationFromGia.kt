package com.bettergi.pocket.pathing.minimap

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * 视角识别兜底算法：极坐标展开后找视野扇区两侧边缘。
 * 来源：BGI `GameTask/Common/Map/Camera/CameraOrientationFromGia.cs`。
 */
object CameraOrientationFromGia {
    /**
     * @param mat 212×212 小地图，彩色或灰度均可。BGI 会原地转灰度并模糊，这里在副本上处理，不修改入参。
     * @return 角度（整数值）
     */
    fun computeMiniMap(mat: Mat): Float {
        val gray = Mat()
        val polarMat = Mat()
        val rotated = Mat()
        val scharrResult = Mat()
        try {
            if (mat.channels() == 3) {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            } else {
                mat.copyTo(gray)
            }
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
            // 极坐标展开
            val center = Point((gray.cols() / 2f).toDouble(), (gray.rows() / 2f).toDouble())
            Imgproc.warpPolar(
                gray,
                polarMat,
                Size(360.0, 360.0),
                center,
                360.0,
                Imgproc.INTER_LINEAR or Imgproc.WARP_POLAR_LINEAR,
            )
            val polarRoi = polarMat.submat(Rect(10, 0, 70, polarMat.rows()))
            Core.rotate(polarRoi, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            polarRoi.release()

            Imgproc.Scharr(rotated, scharrResult, CvType.CV_32F, 1, 0)

            // 求波峰
            val left = IntArray(360)
            val right = IntArray(360)
            val array = FloatArray((scharrResult.total() * scharrResult.channels()).toInt())
            scharrResult.get(0, 0, array)
            findPeaks(array).forEach { left[it % 360]++ }
            val reversed = FloatArray(array.size) { -array[it] }
            findPeaks(reversed).forEach { right[it % 360]++ }

            // 优化
            val left2 = IntArray(360) { max(left[it] - right[it], 0) }
            val right2 = IntArray(360) { max(right[it] - left[it], 0) }

            // 左移后相乘，在附近 2° 内寻找最大值
            var sum = IntArray(360)
            for (i in -2..2) {
                val shifted = shift(right2, -90 + i)
                val all = IntArray(360) { j -> left2[j] * shifted[j] * (3 - abs(i)) / 3 }
                sum = IntArray(360) { j -> sum[j] + all[j] }
            }

            // 卷积
            var result = IntArray(360)
            for (i in -2..2) {
                val all = shift(sum, i)
                for (j in all.indices) {
                    all[j] = all[j] * (3 - abs(i)) / 3
                }
                result = IntArray(360) { j -> result[j] + all[j] }
            }

            // 计算结果角度：取第一个最大值
            var maxIndex = 0
            for (j in result.indices) {
                if (result[j] > result[maxIndex]) maxIndex = j
            }
            var angle = maxIndex + 45
            if (angle > 360) {
                angle -= 360
            }
            return angle.toFloat()
        } finally {
            gray.release()
            polarMat.release()
            rotated.release()
            scharrResult.release()
        }
    }

    internal fun findPeaks(data: FloatArray): List<Int> {
        val peaks = ArrayList<Int>()
        for (i in 1 until data.size - 1) {
            if (data[i] > data[i - 1] && data[i] > data[i + 1]) {
                peaks.add(i)
            }
        }
        return peaks
    }

    /** BGI `Shift`：k > 0 右移，k <= 0 左移 -k。结果 `out[j] = array[(j - k) mod n]`。 */
    internal fun shift(array: IntArray, k: Int): IntArray {
        val n = array.size
        return IntArray(n) { j -> array[Math.floorMod(j - k, n)] }
    }
}

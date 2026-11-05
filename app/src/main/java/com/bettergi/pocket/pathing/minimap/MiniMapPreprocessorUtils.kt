package com.bettergi.pocket.pathing.minimap

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * 来源：BGI `GameTask/Common/Map/MiniMap/MiniMapPreprocessorUtils.cs`。
 */
internal object MiniMapPreprocessorUtils {
    /** MaskCalculator 处理的小地图边长。 */
    const val SIZE = 156

    /** 暗角补偿参数（MaskCalculator 与 CameraOrientationCalculator 共用）。 */
    val ALPHA_PARAMS_1 = floatArrayOf(
        18.632f, 20.157f, 24.093f, 34.617f, 38.566f, 41.94f, 47.654f, 51.087f, 58.561f, 63.925f, 67.759f, 71.77f, 75.214f,
    )

    /** 与 C# 单精度运算顺序一致：`a + (b - a) * i / (n - 1)`。 */
    fun linearSpaced(a: Float, b: Float, n: Int, endpoint: Boolean = true): FloatArray {
        return FloatArray(n) { i -> if (endpoint) a + (b - a) * i / (n - 1) else a + (b - a) * i / n }
    }

    /**
     * C# `Array.BinarySearch(alphaParams1, v)` 再取 `index < 0 ? ~index : index`：
     * 未命中时是插入位置，命中时是元素下标，两者都等于「小于 v 的元素个数」。
     */
    fun alphaParamIndex(v: Float): Int = ALPHA_PARAMS_1.count { it < v }

    fun maxMinChannels(bgrMat: Mat, cmax: Mat, cmin: Mat) {
        val bgr = ArrayList<Mat>(3)
        Core.split(bgrMat, bgr)
        try {
            Core.max(bgr[2], bgr[1], cmax)
            Core.max(cmax, bgr[0], cmax)
            Core.min(bgr[2], bgr[1], cmin)
            Core.min(cmin, bgr[0], cmin)
        } finally {
            bgr.forEach { it.release() }
        }
    }

    /** `(input - bkg) / mask * 255 + bkg`，结果为 CV_32F。 */
    fun applyMask(inputMat: Mat, mask: Mat, bkg: Scalar) {
        Core.subtract(inputMat, bkg, inputMat)
        Core.divide(inputMat, mask, inputMat, 255.0, CvType.CV_32F)
        Core.add(inputMat, bkg, inputMat)
    }

    fun bgrToHue(bgrMat: Mat, hImg: Mat, faImg: Mat) {
        val hls = Mat()
        val h = Mat()
        val fa = Mat()
        try {
            Imgproc.cvtColor(bgrMat, hls, Imgproc.COLOR_BGR2HLS_FULL)
            Imgproc.cvtColor(bgrMat, fa, Imgproc.COLOR_BGR2GRAY)
            Core.extractChannel(hls, h, 0)
            h.convertTo(hImg, CvType.CV_32FC1)
            fa.convertTo(faImg, CvType.CV_32FC1)
        } finally {
            hls.release()
            h.release()
            fa.release()
        }
    }

    /** 循环右移 k 列。 */
    fun rightShiftCv(input: Mat, output: Mat, k: Int) {
        val part1 = input.submat(0, input.rows(), input.cols() - k, input.cols()) // 后 k 个元素
        val part2 = input.submat(0, input.rows(), 0, input.cols() - k) // 前面的元素
        try {
            Core.hconcat(listOf(part1, part2), output)
        } finally {
            part1.release()
            part2.release()
        }
    }

    fun floatRowMat(values: FloatArray): Mat {
        return Mat(1, values.size, CvType.CV_32F).also { it.put(0, 0, values) }
    }
}

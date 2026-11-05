package com.bettergi.pocket.pathing.minimap

import com.bettergi.pocket.pathing.common.csRoundToInt
import com.bettergi.pocket.pathing.minimap.MiniMapPreprocessorUtils.SIZE
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 小地图掩膜计算：图标掩膜、暗角与视野扇区补偿、边界掩膜。
 * 来源：BGI `GameTask/Common/Map/MiniMap/MaskCalculator.cs`。
 *
 * 内部缓冲区可变复用，不是线程安全的；[process1] 返回的掩膜在下一次调用前有效。
 */
class MaskCalculator : AutoCloseable {
    private val alphaMask1 = Mat()
    private val alphaMask2 = Mat()
    private val radius = Mat()
    private val angle = Mat()
    private val sectorMask = Mat(SIZE, SIZE, CvType.CV_8UC3)
    private val circleMask = Mat.zeros(SIZE, SIZE, CvType.CV_8UC1)
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    private val outMatF = Mat(SIZE, SIZE, CvType.CV_32FC3)
    private val outMask = Mat(SIZE, SIZE, CvType.CV_8UC1)

    init {
        val xArray = MiniMapPreprocessorUtils.linearSpaced(-SIZE / 2.0f, SIZE / 2.0f, SIZE, false)
        val x = MiniMapPreprocessorUtils.floatRowMat(xArray)
        val xMat = Mat()
        val yMat: Mat
        try {
            Core.repeat(x, SIZE, 1, xMat)
            yMat = xMat.t()
            Core.cartToPolar(xMat, yMat, radius, angle, true)
            yMat.release()
        } finally {
            x.release()
            xMat.release()
        }
        radius.convertTo(radius, CvType.CV_8UC1)
        Core.divide(angle, Scalar(2.0), angle)
        angle.convertTo(angle, CvType.CV_8UC1)

        val lutData1 = ByteArray(256) { v ->
            minOf(229 + MiniMapPreprocessorUtils.alphaParamIndex(v.toFloat()), 255).toByte()
        }
        // C# `(byte)Math.Min(137 + 1.43 * v, 255)`：截断取整
        val lutData2 = ByteArray(256) { v -> minOf(137 + 1.43 * v, 255.0).toInt().toByte() }
        val lut1 = Mat(1, 256, CvType.CV_8UC1).also { it.put(0, 0, lutData1) }
        val lut2 = Mat(1, 256, CvType.CV_8UC1).also { it.put(0, 0, lutData2) }
        try {
            Core.LUT(radius, lut1, alphaMask1)
            Core.LUT(radius, lut2, alphaMask2)
        } finally {
            lut1.release()
            lut2.release()
        }
        Imgproc.cvtColor(alphaMask1, alphaMask1, Imgproc.COLOR_GRAY2BGR)
        Imgproc.cvtColor(alphaMask2, alphaMask2, Imgproc.COLOR_GRAY2BGR)

        Imgproc.circle(circleMask, Point((SIZE / 2).toDouble(), (SIZE / 2).toDouble()), SIZE / 2, Scalar(255.0), -1)
    }

    /**
     * 从小地图中心裁出 156×156（偏移按宽度计算，与 BGI 一致），生成图标掩膜。
     *
     * @return (裁剪视图, 图标掩膜)。裁剪视图由调用方释放；掩膜为内部缓冲区，不要释放。
     */
    fun process1(bgrMat: Mat): Pair<Mat, Mat> {
        val offset = (bgrMat.cols() - SIZE) / 2
        val roi = Mat(bgrMat, Rect(offset, offset, SIZE, SIZE))
        roi.convertTo(outMatF, CvType.CV_32FC3)
        createIconMask(roi)
        return roi to outMask
    }

    /**
     * 按视角去除视野扇区，补偿暗角，生成最终掩膜。必须在 [process1] 之后调用。
     *
     * @return (处理后的 156×156 BGR, 掩膜副本)，都由调用方释放。
     */
    fun process2(angle: Float): Pair<Mat, Mat> {
        createSectorMask(csRoundToInt(angle))
        MiniMapPreprocessorUtils.applyMask(outMatF, sectorMask, Scalar(255.0, 255.0, 255.0))
        MiniMapPreprocessorUtils.applyMask(outMatF, alphaMask1, Scalar(0.0, 0.0, 0.0))
        val outMat = Mat()
        outMatF.convertTo(outMat, CvType.CV_8UC3)
        Core.bitwise_not(outMask, outMask)
        createBgMask(outMat)
        Core.bitwise_and(circleMask, outMask, outMask)
        return outMat to outMask.clone()
    }

    private fun createSectorMask(angle: Int) {
        alphaMask2.copyTo(sectorMask)
        Imgproc.ellipse(
            sectorMask,
            Point((SIZE / 2).toDouble(), (SIZE / 2).toDouble()),
            Size(SIZE.toDouble(), SIZE.toDouble()),
            0.0,
            angle + 45.5,
            angle + 314.5,
            Scalar(255.0, 255.0, 255.0),
            -1,
        )
    }

    private fun createBgMask(bgrMat: Mat) {
        val temp = Mat()
        val morphologyKernel = Mat.ones(2, 2, CvType.CV_8UC1)
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val temp1 = Mat()
        try {
            Core.inRange(bgrMat, Scalar(165.0, 165.0, 55.0), Scalar(180.0, 180.0, 75.0), temp)
            Imgproc.morphologyEx(temp, temp, Imgproc.MORPH_OPEN, morphologyKernel)
            if (Core.countNonZero(temp) == 0) {
                return
            }
            val minDist = ByteArray(256) { 255.toByte() }
            calculateMinDist(temp, minDist)
            lut.put(0, 0, minDist)
            Core.LUT(angle, lut, temp)
            Core.compare(radius, temp, temp, Core.CMP_LT)
            Core.inRange(bgrMat, Scalar.all(100.0), Scalar.all(255.0), temp1)
            Core.bitwise_or(temp1, temp, outMask, outMask)
        } finally {
            temp.release()
            morphologyKernel.release()
            lut.release()
            temp1.release()
        }
    }

    private fun createIconMask(bgrMat: Mat) {
        val cmax = Mat()
        val cmin = Mat()
        val diff = Mat()
        try {
            MiniMapPreprocessorUtils.maxMinChannels(bgrMat, cmax, cmin)
            Core.compare(cmax, cmin, outMask, Core.CMP_EQ)
            Core.inRange(cmax, Scalar(50.0), Scalar(127.0), diff)
            Core.bitwise_and(diff, outMask, outMask)
            Core.subtract(cmax, cmin, diff)
            // C# Cv2.Subtract(255, cmax) 在 8 位上等于按位取反
            Core.bitwise_not(cmax, cmin)
            Core.divide(cmin, Scalar(6.0), cmin)
            Core.min(cmin, diff, diff)
            Core.add(diff, Scalar(10.0), diff)
            cmax.setTo(Scalar(255.0), outMask)
            Core.divide(cmax, diff, cmax, 10.0)
            Imgproc.threshold(cmax, outMask, 200.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.dilate(outMask, outMask, kernel)
            Imgproc.morphologyEx(outMask, outMask, Imgproc.MORPH_CLOSE, kernel)
        } finally {
            cmax.release()
            cmin.release()
            diff.release()
        }
    }

    /** 每个 2° 角度桶内，边界色像素的最小半径。 */
    private fun calculateMinDist(mask: Mat, minDist: ByteArray) {
        val total = SIZE * SIZE
        val maskData = ByteArray(total)
        val angleData = ByteArray(total)
        val radiusData = ByteArray(total)
        mask.get(0, 0, maskData)
        angle.get(0, 0, angleData)
        radius.get(0, 0, radiusData)
        for (i in 0 until total) {
            if ((maskData[i].toInt() and 0xFF) != 255) continue
            val a = angleData[i].toInt() and 0xFF
            val r = radiusData[i].toInt() and 0xFF
            if ((minDist[a].toInt() and 0xFF) > r) {
                minDist[a] = r.toByte()
            }
        }
    }

    override fun close() {
        alphaMask1.release()
        alphaMask2.release()
        radius.release()
        angle.release()
        sectorMask.release()
        circleMask.release()
        kernel.release()
        outMatF.release()
        outMask.release()
    }
}

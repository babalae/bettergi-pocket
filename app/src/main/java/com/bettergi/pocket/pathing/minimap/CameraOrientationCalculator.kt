package com.bettergi.pocket.pathing.minimap

import com.bettergi.pocket.pathing.minimap.MiniMapPreprocessorUtils.SIZE
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * 根据小地图视野扇区的亮度变化求摄像机朝向。
 * 来源：BGI `GameTask/Common/Map/MiniMap/CameraOrientationCalculator.cs`。
 *
 * 输入为 [MaskCalculator.process1] 输出的 156×156 BGR 与图标掩膜。不是线程安全的。
 */
class CameraOrientationCalculator : AutoCloseable {
    private val rotationRemapDataX = Mat()
    private val rotationRemapDataY = Mat()
    private val alphaMask1Remap = Mat()
    private val alphaMask2Remap = Mat()

    init {
        val rArray = MiniMapPreprocessorUtils.linearSpaced(TPL_INN_RAD.toFloat(), TPL_OUT_RAD.toFloat(), R_LENGTH)
        val thetaArray = MiniMapPreprocessorUtils.linearSpaced(0f, 360f, THETA_LENGTH, false)

        val r = MiniMapPreprocessorUtils.floatRowMat(rArray)
        val theta = Mat(THETA_LENGTH, 1, CvType.CV_32F).also { it.put(0, 0, thetaArray) }
        val rMat = Mat()
        val thetaMat = Mat()
        try {
            Core.repeat(r, THETA_LENGTH, 1, rMat)
            Core.repeat(theta, 1, R_LENGTH, thetaMat)
            Core.polarToCart(rMat, thetaMat, rotationRemapDataX, rotationRemapDataY, true)
        } finally {
            r.release()
            theta.release()
            rMat.release()
            thetaMat.release()
        }
        Core.add(rotationRemapDataX, Scalar((SIZE / 2).toDouble()), rotationRemapDataX)
        Core.add(rotationRemapDataY, Scalar((SIZE / 2).toDouble()), rotationRemapDataY)

        val alpha2Row = MiniMapPreprocessorUtils.floatRowMat(FloatArray(R_LENGTH) { i -> (137 + 1.43 * rArray[i]).toFloat() })
        val alpha1Row = MiniMapPreprocessorUtils.floatRowMat(
            FloatArray(R_LENGTH) { i -> (229 + MiniMapPreprocessorUtils.alphaParamIndex(rArray[i])).toFloat() },
        )
        try {
            Core.repeat(alpha2Row, THETA_LENGTH, 1, alphaMask2Remap)
            Core.repeat(alpha1Row, THETA_LENGTH, 1, alphaMask1Remap)
        } finally {
            alpha2Row.release()
            alpha1Row.release()
        }
    }

    /**
     * @return (角度 0~360, 置信度)
     */
    fun predictRotation(src: Mat, mask: Mat): Pair<Float, Float> {
        val remapSrc = Mat()
        val remapMask = Mat()
        val hImg = Mat()
        val faImg = Mat()
        val fbImg = Mat()
        val histA = Mat()
        val histB = Mat()
        val noMask = Mat()
        val result = Mat(1, THETA_LENGTH, CvType.CV_32FC1)
        val resized = Mat()
        val resultShift = Mat()
        val integral = Mat()
        var temp: Mat? = null
        try {
            Imgproc.remap(src, remapSrc, rotationRemapDataX, rotationRemapDataY, Imgproc.INTER_LINEAR)
            Imgproc.remap(mask, remapMask, rotationRemapDataX, rotationRemapDataY, Imgproc.INTER_NEAREST)
            MiniMapPreprocessorUtils.bgrToHue(remapSrc, hImg, faImg)
            temp = faImg.clone()
            MiniMapPreprocessorUtils.applyMask(faImg, alphaMask1Remap, Scalar(0.0))
            temp.copyTo(faImg, remapMask)
            MiniMapPreprocessorUtils.applyMask(temp, alphaMask2Remap, Scalar(255.0))
            temp.copyTo(fbImg)
            MiniMapPreprocessorUtils.applyMask(fbImg, alphaMask1Remap, Scalar(0.0))
            temp.copyTo(fbImg, remapMask)

            val channels = MatOfInt(0, 1)
            val histSize = MatOfInt(F_LENGTH, F_LENGTH)
            val ranges = MatOfFloat(0f, 256f, 0f, 256f)
            Imgproc.calcHist(listOf(hImg, faImg), channels, noMask, histA, histSize, ranges)
            Imgproc.calcHist(listOf(hImg, fbImg), channels, noMask, histB, histSize, ranges)
            channels.release()
            histSize.release()
            ranges.release()

            val total = THETA_LENGTH * R_LENGTH
            val h = FloatArray(total).also { hImg.get(0, 0, it) }
            val fa = FloatArray(total).also { faImg.get(0, 0, it) }
            val fb = FloatArray(total).also { fbImg.get(0, 0, it) }
            val ha = FloatArray(F_LENGTH * F_LENGTH).also { histA.get(0, 0, it) }
            val hb = FloatArray(F_LENGTH * F_LENGTH).also { histB.get(0, 0, it) }
            val acc = FloatArray(THETA_LENGTH)
            for (i in 0 until total) {
                val hv = h[i]
                val fav = fa[i]
                val fbv = fb[i]
                if (hv < 0 || hv >= 256 || fav < 0 || fbv >= 256) continue
                if (fav >= 256) {
                    acc[i / R_LENGTH] += 255.0f
                } else if (fbv < 0) {
                    acc[i / R_LENGTH] += -255.0f
                } else {
                    val row = (hv / 256 * F_LENGTH).toInt() * F_LENGTH
                    val a = ha[row + (fav / 256 * F_LENGTH).toInt()]
                    val b = hb[row + (fbv / 256 * F_LENGTH).toInt()]
                    acc[i / R_LENGTH] += if (a > b) 0.0f else if (abs(a - b) < 0.0001) 100.0f else 255.0f
                }
            }
            result.put(0, 0, acc)

            Imgproc.resize(result, resized, Size((THETA_LENGTH * SCALE).toDouble(), 1.0), 0.0, 0.0, Imgproc.INTER_CUBIC)
            MiniMapPreprocessorUtils.rightShiftCv(resized, resultShift, PEAK_WIDTH)
            val peakRegion = resultShift.submat(0, 1, 0, PEAK_WIDTH)
            val peakRegionSum = Core.sumElems(peakRegion).`val`[0]
            peakRegion.release()
            Core.subtract(resized, resultShift, resultShift)
            Imgproc.integral(resultShift, integral)
            val mm = Core.minMaxLoc(integral)
            var degree = (mm.maxLoc.x.toInt() - 1).toFloat() / THETA_LENGTH * 360 / SCALE - 45
            if (degree < 0) {
                degree += 360
            }
            val rotationConfidence = (mm.maxVal + peakRegionSum).toFloat() / (PEAK_WIDTH * R_LENGTH * 255)
            return degree to rotationConfidence
        } finally {
            remapSrc.release()
            remapMask.release()
            hImg.release()
            faImg.release()
            fbImg.release()
            histA.release()
            histB.release()
            noMask.release()
            result.release()
            resized.release()
            resultShift.release()
            integral.release()
            temp?.release()
        }
    }

    override fun close() {
        rotationRemapDataX.release()
        rotationRemapDataY.release()
        alphaMask1Remap.release()
        alphaMask2Remap.release()
    }

    private companion object {
        const val TPL_OUT_RAD = 78
        const val TPL_INN_RAD = 19
        const val R_LENGTH = 60
        const val THETA_LENGTH = 360
        const val F_LENGTH = 256
        const val SCALE = 2
        const val PEAK_WIDTH = THETA_LENGTH / 4 * SCALE + 1
    }
}

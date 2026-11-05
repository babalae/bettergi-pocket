package com.bettergi.pocket.pathing.match.template

import com.bettergi.pocket.pathing.common.PointI
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 带掩膜的快速平方差模板匹配：用 CCORR 拼出 Σ mask·(S - T)² 中与位置相关的部分。
 * 来源：BGI `Core/Recognition/OpenCv/TemplateMatch/FastSqDiffMatcher.cs`。
 *
 * 返回值 `Val` 不含 Σ T²，调用方需加上 [getTplSumSq] 的结果才是真实 SSD。
 *
 * 与 BGI 的区别（结构调整，结果相同）：BGI 构造时把源图平方（float）常驻，每像素多占 12 字节，
 * 342 个图层合计约 240 MiB。这里只常驻 8 位通道，匹配时对搜索区域现算平方；
 * 0~255 的平方在 float 下是精确值，结果与常驻版本一致。
 */
class FastSqDiffMatcher(source: Mat, templateSize: Size) : AutoCloseable {
    /** 源图各通道（8 位）。 */
    val source: List<Mat>

    init {
        if (source.empty()) throw IllegalArgumentException("源图像为空")
        if (templateSize.width > source.cols() || templateSize.height > source.rows()) {
            throw IllegalArgumentException("模板图尺寸超过源图片尺寸")
        }
        this.source = ArrayList<Mat>().also { Core.split(source, it) }
    }

    val sourceWidth: Int get() = source[0].cols()
    val sourceHeight: Int get() = source[0].rows()

    fun match(maskedTemplates: List<Mat>, maskF: Mat): Pair<PointI, Double> {
        return match(source, maskedTemplates, maskF)
    }

    fun match(maskedTemplates: List<Mat>, maskF: Mat, rect: Rect, channels: IntArray? = null): Pair<PointI, Double> {
        val sourceRoi = source.map { it.submat(rect) }
        try {
            return match(selectByIndex(sourceRoi, channels), selectByIndex(maskedTemplates, channels), maskF)
        } finally {
            sourceRoi.forEach { it.release() }
        }
    }

    private fun match(source: List<Mat>, maskedTemplates: List<Mat>, maskF: Mat): Pair<PointI, Double> {
        val n = source.size
        if (maskedTemplates.size != n) {
            throw IllegalArgumentException("模板图通道数 ${maskedTemplates.size} 与源图像通道数 $n 不匹配")
        }
        val crossCorr = Mat()
        val temp = Mat()
        val sourceSq = Mat()
        try {
            Imgproc.matchTemplate(source[0], maskedTemplates[0], crossCorr, Imgproc.TM_CCORR)
            for (i in 1 until n) {
                Imgproc.matchTemplate(source[i], maskedTemplates[i], temp, Imgproc.TM_CCORR)
                Core.add(crossCorr, temp, crossCorr)
            }
            Core.multiply(crossCorr, Scalar(-2.0), crossCorr)
            // 源图平方与掩膜的加权和
            for (i in 0 until n) {
                source[i].convertTo(sourceSq, CvType.CV_32F)
                Core.multiply(sourceSq, sourceSq, sourceSq)
                Imgproc.matchTemplate(sourceSq, maskF, temp, Imgproc.TM_CCORR)
                Core.add(crossCorr, temp, crossCorr)
            }
            val mm = Core.minMaxLoc(crossCorr)
            return PointI.of(mm.minLoc) to mm.minVal
        } finally {
            crossCorr.release()
            temp.release()
            sourceSq.release()
        }
    }

    override fun close() {
        source.forEach { it.release() }
    }

    companion object {
        /**
         * @param template 模板图
         * @param mask 8UC1 掩膜，尺寸与模板相同
         * @return 掩膜后的模板各通道，以及归一化到 [0, 1] 的 float 掩膜
         */
        fun preProcess(template: Mat, mask: Mat): Pair<List<Mat>, Mat> {
            if (mask.type() != CvType.CV_8UC1) throw IllegalArgumentException("遮罩格式不对")
            if (template.cols() != mask.cols() || template.rows() != mask.rows()) {
                throw IllegalArgumentException("模板图与遮罩尺寸不匹配")
            }
            val maskF = Mat()
            mask.convertTo(maskF, CvType.CV_32F)
            Core.normalize(maskF, maskF, 0.0, 1.0, Core.NORM_MINMAX)

            val maskedTemplate = Mat(template.size(), template.type(), Scalar.all(0.0))
            try {
                Core.bitwise_and(template, template, maskedTemplate, mask)
                val maskedTemplates = ArrayList<Mat>().also { Core.split(maskedTemplate, it) }
                return maskedTemplates to maskF
            } finally {
                maskedTemplate.release()
            }
        }

        fun getTplSumSq(maskedTemplates: List<Mat>, channels: IntArray? = null): Double {
            return selectByIndex(maskedTemplates, channels).sumOf { Core.norm(it, Core.NORM_L2SQR) }
        }

        fun <T> selectByIndex(items: List<T>, channels: IntArray? = null): List<T> {
            if (channels == null) return items
            return channels.filter { it >= 0 && it < items.size }.map { items[it] }
        }
    }
}

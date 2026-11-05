package com.bettergi.pocket.pathing.match.template

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.common.PointI
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 来源：BGI `Core/Recognition/OpenCv/TemplateMatch/TemplateMatchHelper.cs`。
 */
object TemplateMatchHelper {
    fun matchTemplate(image: Mat, template: Mat, method: Int, mask: Mat? = null): Pair<PointI, Double> {
        val result = Mat()
        try {
            runMatch(image, template, result, method, mask)
            return pickExtreme(result, method)
        } finally {
            result.release()
        }
    }

    fun matchTemplateNormalized(image: Mat, template: Mat, method: Int, mask: Mat? = null): Pair<PointI, Double> {
        val normalizer = TemplateMatchNormalizer.forTemplate(template, mask, method)
        val (loc, value) = matchTemplate(image, template, method, mask)
        normalizer.value = value
        return loc to normalizer.confidence()
    }

    /** 匹配后在极值点附近做亚像素拟合，返回 (亚像素位置, 极值)。 */
    fun matchTemplateSubPix(image: Mat, template: Mat, method: Int, mask: Mat?): Pair<Point2f, Double> {
        val result = Mat()
        try {
            runMatch(image, template, result, method, mask)
            val (loc, value) = pickExtreme(result, method)
            return SubPixMatch.fit(result, loc) to value
        } finally {
            result.release()
        }
    }

    private fun runMatch(image: Mat, template: Mat, result: Mat, method: Int, mask: Mat?) {
        if (mask != null) {
            Imgproc.matchTemplate(image, template, result, method, mask)
        } else {
            Imgproc.matchTemplate(image, template, result, method)
        }
    }

    private fun pickExtreme(result: Mat, method: Int): Pair<PointI, Double> {
        val mm = Core.minMaxLoc(result)
        return if (method == Imgproc.TM_SQDIFF || method == Imgproc.TM_SQDIFF_NORMED) {
            PointI.of(mm.minLoc) to mm.minVal
        } else {
            PointI.of(mm.maxLoc) to mm.maxVal
        }
    }
}

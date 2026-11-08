package com.bettergi.pocket.pathing.vision

import com.bettergi.pocket.recognition.IntRect
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

data class TemplateHit(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val score: Double,
) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
}

/**
 * 在灰度图上做归一化相关系数匹配。坐标相对整张图，[roi] 为空时搜全图。
 */
object ScreenTemplate {
    fun findBest(gray: Mat, template: Mat, roi: IntRect?, threshold: Double, mask: Mat? = null): TemplateHit? {
        return findAll(gray, template, roi, threshold, 1, mask).firstOrNull()
    }

    fun findAll(
        gray: Mat,
        template: Mat,
        roi: IntRect?,
        threshold: Double,
        limit: Int,
        mask: Mat? = null,
    ): List<TemplateHit> {
        if (gray.empty() || template.empty() || limit <= 0) return emptyList()
        val search = (roi ?: IntRect(0, 0, gray.cols(), gray.rows())).clampTo(gray.cols(), gray.rows())
        if (search.width < template.cols() || search.height < template.rows()) return emptyList()
        val view = gray.submat(Rect(search.x, search.y, search.width, search.height))
        val result = Mat()
        try {
            if (mask == null) Imgproc.matchTemplate(view, template, result, Imgproc.TM_CCOEFF_NORMED)
            else {
                Imgproc.matchTemplate(view, template, result, Imgproc.TM_CCOEFF_NORMED, mask)
                // 常量区域的掩膜相关系数可能是 NaN/Infinity，不能作为命中。
                val valid = Mat()
                try {
                    Core.inRange(result,Scalar(-1.001),Scalar(1.001),valid)
                    Core.bitwise_not(valid,valid)
                    result.setTo(Scalar(-1.0),valid)
                } finally { valid.release() }
            }
            val hits = ArrayList<TemplateHit>(limit.coerceAtMost(32))
            var lastX = Int.MIN_VALUE
            var lastY = Int.MIN_VALUE
            while (hits.size < limit) {
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal < threshold || mm.maxLoc.x < 0) break
                val x = mm.maxLoc.x.toInt()
                val y = mm.maxLoc.y.toInt()
                if (x == lastX && y == lastY) break
                lastX = x
                lastY = y
                hits += TemplateHit(search.x + x, search.y + y, template.cols(), template.rows(), mm.maxVal)
                val x1 = (x - template.cols() / 2).coerceAtLeast(0)
                val y1 = (y - template.rows() / 2).coerceAtLeast(0)
                val x2 = (x + template.cols()).coerceAtMost(result.cols())
                val y2 = (y + template.rows()).coerceAtMost(result.rows())
                if (x2 <= x1 || y2 <= y1) break
                Imgproc.rectangle(result, Point(x1.toDouble(), y1.toDouble()), Point(x2.toDouble(), y2.toDouble()), Scalar(0.0), -1)
            }
            return hits
        } finally {
            view.release()
            result.release()
        }
    }
}

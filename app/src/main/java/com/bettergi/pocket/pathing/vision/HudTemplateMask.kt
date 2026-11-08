package com.bettergi.pocket.pathing.vision

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 从派蒙亮色轮廓生成前景掩膜，填回眼睛等内部暗部，排除参考截图的场景背景。 */
object HudTemplateMask {
    fun paimon(template: Mat): Mat {
        val mask = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0,3.0))
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.threshold(template,mask,210.0,255.0,Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel)
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
            Imgproc.drawContours(mask,contours,-1,Scalar(255.0),-1)
            return mask
        } catch (e: Exception) { mask.release();throw e }
        finally { hierarchy.release();kernel.release();contours.forEach { it.release() } }
    }
}

package com.bettergi.pocket.pathing.minimap

import org.opencv.core.Mat

/**
 * 来源：BGI `GameTask/Common/Map/MiniMap/MiniMapPreprocessor.cs`。
 *
 * 输入均为二次缩放后的 212×212 BGR 小地图（见 [MiniMapLocator]）。不是线程安全的。
 */
class MiniMapPreprocessor : AutoCloseable {
    private val maskCalculator = MaskCalculator()
    private val coCalculator = CameraOrientationCalculator()
    private var closed = false

    /** @return (视角角度, 置信度) */
    fun predictRotationWithConfidence(miniMap: Mat): Pair<Float, Float> {
        val (src, mask) = maskCalculator.process1(miniMap)
        try {
            return coCalculator.predictRotation(src, mask)
        } finally {
            src.release()
        }
    }

    fun predictRotation(miniMap: Mat): Float = predictRotationWithConfidence(miniMap).first

    /**
     * TemplateMatch 定位的输入预处理。
     * @return (156×156 BGR, 8UC1 掩膜)，由调用方释放。
     */
    fun getMiniMapAndMask(miniMap: Mat): Pair<Mat, Mat> {
        val (src, mask) = maskCalculator.process1(miniMap)
        try {
            val (angle, _) = coCalculator.predictRotation(src, mask)
            return maskCalculator.process2(angle)
        } finally {
            src.release()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        coCalculator.close()
        maskCalculator.close()
    }
}

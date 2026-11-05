package com.bettergi.pocket.pathing.minimap

import org.opencv.core.Mat

/**
 * 计算当前小地图摄像机朝向的角度（度，图像坐标系下自 +x 顺时针）。
 * 来源：BGI `GameTask/Common/Map/CameraOrientation.cs`。
 *
 * 与 BGI 的区别（结构调整，逻辑不变）：BGI 的 `Compute(Mat 全屏截图)` 在内部按 1080p 坐标裁小地图；
 * Pocket 由 [MiniMapLocator] 统一完成锚定裁剪和二次缩放，这里只接收 212×212 小地图。
 */
object CameraOrientation {
    /** BGI 中为静态单例 `_coV2`，这里同样共享一个实例并加锁。 */
    private val coV2 = MiniMapPreprocessor()

    /** 置信度低于该值时改用 [CameraOrientationFromGia]。 */
    const val MIN_CONFIDENCE = 0.2

    /**
     * @param miniMap 212×212 BGR 小地图，不会被修改
     */
    fun computeMiniMap(miniMap: Mat): Float {
        val (angle, confidence) = synchronized(coV2) { coV2.predictRotationWithConfidence(miniMap) }
        if (confidence < MIN_CONFIDENCE) {
            return CameraOrientationFromGia.computeMiniMap(miniMap)
        }
        return angle
    }
}

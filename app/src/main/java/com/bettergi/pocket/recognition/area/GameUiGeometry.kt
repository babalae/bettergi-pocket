package com.bettergi.pocket.recognition.area

import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import com.bettergi.pocket.recognition.SearchAnchorMode
import kotlin.math.abs

/** HUD 的安全边距和图标缩放独立于截图尺寸；游戏仍然可以渲染到挖孔下面。 */
data class GameUiGeometry(
    val frameSize: IntSize,
    val referenceSize: IntSize,
    val scale: Double,
    val leftInset: Double,
    val rightInset: Double,
) {
    fun mapping(width: Int, height: Int, reference: IntSize, rect: IntRect,
                anchor: SearchAnchorMode): ReferenceRectMapping? {
        if (reference != referenceSize || width <= 0 || height <= 0) return null
        val ratio = width / frameSize.width.toDouble()
        // 支持识别用的 1920 宽缩放图；旋转、单应用共享尺寸变化后不得沿用旧标定。
        if (abs(height - frameSize.height * ratio) > 1.1) return null
        val s = scale * ratio
        val (horizontal, vertical) = ReferenceSearch.resolveAnchor(anchor, rect, reference)
        val x = when (horizontal) {
            ReferenceSearch.HorizontalAnchor.Left -> leftInset * ratio
            ReferenceSearch.HorizontalAnchor.Right -> width - rightInset * ratio - reference.width * s
            ReferenceSearch.HorizontalAnchor.Center -> (width - reference.width * s + (leftInset - rightInset) * ratio) / 2
        }
        val y = when (vertical) {
            ReferenceSearch.VerticalAnchor.Top -> 0.0
            ReferenceSearch.VerticalAnchor.Bottom -> height - reference.height * s
            ReferenceSearch.VerticalAnchor.Center -> (height - reference.height * s) / 2
        }
        return ReferenceRectMapping(s, x, y, ReferenceSearch.transformReferenceRect(rect, s, x, y))
    }
}

/** 仅保留当前共享会话的标定，不持久化到另一台手机。发布不可变快照供识别与输入线程共用。 */
object GameUiCalibration {
    @Volatile var current: GameUiGeometry? = null
        internal set

    fun clear() { current = null }
}

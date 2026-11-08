package com.bettergi.pocket.pathing.party

import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.SearchAnchorMode
import com.bettergi.pocket.recognition.area.GameUiCalibration
import com.bettergi.pocket.recognition.area.ReferenceSearch
import org.opencv.core.Mat
import org.opencv.core.Rect

data class VisibleAvatar(val avatar: PartyAvatar, val bounds: IntRect, val confidence: Float)

/** 只有四人单人队伍才接受；重复头像、低置信度和界面遮挡均不会作为有效队伍。 */
data class PartySnapshot(val visible: List<VisibleAvatar>) {
    val names: Set<String> get() = visible.map { it.avatar.name }.toSet()
    val valid: Boolean get() = visible.size == 3 && names.size == 3

    fun switchedFrom(previous: PartySnapshot, target: String): Boolean =
        valid && previous.valid && target in previous.names && target !in names &&
            (previous.names - target).all { it in names } && (names - previous.names).size == 1
}

fun interface PartyRecognizer {
    fun recognize(frame: Mat): PartySnapshot?
}

class PartyRecognition(private val layout: PathingLayout, private val classifier: NcnnAvatarClassifier) : PartyRecognizer {
    private var cachedRects: List<IntRect>? = null
    private var cachedOffset: Pair<Int, Int>? = null

    override fun recognize(frame: Mat): PartySnapshot? {
        val reference = layout.referenceSize ?: error("缺少队伍头像参考画布")
        require(layout.partyAvatars.size == 3) { "需要标定三个后台角色头像" }
        val rects = layout.partyAvatars.map { referenceRect ->
            ReferenceSearch.mapReferenceRect(frame.cols(), frame.rows(), reference,
                referenceRect, SearchAnchorMode.TopRight, GameUiCalibration.current)?.rect ?: return null
        }
        if (cachedRects != rects) { cachedRects = rects; cachedOffset = null }
        cachedOffset?.let { (x,y) -> read(frame, rects, x, y)?.let { return it } }
        read(frame, rects, 0, 0)?.let { return it }
        // 宽屏手机的队伍列表会整体上移，不能把平板上的 TopRight 纵坐标直接复用。
        // 保持三个头像的尺寸与间距，使用模型共同确认整个列表；不接受单个偶然命中。
        if (GameUiCalibration.current == null) return null
        val step = (rects.first().height / 4).coerceAtLeast(1)
        val dx = rects.first().width / 5
        var best: PartySnapshot? = null
        var offset: Pair<Int,Int>? = null
        var score = 0.85
        for (x in listOf(0, -dx, dx)) {
            for (y in -(frame.rows() / 4)..(frame.rows() / 8) step step) {
                val candidate = read(frame, rects, x, y) ?: continue
                val confidence = candidate.visible.map { it.confidence.toDouble() }.average()
                if (confidence > score) { best = candidate; offset = x to y; score = confidence }
            }
        }
        cachedOffset = offset
        return best
    }

    private fun read(frame: Mat, rects: List<IntRect>, dx: Int, dy: Int): PartySnapshot? {
        val visible = rects.map { base ->
            val rect = base.offset(dx, dy)
            if (rect.left < 0 || rect.top < 0 || rect.right > frame.cols() || rect.bottom > frame.rows()) return null
            val crop = frame.submat(Rect(rect.left, rect.top, rect.width, rect.height))
            val classified = try { classifier.classify(crop) } finally { crop.release() } ?: return null
            VisibleAvatar(classified.first, rect, classified.second)
        }
        return PartySnapshot(visible).takeIf { it.valid }
    }
}

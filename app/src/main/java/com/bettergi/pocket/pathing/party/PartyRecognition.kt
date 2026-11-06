package com.bettergi.pocket.pathing.party

import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.SearchAnchorMode
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
    override fun recognize(frame: Mat): PartySnapshot? {
        val reference = layout.referenceSize ?: error("缺少队伍头像参考画布")
        require(layout.partyAvatars.size == 3) { "需要标定三个后台角色头像" }
        val visible = layout.partyAvatars.map { referenceRect ->
            val rect = ReferenceSearch.mapReferenceRect(frame.cols(), frame.rows(), reference,
                referenceRect, SearchAnchorMode.TopRight)?.rect ?: return null
            if (rect.left < 0 || rect.top < 0 || rect.right > frame.cols() || rect.bottom > frame.rows()) return null
            val crop = frame.submat(Rect(rect.left, rect.top, rect.width, rect.height))
            val classified = try { classifier.classify(crop) } finally { crop.release() } ?: return null
            VisibleAvatar(classified.first, rect, classified.second)
        }
        return PartySnapshot(visible).takeIf { it.valid }
    }
}

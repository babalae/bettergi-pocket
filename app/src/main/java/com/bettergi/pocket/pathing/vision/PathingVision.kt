package com.bettergi.pocket.pathing.vision

import org.opencv.core.Mat

enum class MotionStatus {
    Unknown,
    Climb,
    Fly,
    Other,
}

/**
 * 手机画面识别。模板还没标定前用 [UnavailablePathingVision]，相关分支不会误触发。
 */
interface PathingVision {
    fun motionStatus(frame: Mat): MotionStatus

    fun isInMainUi(frame: Mat): Boolean

    fun isInBigMap(frame: Mat): Boolean

    /** 烹饪页或关闭按钮的点击位置。没有则返回 null。 */
    fun closeButton(frame: Mat): Pair<Int, Int>?

    fun inDialogue(frame: Mat): Boolean

    /** 对话选项里优先点最后一个。没有则返回 null。 */
    fun lastDialogueOption(frame: Mat): Pair<Int, Int>?

    fun zhongliSkillVisible(frame: Mat): Boolean

    /** 小道具 CD 秒数。无法识别时返回 null，按没有 CD 处理。 */
    fun gadgetCooldownSeconds(frame: Mat): Double?

    /**
     * 四个队伍槽位的特征。无法识别时返回 null。
     * 三个相同、一个不同时，不同的那个是出战位（从 1 开始）。
     */
    fun partySlotSignatures(frame: Mat): List<Int>?
}

fun activePartySlot(signatures: List<Int>?): Int? {
    if (signatures == null || signatures.size != 4) return null
    val counts = signatures.groupingBy { it }.eachCount()
    if (counts.size != 2) return null
    val unique = counts.entries.firstOrNull { it.value == 1 } ?: return null
    if (counts.values.none { it == 3 }) return null
    return signatures.indexOf(unique.key) + 1
}

object UnavailablePathingVision : PathingVision {
    override fun motionStatus(frame: Mat) = MotionStatus.Unknown
    override fun isInMainUi(frame: Mat) = true
    override fun isInBigMap(frame: Mat) = false
    override fun closeButton(frame: Mat) = null
    override fun inDialogue(frame: Mat) = false
    override fun lastDialogueOption(frame: Mat) = null
    override fun zhongliSkillVisible(frame: Mat) = false
    override fun gadgetCooldownSeconds(frame: Mat) = null
    override fun partySlotSignatures(frame: Mat) = null
}

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
}

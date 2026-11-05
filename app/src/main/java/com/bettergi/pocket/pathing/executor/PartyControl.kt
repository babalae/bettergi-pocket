package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.GameButton
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.pathing.vision.MotionStatus
import com.bettergi.pocket.pathing.vision.PathingVision
import com.bettergi.pocket.pathing.vision.activePartySlot

/**
 * 按 1–4 号位点队伍槽。不识别角色名。
 * 钟离靠 E 按钮上的技能图标判断，命中后向后轻点再放 E。来源：BGI `UseElementalSkill`。
 */
class PartyControl(
    private val runtime: PathingRuntime,
    private val input: GameInput,
    private val settings: PathingSettings,
    private val vision: PathingVision,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long,
) {
    private var lastSkillAtMs = 0L

    fun switchTo(index: Int?, requireSkillReady: Boolean = false): Boolean {
        if (index == null) return false
        val intervalMs = (settings.guardianSkillIntervalSeconds ?: 0.0) * 1000.0
        if (requireSkillReady && intervalMs > 0 && nowMs() - lastSkillAtMs < intervalMs) {
            log("生存位技能还在间隔内，跳过")
            return false
        }
        repeat(5) {
            val signatures = readSignatures()
            if (signatures == null) {
                dropIfAirborne()
                input.tapButton(partyButton(index))
                runtime.delay(100)
                log("无法识别出战角色，已点第 $index 号位")
                return true
            }
            if (activePartySlot(signatures) == index) return true
            dropIfAirborne()
            input.tapButton(partyButton(index))
            runtime.delay(250)
        }
        log("切换到第 $index 号位失败")
        return false
    }

    /**
     * @param walking 当前是否按着前进。钟离序列会松开再重新按住。
     */
    fun useGuardianSkill(walking: Boolean) {
        val index = settings.guardianAvatarIndex ?: return
        runtime.delay(200)
        if (!switchTo(index, requireSkillReady = true)) return
        val zhongli = readZhongli()
        if (zhongli) {
            input.releasePad()
            runtime.delay(50)
            input.holdBackward()
            runtime.delay(80)
            input.releasePad()
            runtime.delay(200)
        }
        if (settings.guardianSkillLongPress) {
            input.holdButton(GameButton.Skill)
            runtime.delay(1000)
            input.releaseButton(GameButton.Skill)
        } else {
            input.tapButton(GameButton.Skill)
        }
        lastSkillAtMs = nowMs()
        if (zhongli && walking) input.holdForward()
    }

    fun skillIntervalElapsed(): Boolean {
        val seconds = settings.guardianSkillIntervalSeconds ?: return false
        return nowMs() - lastSkillAtMs > seconds * 1000.0
    }

    private fun readSignatures(): List<Int>? {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return null
        try {
            return vision.partySlotSignatures(frame.bgr)
        } finally {
            frame.bgr.release()
        }
    }

    private fun readZhongli(): Boolean {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return false
        try {
            return vision.zhongliSkillVisible(frame.bgr)
        } finally {
            frame.bgr.release()
        }
    }

    private fun dropIfAirborne() {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return
        try {
            val status = vision.motionStatus(frame.bgr)
            if (status == MotionStatus.Climb || status == MotionStatus.Fly) {
                input.tapButton(GameButton.Drop)
            }
        } finally {
            frame.bgr.release()
        }
    }

    private fun partyButton(index: Int): GameButton = when (index) {
        1 -> GameButton.Party1
        2 -> GameButton.Party2
        3 -> GameButton.Party3
        4 -> GameButton.Party4
        else -> throw IllegalArgumentException("队伍位只能是 1–4")
    }
}

package com.bettergi.pocket.pathing.party

import android.os.SystemClock
import com.bettergi.pocket.pathing.GameButton
import com.bettergi.pocket.pathing.executor.PathingRuntime
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.model.HandledException
import com.bettergi.pocket.pathing.model.PathAction
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.pathing.vision.PathingVision

/** 来源：PC PathExecutor、PathingConditionConfig、ElementalCollectHandler。切人改为手机头像点按。 */
class PathingParty(
    private val runtime: PathingRuntime,
    private val input: GameInput,
    private val recognition: PartyRecognizer,
    private val settings: PathingSettings,
    private val vision: PathingVision,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var team: List<PartyAvatar> = emptyList()
    private var lastSwitch: Long? = null
    private var lastGuardian: Long? = null
    private val lastSkill = HashMap<String, Long>()

    fun invalidate() { team = emptyList() }

    fun prepare(actions: Set<PathAction>) {
        if (team.isNotEmpty()) return
        input.releaseAll()
        val before = snapshot()
        // 先切到一个可见角色，通过列表差集识别原来隐藏的第四人。
        val target = before.visible.first().avatar.name
        val after = switchVisible(before, target)
        team = (before.visible + after.visible).map { it.avatar }.distinctBy { it.name }
        if (team.size != 4) fail("未能识别完整四人队伍")
        log("当前队伍：${team.joinToString { it.name }}")
        // 候选名单可包含不在当前队伍中的角色；按名单顺序匹配，而非要求全部在队。
        actions.mapNotNull(::elementFor).distinct().forEach { element ->
            if (elementAvatar(element) == null) fail("队伍中没有可用于${elementName(element)}元素采集的角色")
        }
    }

    fun walk() {
        mainAvatar()?.let { switchTo(it.name) }
    }

    /** 地面行走且目标尚远时调用；飞行、攀爬、游泳、跳跃期间不续盾。 */
    fun renewGuardian(): Boolean {
        val guardian = guardianAvatar() ?: return false
        val longPress = guardian.name !in settings.guardianShortAvatars && guardian.name in settings.guardianLongAvatars
        val configuredCd = if (longPress) guardian.skillHoldCd.takeIf { it > 0 } ?: guardian.skillCd else guardian.skillCd
        val interval = if (settings.guardianIntervalSeconds > 0) settings.guardianIntervalSeconds else configuredCd
        if (interval < 1) fail("生存位战技冷却未配置，请设置续盾间隔")
        if (lastGuardian?.let { nowMs() - it < (interval * 1000).toLong() } == true) return false
        input.releaseAll()
        switchTo(guardian.name)
        if (guardian.name == "钟离") {
            // PC 同样向身后放柱子，避免挡住路线。
            requireInput(input.holdBackward().succeeded, "转向")
            runtime.delay(200)
            input.releasePad()
        }
        useSkill(guardian, longPress)
        lastGuardian = nowMs()
        log("生存位 ${guardian.name}：${if (longPress) "长按" else "短按"}战技")
        walk()
        return true
    }

    fun collect(action: PathAction) {
        val element = elementFor(action) ?: return
        val avatar = elementAvatar(element) ?: fail("缺少${elementName(element)}元素采集角色")
        input.releaseAll()
        switchTo(avatar.name)
        log("${elementName(element)}元素采集：${avatar.name}")
        if (avatar.normalAttack) {
            requireInput(input.tapButton(GameButton.Attack, 100).succeeded, "元素普攻")
        } else {
            useSkill(avatar, false)
        }
        runtime.delay(500)
        walk()
    }

    private fun mainAvatar(): PartyAvatar? = PartyCandidates.first(team, settings.mainAvatars)

    private fun guardianAvatar(): PartyAvatar? =
        PartyCandidates.first(team, settings.guardianShortAvatars) ?:
            PartyCandidates.first(team, settings.guardianLongAvatars)

    private fun elementAvatar(element: String): PartyAvatar? {
        val candidates = team.filter { it.element == element && (it.normalAttack || it.elementalSkill) }
        return settings.elementAvatars[element]?.let { PartyCandidates.first(candidates, it) } ?:
            candidates.firstOrNull().takeIf { element !in settings.elementAvatars }
    }

    private fun useSkill(avatar: PartyAvatar, longPress: Boolean) {
        val cd = (if (longPress) avatar.skillHoldCd.takeIf { it > 0 } ?: avatar.skillCd else avatar.skillCd) * 1000
        lastSkill[avatar.name]?.let { last -> runtime.delay(((last + cd.toLong()) - nowMs()).coerceAtLeast(0)) }
        // 长按分片等待，停止时 finally 保证抬起战技触点。
        if (longPress) {
            requireInput(input.holdButton(GameButton.Skill).succeeded, "长按战技")
            try { runtime.delay(1200) } finally { input.releaseButton(GameButton.Skill) }
        } else requireInput(input.tapButton(GameButton.Skill).succeeded, "战技")
        lastSkill[avatar.name] = nowMs()
        runtime.delay(800)
    }

    private fun switchTo(name: String) {
        val before = snapshot()
        if (name !in before.names) {
            // 仅在完整队伍已识别且三个可见头像恰好为其余队员时推断当前角色。
            if (team.size == 4 && team.map { it.name }.toSet() == before.names + name) {
                return
            }
            fail("队伍发生变化或无法确认当前角色，请重新开始路线")
        }
        if (team.isNotEmpty() && !team.map { it.name }.containsAll(before.names)) fail("队伍已变化，请重新开始路线")
        switchVisible(before, name)
    }

    private fun switchVisible(before: PartySnapshot, name: String): PartySnapshot {
        lastSwitch?.let { runtime.delay((1200 - (nowMs() - it)).coerceAtLeast(0)) }
        val target = before.visible.first { it.avatar.name == name }
        val rect = target.bounds
        repeat(3) {
            requireInput(input.tapNative(rect.left + rect.width / 2, rect.top + rect.height / 2).succeeded, "切人")
            // 等待切人动画及队伍栏重排稳定后，再识别原来隐藏的第四人。
            runtime.delay(700)
            val after = snapshot()
            if (after.switchedFrom(before, name)) {
                lastSwitch = nowMs()
                return after
            }
            if (after.names != before.names) fail("切人后队伍不一致，停止当前路线")
            runtime.delay(700)
        }
        fail("切换角色「$name」失败，可能处于战斗动作、冷却或禁止切人状态")
    }

    private fun snapshot(): PartySnapshot {
        repeat(4) {
            val frame = runtime.nextFrame(1000)
            if (frame != null) try {
                if (vision.isInMainUi(frame.bgr)) recognition.recognize(frame.bgr)?.let { return it }
            } finally { frame.bgr.release() }
            runtime.delay(250)
        }
        fail("无法识别队伍头像，请确认单人四人队伍、主界面无遮挡且头像区域已标定")
    }

    private fun requireInput(success: Boolean, action: String) { if (!success) fail("$action 触控失败或按钮未标定") }
    private fun fail(message: String): Nothing = throw HandledException(message)

    companion object {
        const val AUTO = "自动"
        val SHORT_GUARDIANS = setOf("绮良良", "莱依拉", "茜特菈莉", "芭芭拉", "七七")
        fun elementFor(action: PathAction): String? = when (action) {
            PathAction.HydroCollect -> "Hydro"
            PathAction.ElectroCollect -> "Electro"
            PathAction.AnemoCollect -> "Anemo"
            PathAction.PyroCollect -> "Pyro"
            else -> null
        }
        fun elementName(element: String): String = mapOf("Hydro" to "水", "Electro" to "雷", "Anemo" to "风", "Pyro" to "火")[element] ?: element
    }
}

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
    // 切人失败的角色可能已经死亡；本条路线内不再尝试，重新识别队伍也不清除。
    private val failedSwitchAvatars = HashSet<String>()

    fun invalidate() { team = emptyList() }

    fun prepare(actions: Set<PathAction>) {
        if (team.isNotEmpty()) return
        input.releaseAll()
        val before = snapshot()
        // 先切到一个可见角色，通过列表差集识别原来隐藏的第四人。
        val after = before.visible.firstNotNullOfOrNull {
            val name = it.avatar.name
            if (name in failedSwitchAvatars) null else switchVisible(before, name)
        } ?: fail("无法切换到任何可见角色，未能识别完整四人队伍")
        team = (before.visible + after.visible).map { it.avatar }.distinctBy { it.name }
        if (team.size != 4) fail("未能识别完整四人队伍")
        log("当前队伍：${team.joinToString { it.name }}")
        // 候选名单可包含不在当前队伍中的角色；按名单顺序匹配，而非要求全部在队。
        actions.mapNotNull(::elementFor).distinct().forEach { element ->
            if (elementAvatar(element, availableOnly = false) == null) fail("队伍中没有可用于${elementName(element)}元素采集的角色")
        }
    }

    fun walk() {
        while (true) {
            val avatar = mainAvatar() ?: return
            if (switchTo(avatar.name)) return
        }
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
        if (!switchTo(guardian.name)) return false
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
        val avatar = elementAvatar(element) ?: return
        input.releaseAll()
        if (!switchTo(avatar.name)) return
        log("${elementName(element)}元素采集：${avatar.name}")
        if (avatar.normalAttack) {
            requireInput(input.tapButton(GameButton.Attack, 100).succeeded, "元素普攻")
        } else {
            useSkill(avatar, false)
        }
        runtime.delay(500)
        walk()
    }

    private fun availableTeam(): List<PartyAvatar> = team.filter { it.name !in failedSwitchAvatars }

    private fun mainAvatar(): PartyAvatar? = PartyCandidates.first(availableTeam(), settings.mainAvatars)

    private fun guardianAvatar(): PartyAvatar? =
        PartyCandidates.first(availableTeam(), settings.guardianShortAvatars) ?:
            PartyCandidates.first(availableTeam(), settings.guardianLongAvatars)

    private fun elementAvatar(element: String, availableOnly: Boolean = true): PartyAvatar? {
        val candidates = team.filter {
            it.element == element && (it.normalAttack || it.elementalSkill) &&
                (!availableOnly || it.name !in failedSwitchAvatars)
        }
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

    private fun switchTo(name: String): Boolean {
        if (name in failedSwitchAvatars) return false
        val before = snapshot()
        if (name !in before.names) {
            // 仅在完整队伍已识别且三个可见头像恰好为其余队员时推断当前角色。
            if (team.size == 4 && team.map { it.name }.toSet() == before.names + name) {
                return true
            }
            fail("队伍发生变化或无法确认当前角色，请重新开始路线")
        }
        if (team.isNotEmpty() && !team.map { it.name }.containsAll(before.names)) fail("队伍已变化，请重新开始路线")
        return switchVisible(before, name) != null
    }

    private fun switchVisible(before: PartySnapshot, name: String): PartySnapshot? {
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
        failedSwitchAvatars += name
        log("切换角色「$name」失败，可能已经死亡，本条路线后续不再尝试切换该角色")
        return null
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

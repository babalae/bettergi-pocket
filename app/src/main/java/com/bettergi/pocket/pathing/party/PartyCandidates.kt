package com.bettergi.pocket.pathing.party

/** 与 PC 条件配置的“从左到右”一致；不受手机头像重排或当前角色隐藏影响。 */
object PartyCandidates {
    fun first(team: List<PartyAvatar>, priority: List<String>): PartyAvatar? {
        val byName = team.associateBy { it.name }
        return priority.firstNotNullOfOrNull { byName[it] }
    }
}

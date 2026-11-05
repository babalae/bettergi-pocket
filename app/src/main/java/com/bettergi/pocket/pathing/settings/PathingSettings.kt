package com.bettergi.pocket.pathing.settings

/**
 * 地图追踪只保留的 5 个设置。未填写的队伍位为 null。
 * 生存位 E 间隔若填写则必须 ≥ 1 秒，避免走到 BGI「间隔太短直接退出且不松开前进」的分支。
 */
data class PathingSettings(
    val mainAvatarIndex: Int? = null,
    val guardianAvatarIndex: Int? = null,
    val guardianSkillIntervalSeconds: Double? = null,
    val guardianSkillLongPress: Boolean = false,
    val useGadgetIntervalMs: Int = 0,
) {
    init {
        require(mainAvatarIndex == null || mainAvatarIndex in 1..4) { "行走位只能是 1–4" }
        require(guardianAvatarIndex == null || guardianAvatarIndex in 1..4) { "生存位只能是 1–4" }
        val interval = guardianSkillIntervalSeconds
        require(interval == null || interval >= 1.0) { "生存位 E 间隔不能小于 1 秒" }
        require(useGadgetIntervalMs >= 0) { "自动按 Z 间隔不能为负" }
    }

    companion object {
        /** BGI `AutoRunEnabled` 默认 true。 */
        const val AUTO_RUN = true

        /** BGI `AutoSkipEnabled` 默认 true。 */
        const val AUTO_SKIP = true

        /** BGI `HurryOnFrameInterval` 固定 100。 */
        const val FRAME_INTERVAL_MS = 100L

        /** 每段重试次数，来源：BGI `PathExecutor.RetryTimes`。 */
        const val RETRY_TIMES = 2

        /** 手机拾取点击间隔，沿用自动拾取的 800 ms。 */
        const val PICKUP_INTERVAL_MS = 800L
    }
}

package com.bettergi.pocket.pathing.settings

import com.bettergi.pocket.pathing.map.MapMatchingMethod

/**
 * 地图追踪设置。早期手机队伍栏分不出号位，曾去掉行走位、生存位和按号位切人。
 * 现在通过 ncnn 识别头像后按角色名切换；小道具通过独立开关启用，兼容旧设置中间隔 0 表示关闭。
 */
data class PathingSettings(
    val useGadgetIntervalMs: Int = 0,
    val mapMatchingMethod: MapMatchingMethod = MapMatchingMethod.TemplateMatch,
    /** 候选角色从左到右匹配，未在队伍中的候选直接跳过。空列表表示关闭。 */
    val mainAvatars: List<String> = DEFAULT_WALKERS,
    val guardianShortAvatars: List<String> = DEFAULT_SHORT_GUARDIANS,
    val guardianLongAvatars: List<String> = DEFAULT_LONG_GUARDIANS,
    /** 0 使用 PC 角色资料中的战技冷却，其他值至少一秒。 */
    val guardianIntervalSeconds: Double = 0.0,
    /** 未配置元素列表时自动使用对应元素的能力配置；显式空列表表示禁用。 */
    val elementAvatars: Map<String, List<String>> = emptyMap(),
    val useGadgetEnabled: Boolean = useGadgetIntervalMs > 0,
) {
    init {
        require(useGadgetIntervalMs >= 0) { "自动按 Z 间隔不能为负" }
        require(guardianIntervalSeconds.isFinite() && (guardianIntervalSeconds == 0.0 || guardianIntervalSeconds >= 1.0)) {
            "续盾间隔必须为自动或至少一秒"
        }
    }

    val needsParty: Boolean get() = mainAvatars.isNotEmpty() ||
        guardianShortAvatars.isNotEmpty() || guardianLongAvatars.isNotEmpty()

    companion object {
        val DEFAULT_WALKERS = listOf("迪希雅")
        val DEFAULT_SHORT_GUARDIANS = listOf("绮良良", "莱依拉", "茜特菈莉", "芭芭拉", "七七")
        val DEFAULT_LONG_GUARDIANS = listOf("钟离")
        /** BGI `AutoRunEnabled` 默认 true。 */
        const val AUTO_RUN = true

        /** BGI `AutoSkipEnabled` 默认 true。 */
        const val AUTO_SKIP = true

        /** BGI `HurryOnFrameInterval` 固定 100。 */
        const val FRAME_INTERVAL_MS = 100L

        /** 每段重试次数，来源：BGI `PathExecutor.RetryTimes`。 */
        const val RETRY_TIMES = 2

        /** 手机拾取点击间隔；原为 800 ms，真机漏拾后改为每秒 5 次。 */
        const val PICKUP_INTERVAL_MS = 200L

        /** 终点先停稳，给游戏的物品交互列表留出显示时间。 */
        const val END_PICKUP_SETTLE_MS = 300L

        /** 每个路径段结束前继续拾取两秒，最后一次点按也有完整的抬手时间。 */
        const val END_PICKUP_WINDOW_MS = 2000L
    }
}

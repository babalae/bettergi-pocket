package com.bettergi.pocket.pathing.settings

/**
 * 地图追踪设置。手机队伍栏分不出号位，行走位、生存位和按号位切人已去掉。
 * 目前只保留自动点小道具的间隔，0 表示关闭。
 */
data class PathingSettings(
    val useGadgetIntervalMs: Int = 0,
) {
    init {
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

        /** 手机拾取点击间隔；原为 800 ms，真机漏拾后改为每秒 5 次。 */
        const val PICKUP_INTERVAL_MS = 200L

        /** 终点先停稳，给游戏的物品交互列表留出显示时间。 */
        const val END_PICKUP_SETTLE_MS = 300L

        /** 每个路径段结束前继续拾取两秒，最后一次点按也有完整的抬手时间。 */
        const val END_PICKUP_WINDOW_MS = 2000L
    }
}

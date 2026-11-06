package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.settings.PathingSettings

/** 路径段结束时等交互条显示并连续拾取；delay 使用 runtime.delay，用户停止可立即取消。 */
internal object EndpointPickup {
    fun collect(delay: (Long) -> Unit, pickup: () -> Unit) {
        delay(PathingSettings.END_PICKUP_SETTLE_MS)
        var elapsed = 0L
        while (elapsed < PathingSettings.END_PICKUP_WINDOW_MS) {
            pickup()
            // 最后一次点击后也要等待，不能由结束清理把尚未完成的点按撤掉。
            delay(PathingSettings.PICKUP_INTERVAL_MS)
            elapsed += PathingSettings.PICKUP_INTERVAL_MS
        }
    }
}

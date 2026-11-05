package com.bettergi.pocket.pathing

/**
 * 地图追踪独占取帧时置位。实时任务的 tick 看到置位就跳过，不取图也不丢帧。
 *
 * 不改用户的实时任务开关。任务结束清掉标志后，实时任务按原开关继续。
 */
object TaskGate {
    @Volatile
    var exclusiveRunning: Boolean = false
}

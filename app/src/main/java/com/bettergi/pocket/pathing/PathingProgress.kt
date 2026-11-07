package com.bettergi.pocket.pathing

/** 批次的只读进度，供面板与状态条使用，不参与执行决策。 */
data class PathingProgress(
    val index: Int, val total: Int, val name: String,
    val completed: Int, val skipped: Int, val failed: Int,
    val startedAtMs: Long, val finished: Boolean = false,
)

data class PathingPreflight(val reason: String? = null, val action: Action? = null) {
    val ready: Boolean get() = reason == null
    enum class Action { ScreenShare, Accessibility }
}

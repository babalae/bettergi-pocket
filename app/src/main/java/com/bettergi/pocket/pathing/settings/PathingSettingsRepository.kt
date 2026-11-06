package com.bettergi.pocket.pathing.settings

import android.content.Context

/**
 * 地图追踪设置存在 SharedPreferences `pathing_settings`。
 * 悬浮窗用点按切换小道具间隔预设。
 */
class PathingSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): PathingSettings = PathingSettings(
        useGadgetIntervalMs = prefs.getInt(KEY_GADGET, 0).coerceAtLeast(0),
    )

    fun cycleGadgetInterval(): PathingSettings = update {
        val next = GADGETS[(GADGETS.indexOf(it.useGadgetIntervalMs) + 1) % GADGETS.size]
        it.copy(useGadgetIntervalMs = next)
    }

    fun describe(settings: PathingSettings = get()): String {
        return "自动 Z ${if (settings.useGadgetIntervalMs == 0) "关" else "${settings.useGadgetIntervalMs} ms"}"
    }

    private fun update(transform: (PathingSettings) -> PathingSettings): PathingSettings {
        val updated = transform(get())
        prefs.edit()
            .putInt(KEY_GADGET, updated.useGadgetIntervalMs)
            .apply()
        return updated
    }

    companion object {
        const val PREFS_NAME = "pathing_settings"
        private const val KEY_GADGET = "useGadgetIntervalMs"
        private val GADGETS = intArrayOf(0, 500, 1000, 2000, 5000)
    }
}

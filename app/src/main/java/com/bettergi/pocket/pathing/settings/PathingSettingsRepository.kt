package com.bettergi.pocket.pathing.settings

import android.content.Context

/**
 * 五个设置存在 SharedPreferences `pathing_settings`。
 * 悬浮窗用点按切换预设，不能选出小于 1 秒的 E 间隔。
 */
class PathingSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): PathingSettings = PathingSettings(
        mainAvatarIndex = prefs.getInt(KEY_MAIN, 0).takeIf { it in 1..4 },
        guardianAvatarIndex = prefs.getInt(KEY_GUARDIAN, 0).takeIf { it in 1..4 },
        guardianSkillIntervalSeconds = prefs.getString(KEY_INTERVAL, null)?.toDoubleOrNull(),
        guardianSkillLongPress = prefs.getBoolean(KEY_LONG_PRESS, false),
        useGadgetIntervalMs = prefs.getInt(KEY_GADGET, 0).coerceAtLeast(0),
    )

    fun cycleMainAvatar(): PathingSettings = update { it.copy(mainAvatarIndex = cycleIndex(it.mainAvatarIndex)) }

    fun cycleGuardianAvatar(): PathingSettings = update { it.copy(guardianAvatarIndex = cycleIndex(it.guardianAvatarIndex)) }

    fun cycleSkillInterval(): PathingSettings = update {
        val next = INTERVALS[(INTERVALS.indexOf(it.guardianSkillIntervalSeconds) + 1) % INTERVALS.size]
        it.copy(guardianSkillIntervalSeconds = next)
    }

    fun toggleLongPress(): PathingSettings = update { it.copy(guardianSkillLongPress = !it.guardianSkillLongPress) }

    fun cycleGadgetInterval(): PathingSettings = update {
        val next = GADGETS[(GADGETS.indexOf(it.useGadgetIntervalMs) + 1) % GADGETS.size]
        it.copy(useGadgetIntervalMs = next)
    }

    fun describe(settings: PathingSettings = get()): String {
        return buildString {
            append("行走位 ${indexLabel(settings.mainAvatarIndex)}")
            append("\n生存位 ${indexLabel(settings.guardianAvatarIndex)}")
            append("\nE 间隔 ${settings.guardianSkillIntervalSeconds?.let { "${it.toInt()} 秒" } ?: "关"}")
            append("\n长按 E ${if (settings.guardianSkillLongPress) "开" else "关"}")
            append("\n自动 Z ${if (settings.useGadgetIntervalMs == 0) "关" else "${settings.useGadgetIntervalMs} ms"}")
        }
    }

    private fun update(transform: (PathingSettings) -> PathingSettings): PathingSettings {
        val updated = transform(get())
        prefs.edit()
            .putInt(KEY_MAIN, updated.mainAvatarIndex ?: 0)
            .putInt(KEY_GUARDIAN, updated.guardianAvatarIndex ?: 0)
            .putString(KEY_INTERVAL, updated.guardianSkillIntervalSeconds?.toString())
            .putBoolean(KEY_LONG_PRESS, updated.guardianSkillLongPress)
            .putInt(KEY_GADGET, updated.useGadgetIntervalMs)
            .apply()
        return updated
    }

    private fun cycleIndex(current: Int?): Int? = when (current) {
        null -> 1
        1 -> 2
        2 -> 3
        3 -> 4
        else -> null
    }

    private fun indexLabel(index: Int?): String = index?.toString() ?: "无"

    companion object {
        const val PREFS_NAME = "pathing_settings"
        private const val KEY_MAIN = "mainAvatarIndex"
        private const val KEY_GUARDIAN = "guardianAvatarIndex"
        private const val KEY_INTERVAL = "guardianSkillIntervalSeconds"
        private const val KEY_LONG_PRESS = "guardianSkillLongPress"
        private const val KEY_GADGET = "useGadgetIntervalMs"
        private val INTERVALS = arrayOf<Double?>(null, 1.0, 2.0, 5.0, 10.0, 15.0, 20.0)
        private val GADGETS = intArrayOf(0, 500, 1000, 2000, 5000)
    }
}

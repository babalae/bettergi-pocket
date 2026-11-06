package com.bettergi.pocket.pathing.settings

import org.json.JSONArray

/** 有序 JSON 数组保留用户选择的优先级，不能使用无序的 StringSet。 */
object CandidatePreferences {
    fun decode(json: String?): List<String>? = runCatching {
        val rows = JSONArray(json ?: return null)
        (0 until rows.length()).map { rows.getString(it) }.filter { it.isNotBlank() }.distinct()
    }.getOrNull()

    fun fromLegacy(value: String?, defaults: List<String>): List<String> = when (value) {
        null, "自动" -> defaults
        "" -> emptyList()
        else -> listOf(value)
    }
}

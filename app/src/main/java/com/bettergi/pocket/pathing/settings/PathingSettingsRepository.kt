package com.bettergi.pocket.pathing.settings

import android.content.Context
import com.bettergi.pocket.pathing.party.AvatarCatalog
import org.json.JSONArray
import com.bettergi.pocket.pathing.map.MapMatchingMethod

/**
 * 地图追踪设置存在 SharedPreferences `pathing_settings`。
 * 悬浮窗输入小道具间隔并独立启用，点按切换默认地图匹配方式。
 */
class PathingSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val catalog by lazy { AvatarCatalog.load(context.applicationContext.assets) }

    fun get(): PathingSettings = PathingSettings(
        useGadgetIntervalMs = prefs.getInt(KEY_GADGET, 0).takeIf { it > 0 } ?: 1000,
        // 旧版以正数间隔表示启用，升级后保留原来的开关状态。
        useGadgetEnabled = prefs.getBoolean(KEY_GADGET_ENABLED, prefs.getInt(KEY_GADGET, 0) > 0),
        mapMatchingMethod = MapMatchingMethod.resolve(prefs.getString(KEY_MAP_MATCHING, null)),
        mainAvatars = readCandidates("mainAvatars", "mainAvatar", PathingSettings.DEFAULT_WALKERS),
        guardianShortAvatars = readGuardians(longPress = false),
        guardianLongAvatars = readGuardians(longPress = true),
        guardianIntervalSeconds = prefs.getString("guardianIntervalSeconds", "0")?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && (it == 0.0 || it >= 1.0) } ?: 0.0,
        elementAvatars = ELEMENTS.associateWith { element ->
            readCandidates("elementCandidates_$element", "element_$element", defaultElementCandidates(element))
        },
    )

    fun toggleMapMatchingMethod(): PathingSettings = update {
        it.copy(mapMatchingMethod = if (it.mapMatchingMethod == MapMatchingMethod.SIFT) {
            MapMatchingMethod.TemplateMatch
        } else MapMatchingMethod.SIFT)
    }

    fun describe(settings: PathingSettings = get()): String {
        return "自动 Z ${if (!settings.useGadgetEnabled || settings.useGadgetIntervalMs == 0) "关" else "${settings.useGadgetIntervalMs} ms"}"
    }

    fun update(transform: (PathingSettings) -> PathingSettings): PathingSettings {
        val updated = transform(get())
        prefs.edit()
            .putInt(KEY_GADGET, updated.useGadgetIntervalMs)
            .putBoolean(KEY_GADGET_ENABLED, updated.useGadgetEnabled)
            .putString(KEY_MAP_MATCHING, updated.mapMatchingMethod.value)
            .putString("mainAvatars", JSONArray(updated.mainAvatars.distinct()).toString())
            .putString("guardianShortAvatars", JSONArray(updated.guardianShortAvatars.distinct()).toString())
            .putString("guardianLongAvatars", JSONArray(updated.guardianLongAvatars.distinct()).toString())
            .putString("guardianIntervalSeconds", updated.guardianIntervalSeconds.toString())
            .also { editor -> ELEMENTS.forEach { element ->
                editor.putString("elementCandidates_$element", JSONArray(
                    updated.elementAvatars[element] ?: defaultElementCandidates(element)).toString())
            } }
            .apply()
        return updated
    }

    fun defaultElementCandidates(element: String): List<String> = catalog.avatars.filter {
        it.element == element && (it.normalAttack || it.elementalSkill)
    }.map { it.name }

    private fun readCandidates(key: String, legacyKey: String, defaults: List<String>): List<String> {
        if (prefs.contains(key)) return CandidatePreferences.decode(prefs.getString(key, "[]")) ?: defaults
        return CandidatePreferences.fromLegacy(prefs.getString(legacyKey, null), defaults)
    }

    private fun readGuardians(longPress: Boolean): List<String> {
        val key = if (longPress) "guardianLongAvatars" else "guardianShortAvatars"
        val defaults = if (longPress) PathingSettings.DEFAULT_LONG_GUARDIANS else PathingSettings.DEFAULT_SHORT_GUARDIANS
        if (prefs.contains(key)) return CandidatePreferences.decode(prefs.getString(key, "[]")) ?: defaults
        val legacy = prefs.getString("guardianAvatar", null)
        if (legacy == null || legacy == "自动") return defaults
        return if (prefs.getBoolean("guardianLongPress", false) == longPress)
            CandidatePreferences.fromLegacy(legacy, defaults) else emptyList()
    }

    companion object {
        const val PREFS_NAME = "pathing_settings"
        val ELEMENTS = listOf("Hydro", "Electro", "Anemo", "Pyro")
        private const val KEY_GADGET = "useGadgetIntervalMs"
        private const val KEY_GADGET_ENABLED = "useGadgetEnabled"
        private const val KEY_MAP_MATCHING = "mapMatchingMethod"
    }
}

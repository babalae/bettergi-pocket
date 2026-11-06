package com.bettergi.pocket.pathing.map

/**
 * 地图实例缓存，键为 `"{地图}_{匹配方式}"`。
 * 来源：BGI `GameTask/Common/Map/Maps/MapManager.cs`。
 *
 * 使用前需设置 [assets]（Android 上为 [AndroidMapAssetSource]）。
 * 第一版只支持提瓦特，其他地图直接抛异常。
 */
object MapManager {
    /** BGI 默认的匹配方式（`PathingConditionConfig.MapMatchingMethod`）。路线和坐标显示由调用方传入设置解析后的实际方式。 */
    const val DEFAULT_MATCHING_METHOD = "TemplateMatch"
    const val SIFT = "SIFT"

    @Volatile
    var assets: MapAssetSource? = null

    private val maps = HashMap<String, SceneMap>()

    fun getMap(mapName: String, matchingMethod: String?): SceneMap {
        return getMap(MapTypes.parseFromName(mapName), matchingMethod)
    }

    fun getMap(mapType: MapTypes, matchingMethod: String?): SceneMap {
        val method = if (matchingMethod.isNullOrEmpty()) DEFAULT_MATCHING_METHOD else matchingMethod
        val key = "${mapType}_$method"
        synchronized(maps) {
            maps[key]?.let { return it }
            val map = createMap(mapType, method)
            maps[key] = map
            return map
        }
    }

    private fun createMap(mapType: MapTypes, matchingMethod: String): SceneMap {
        val source = assets ?: throw IllegalStateException("MapManager.assets 未设置")
        if (mapType != MapTypes.Teyvat) {
            throw IllegalArgumentException("暂不支持地图：${mapType.description}")
        }
        return if (matchingMethod == SIFT) TeyvatMap(source) else TeyvatTemplateMatchMap(source)
    }

    /** 释放所有地图数据（任务结束时调用）。 */
    fun releaseAll() {
        synchronized(maps) {
            maps.values.forEach { it.release() }
            maps.clear()
        }
        BigMapTeyvat256Layer.releaseInstance()
    }
}

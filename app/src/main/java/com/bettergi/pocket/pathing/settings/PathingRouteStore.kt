package com.bettergi.pocket.pathing.settings

import android.content.Context
import java.io.File
import org.json.JSONArray

/** 路线内容在执行到该文件时读取，批量任务不会一次把所有 JSON 装进内存。 */
data class PathingRouteSource(val name: String, val readText: () -> String)

/**
 * 记住当前选中的打包路线；兼容旧的单文件选择，也支持目录和多文件队列。
 */
class PathingRouteStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var debugRoute: Pair<String, String>? = null

    fun label(): String {
        debugRoute?.let { return it.first }
        if (prefs.contains(KEY_SELECTION)) return prefs.getString(KEY_SUMMARY, null)
            ?: selectedPaths().size.let { if (it == 0) "未选择路线" else "已选 $it 条路线" }
        val path = prefs.getString(KEY_PATH, null) ?: return "未选择路线"
        return prefs.getString(KEY_LABEL, null) ?: path.substringAfterLast('/').removeSuffix(".json")
    }

    fun selectedPath(): String? = if (debugRoute != null) null else prefs.getString(KEY_PATH, null)

    fun selectedPaths(): Set<String> {
        if (debugRoute != null) return emptySet()
        val json = prefs.getString(KEY_SELECTION, null) ?: return setOfNotNull(selectedPath())
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun hasSelection(): Boolean = debugRoute != null || selectedPaths().isNotEmpty()

    fun select(paths: Collection<String>, summary: String? = null) {
        require(paths.all { it.isNotBlank() && !it.startsWith('/') && ".." !in it.split('/') }) {
            "路线路径不合法"
        }
        debugRoute = null
        prefs.edit().remove(KEY_PATH).remove(KEY_LABEL)
            .putString(KEY_SUMMARY, summary)
            .putString(KEY_SELECTION, JSONArray(paths.toList()).toString()).apply()
    }

    /** 调试入口直接指定一条路线文件，不走打包目录。 */
    fun useFile(file: File) {
        debugRoute = file.name to file.readText()
    }

    fun select(relativePath: String, displayName: String) {
        val path = relativePath.trim().replace('\\', '/')
        require(path.isNotEmpty() && !path.startsWith("/") && ".." !in path.split('/')) {
            "路线路径不合法"
        }
        debugRoute = null
        prefs.edit()
            .remove(KEY_SELECTION).remove(KEY_SUMMARY)
            .remove(KEY_URI)
            .remove(KEY_TREE)
            .putString(KEY_PATH, path)
            .putString(KEY_LABEL, displayName)
            .apply()
    }

    fun readRoutes(): List<PathingRouteSource> {
        debugRoute?.let { (name, text) -> return listOf(PathingRouteSource(name) { text }) }
        val selection = selectedPaths()
        if (selection.isEmpty()) return emptyList()
        val catalog = appContext.assets.open(RouteCatalog.INDEX_ASSET)
            .bufferedReader(Charsets.UTF_8).use { RouteCatalog.parse(it.readText()) }
        val routes = catalog.resolve(selection).map { node ->
            PathingRouteSource(node.name) {
                readAsset(node.relativePath) ?: error("找不到路线：${node.relativePath}")
            }
        }
        require(routes.isNotEmpty()) { "所选路线已不存在，请重新选择" }
        return routes
    }

    private fun readAsset(relativePath: String): String? {
        return try {
            appContext.assets.open("${RouteCatalog.ROOT_ASSET}/$relativePath")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "pathing_routes"
        private const val KEY_PATH = "asset_path"
        private const val KEY_SELECTION = "asset_selection"
        private const val KEY_SUMMARY = "selection_summary"
        private const val KEY_LABEL = "label"
        private const val KEY_URI = "uri"
        private const val KEY_TREE = "tree"
    }
}

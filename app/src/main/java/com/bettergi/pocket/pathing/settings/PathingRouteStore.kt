package com.bettergi.pocket.pathing.settings

import android.content.Context
import java.io.File

/**
 * 记住当前选中的打包路线，并在开始时从 assets 读出 JSON。
 */
class PathingRouteStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var debugRoute: Pair<String, String>? = null

    fun label(): String {
        debugRoute?.let { return it.first }
        val path = prefs.getString(KEY_PATH, null) ?: return "未选择路线"
        return prefs.getString(KEY_LABEL, null) ?: path.substringAfterLast('/').removeSuffix(".json")
    }

    fun selectedPath(): String? = if (debugRoute != null) null else prefs.getString(KEY_PATH, null)

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
            .remove(KEY_URI)
            .remove(KEY_TREE)
            .putString(KEY_PATH, path)
            .putString(KEY_LABEL, displayName)
            .apply()
    }

    fun readRoutes(): List<Pair<String, String>> {
        debugRoute?.let { return listOf(it) }
        val path = prefs.getString(KEY_PATH, null) ?: return emptyList()
        val text = readAsset(path) ?: error("找不到路线：$path")
        return listOf(label() to text)
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
        private const val KEY_LABEL = "label"
        private const val KEY_URI = "uri"
        private const val KEY_TREE = "tree"
    }
}

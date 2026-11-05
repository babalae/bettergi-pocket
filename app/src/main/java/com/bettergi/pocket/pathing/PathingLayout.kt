package com.bettergi.pocket.pathing

import com.bettergi.pocket.pathing.map.MapAssetSource
import com.bettergi.pocket.pathing.minimap.MiniMapLayout
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import com.bettergi.pocket.recognition.SearchAnchorMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 地图追踪的手机 UI 标定数据，来自 `assets/pathing/layout.json`。
 *
 * 坐标在 `referenceSize` 画布上量取，运行时按 ReferenceSearch 的自动锚定换算到当前分辨率。
 * 未标定的项为 null，使用方需给出明确提示。
 *
 * 文件格式：
 * ```json
 * {
 *   "version": 1,
 *   "referenceSize": [3040, 1904],
 *   "miniMap": { "bbox": "rect(x, y, w, h)", "anchor": "Auto" }
 * }
 * ```
 */
data class PathingLayout(
    val referenceSize: IntSize?,
    val miniMap: MiniMapLayout?,
) {
    companion object {
        const val FILE_NAME = "layout.json"

        fun load(source: MapAssetSource): PathingLayout = parse(source.readText(FILE_NAME))

        fun parse(json: String): PathingLayout {
            val root = JSONObject(json)
            val referenceSize = root.optJSONArray("referenceSize")?.let(::parseSize)
            val miniMapJson = root.optJSONObject("miniMap")
            val miniMap = if (referenceSize != null && miniMapJson != null) {
                MiniMapLayout(
                    referenceSize = referenceSize,
                    bbox = parseRect(miniMapJson.getString("bbox")),
                    anchorMode = miniMapJson.optString("anchor", SearchAnchorMode.Auto.name)
                        .let { SearchAnchorMode.valueOf(it) },
                )
            } else {
                null
            }
            return PathingLayout(referenceSize, miniMap)
        }

        private fun parseSize(array: JSONArray): IntSize {
            require(array.length() == 2) { "referenceSize 必须是 [宽, 高]" }
            return IntSize(array.getInt(0), array.getInt(1))
        }

        private val RECT_REGEX = Regex("""^\s*rect\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)\s*$""")

        private fun parseRect(text: String): IntRect {
            val m = RECT_REGEX.matchEntire(text) ?: throw IllegalArgumentException("无法解析矩形：$text，格式应为 rect(x, y, w, h)")
            val (x, y, w, h) = m.destructured
            return IntRect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
        }
    }
}

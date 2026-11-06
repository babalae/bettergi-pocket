package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.map.MapAssetSource
import org.json.JSONObject
import kotlin.math.sqrt

data class TpPoint(
    val id: Int,
    val type: String,
    val name: String,
    val country: String,
    val x: Double,
    val y: Double,
)

/**
 * `tp.json` 里 `X = position[2]`，`Y = position[0]`。来源：BGI `GiTpPosition`。
 */
object TpCatalog {
    const val FILE_NAME = "tp.json"

    val countryCenters: Map<String, Pair<Double, Double>> = mapOf(
        "蒙德" to (-876.0 to 2278.0),
        "璃月" to (270.0 to -666.0),
        "稻妻" to (-4400.0 to -3050.0),
        "须弥" to (2877.0 to -374.0),
        "枫丹" to (4515.0 to 3631.0),
        "纳塔" to (8973.5 to -1879.1),
        "挪德卡莱" to (9542.25 to 1661.84),
        "至冬" to (6755.051 to 9480.659),
    )

    fun load(source: MapAssetSource): List<TpPoint> = parse(source.readText(FILE_NAME))

    fun parse(json: String): List<TpPoint> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val points = ArrayList<TpPoint>()
        for (sceneIndex in 0 until data.length()) {
            val scene = data.getJSONObject(sceneIndex)
            if (!scene.optString("mapName").equals("Teyvat", ignoreCase = true)) continue
            val array = scene.optJSONArray("points") ?: continue
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val position = item.optJSONArray("position") ?: continue
                if (position.length() < 3) continue
                points += TpPoint(
                    id = item.optInt("id"),
                    type = item.optString("type"),
                    name = item.optString("name"),
                    country = item.optString("country"),
                    x = position.getDouble(2),
                    y = position.getDouble(0),
                )
            }
        }
        return points
    }

    fun nearest(points: List<TpPoint>, x: Double, y: Double, count: Int): List<TpPoint> {
        require(count >= 1)
        return points.sortedBy { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }.take(count)
    }

    /** 当前大地图中心离目标不足 50 个游戏单位时不切换地区。 */
    fun shouldSwitchCountry(centerX: Double, centerY: Double, targetX: Double, targetY: Double): Boolean {
        val dx = centerX - targetX
        val dy = centerY - targetY
        return sqrt(dx * dx + dy * dy) >= 50.0
    }

    fun normalizeAreaName(name: String): String {
        return name.replace("渊下宮", "渊下宫").replace("蒙徳", "蒙德").replace("娜塔", "纳塔")
            // TB322FC 地区名单高亮时，ML Kit 会把“莱”识别成“菜”。只修正完整地名。
            .replace("挪德卡菜", "挪德卡莱")
    }

    fun nameOverlap(candidate: String, target: String): Int {
        val left = normalizeCandidate(candidate)
        val right = normalizeCandidate(target)
        if (left.isEmpty() || right.isEmpty()) return 0
        return left.count { it in right }
    }

    /** 名称对不上时选 Y 最大的一条；有重合时取得分最高的。 */
    fun chooseCandidate(candidates: List<Pair<String, Double>>, targetName: String): Int {
        if (candidates.isEmpty()) return -1
        val target = normalizeCandidate(targetName)
        if (target.isEmpty()) return candidates.indices.maxBy { candidates[it].second }
        var best = 0
        var bestScore = -1
        candidates.forEachIndexed { index, candidate ->
            val score = nameOverlap(candidate.first, target)
            if (score > bestScore) {
                bestScore = score
                best = index
            }
        }
        if (bestScore <= 0) return candidates.indices.maxBy { candidates[it].second }
        return best
    }

    private fun normalizeCandidate(text: String): String {
        return text.filterNot { it.isWhitespace() || it == '-' || it == '－' || it == '·' }
    }
}

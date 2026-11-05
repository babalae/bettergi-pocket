package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.common.PointI
import com.bettergi.pocket.pathing.common.csRoundToInt
import com.bettergi.pocket.pathing.common.intersectCs
import com.bettergi.pocket.pathing.match.template.FastSqDiffMatcher
import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.EXACT_SEARCH_RADIUS
import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.EXACT_SIZE
import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.EXACT_ZOOM
import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.ROUGH_SEARCH_RADIUS
import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.ROUGH_SIZE
import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.ROUGH_ZOOM
import com.bettergi.pocket.pathing.match.template.TemplateMatchHelper
import org.json.JSONArray
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * TemplateMatch 分层地图：粗匹配用 1/5 尺寸彩图，精匹配用原尺寸灰度图。
 * 来源：BGI `GameTask/Common/Map/Maps/Base/BaseMapLayerByTemplateMatch.cs`。
 *
 * 「世界坐标」为原神游戏坐标（1024 级别）；图层像素坐标 = (Left - 世界坐标) × Scale / zoom。
 */
class BaseMapLayerByTemplateMatch(
    val layerGroupId: String,
    val layerId: String,
    val name: String,
    val scale: Float,
    val floor: Int,
    val top: Float,
    val left: Float,
    val isOverSize: Boolean,
) : AutoCloseable {
    /** 小尺寸彩图（粗匹配）。 */
    lateinit var coarseColorMatcher: FastSqDiffMatcher
        private set

    private lateinit var assets: MapAssetSource
    private lateinit var grayPath: String
    private var grayCache: GrayMapCache? = null
    private var ownGrayMap: Mat? = null

    /** 大尺寸灰度图（精匹配）。提供 [GrayMapCache] 时按需解码，否则首次访问时解码并常驻。 */
    val fineGrayMap: Mat
        get() {
            val cache = grayCache
            if (cache != null) return cache.get(this, ::decodeGray)
            synchronized(this) {
                return ownGrayMap ?: decodeGray().also { ownGrayMap = it }
            }
        }

    fun loadLayer(assets: MapAssetSource, layerDir: String, grayCache: GrayMapCache? = null) {
        val colorPath = "$layerDir/${layerId}_color.webp"
        val coarseColorMap = assets.decodeImage(colorPath, Imgcodecs.IMREAD_COLOR)
        try {
            if (coarseColorMap.empty()) throw IllegalStateException("彩色分层地图 $layerId 读取失败")
            coarseColorMatcher = FastSqDiffMatcher(coarseColorMap, Size(ROUGH_SIZE.toDouble(), ROUGH_SIZE.toDouble()))
        } finally {
            // FastSqDiffMatcher 已拆分出各通道，原图不再需要（BGI 交给 GC）
            coarseColorMap.release()
        }
        this.assets = assets
        this.grayPath = "$layerDir/${layerId}_gray${if (isOverSize) ".png" else ".webp"}"
        this.grayCache = grayCache
        // BGI 加载时即读取灰度图，缺失会直接报错；这里至少保证文件存在
        if (!assets.exists(grayPath)) throw IllegalStateException("灰度分层地图 $layerId 读取失败")
    }

    private fun decodeGray(): Mat {
        val gray = assets.decodeImage(grayPath, Imgcodecs.IMREAD_GRAYSCALE)
        if (gray.empty()) throw IllegalStateException("灰度分层地图 $layerId 读取失败")
        return gray
    }

    /** 全局粗匹配，返回 (世界坐标, 未加 TplSumSq 的匹配值)。 */
    fun roughMatch(maskedMiniMaps: List<Mat>, maskF: Mat): Pair<Point2f, Double> {
        val (pos, value) = coarseColorMatcher.match(maskedMiniMaps, maskF)
        return mapToWorld(Point2f(pos.x.toFloat(), pos.y.toFloat()), ROUGH_ZOOM.toFloat(), ROUGH_SIZE) to value
    }

    /** 以 [preLoc] 为中心的局部粗匹配；搜索窗被裁到小于模板时返回 (default, -1)。 */
    fun roughMatch(maskedMiniMaps: List<Mat>, maskF: Mat, preLoc: Point2f, channels: IntArray? = null): Pair<Point2f, Double> {
        val roughPos = worldToMap(preLoc, ROUGH_ZOOM.toFloat())
        val rect = getRect(roughPos, (ROUGH_SEARCH_RADIUS * scale).toInt(), ROUGH_SIZE)
            .intersectCs(Rect(0, 0, coarseColorMatcher.sourceWidth, coarseColorMatcher.sourceHeight))
        if (rect.width < ROUGH_SIZE || rect.height < ROUGH_SIZE) {
            return Point2f.DEFAULT to -1.0
        }
        val (pos, value) = coarseColorMatcher.match(maskedMiniMaps, maskF, rect, channels)
        return mapToWorld(Point2f((rect.x + pos.x).toFloat(), (rect.y + pos.y).toFloat()), ROUGH_ZOOM.toFloat(), ROUGH_SIZE) to value
    }

    /** 精确匹配，直接返回世界坐标。 */
    fun exactMatch(miniMap: Mat, mask: Mat, preLoc: Point2f, mode: Int = Imgproc.TM_SQDIFF): Pair<Point2f, Double> {
        val exactPos = worldToMap(preLoc, EXACT_ZOOM.toFloat())
        val grayMap = fineGrayMap
        val rect = getRect(exactPos, EXACT_SEARCH_RADIUS, EXACT_SIZE)
            .intersectCs(Rect(0, 0, grayMap.cols(), grayMap.rows()))
        if (rect.width < EXACT_SIZE || rect.height < EXACT_SIZE) {
            return Point2f.DEFAULT to -1.0
        }
        val bigMap = grayMap.submat(rect)
        try {
            val (pos, value) = TemplateMatchHelper.matchTemplateSubPix(bigMap, miniMap, mode, mask)
            return mapToWorld(Point2f(rect.x.toFloat() + pos.x, rect.y.toFloat() + pos.y), EXACT_ZOOM.toFloat(), EXACT_SIZE) to value
        } finally {
            bigMap.release()
        }
    }

    private fun getRect(loc: PointI, halfSide: Int, miniMapSize: Int): Rect {
        return Rect(
            loc.x - halfSide - miniMapSize / 2,
            loc.y - halfSide - miniMapSize / 2,
            halfSide * 2 + miniMapSize,
            halfSide * 2 + miniMapSize,
        )
    }

    private fun worldToMap(pos: Point2f, zoom: Float): PointI {
        return PointI(csRoundToInt((left - pos.x) * scale / zoom), csRoundToInt((top - pos.y) * scale / zoom))
    }

    private fun mapToWorld(pos: Point2f, zoom: Float, miniMapSize: Int): Point2f {
        return Point2f(
            left - (pos.x + miniMapSize / 2.0f) * zoom / scale,
            top - (pos.y + miniMapSize / 2.0f) * zoom / scale,
        )
    }

    override fun close() {
        if (this::coarseColorMatcher.isInitialized) coarseColorMatcher.close()
        synchronized(this) {
            ownGrayMap?.release()
            ownGrayMap = null
        }
    }

    companion object {
        /**
         * 读取 `Map/{地图}/` 顶层所有 `*.json` 描述文件并加载图层。
         * BGI 用 `Directory.GetFiles` 枚举，Windows 下为文件名字母序；这里按文件名不区分大小写排序，保证图层顺序一致
         * （图层顺序决定全局匹配并列时的取舍）。
         *
         * @param grayCache 精匹配灰度图缓存；为 null 时每个图层首次使用后常驻
         */
        fun loadLayers(assets: MapAssetSource, mapType: MapTypes, grayCache: GrayMapCache? = null): List<BaseMapLayerByTemplateMatch> {
            val layerDir = "Map/${mapType.name}"
            val jsonFiles = assets.list(layerDir)
                .filter { it.endsWith(".json", ignoreCase = true) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
            val layers = ArrayList<BaseMapLayerByTemplateMatch>()
            for (file in jsonFiles) {
                layers.addAll(parseLayers(assets.readText("$layerDir/$file")))
            }
            try {
                for (layer in layers) {
                    layer.loadLayer(assets, layerDir, grayCache)
                }
            } catch (e: Exception) {
                layers.forEach { it.close() }
                throw e
            }
            return layers
        }

        /** System.Text.Json 默认选项：属性名区分大小写，缺省字段取默认值。 */
        internal fun parseLayers(json: String): List<BaseMapLayerByTemplateMatch> {
            val array = JSONArray(json)
            return List(array.length()) { i ->
                val o = array.getJSONObject(i)
                BaseMapLayerByTemplateMatch(
                    layerGroupId = o.optString("LayerGroupId", ""),
                    layerId = o.optString("LayerId", ""),
                    name = o.optString("Name", ""),
                    scale = o.optDouble("Scale", 1.0).toFloat(),
                    floor = o.optInt("Floor", 0),
                    top = o.optDouble("Top", 0.0).toFloat(),
                    left = o.optDouble("Left", 0.0).toFloat(),
                    isOverSize = o.optBoolean("IsOverSize", false),
                )
            }
        }
    }
}

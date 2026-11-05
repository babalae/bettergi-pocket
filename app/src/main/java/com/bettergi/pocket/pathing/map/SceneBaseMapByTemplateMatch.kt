package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.match.template.MatchContext
import com.bettergi.pocket.pathing.match.template.MiniMapMatchConfig.CONFIDENCE_THRESHOLDS
import com.bettergi.pocket.pathing.minimap.MiniMapPreprocessor
import com.bettergi.pocket.recognition.IntSize
import org.opencv.core.Mat

/**
 * 小地图 TemplateMatch 定位（BGI 默认的地图匹配方式）。
 * 来源：BGI `GameTask/Common/Map/Maps/Base/SceneBaseMapByTemplateMatch.cs`。
 *
 * 流程：预处理（掩膜 + 视角）→ 粗匹配（52×52 彩图，全局或 ±250 游戏单位）→ 精匹配（260×260 灰度，亚像素）。
 * 置信度阈值按 rank 取 0.99 / 0.97 / 0.95。返回 2048 级别图像坐标，失败返回 default。
 * 不是线程安全的。
 */
abstract class SceneBaseMapByTemplateMatch(
    type: MapTypes,
    mapSize: IntSize,
    mapOriginInImageCoordinate: Point2f,
    mapImageBlockWidth: Int,
    splitRow: Int,
    splitCol: Int,
    assets: MapAssetSource,
) : SceneBaseMap(type, mapSize, mapOriginInImageCoordinate, mapImageBlockWidth, splitRow, splitCol, assets) {
    private val miniMapPreprocessor = MiniMapPreprocessor()

    /** 精匹配灰度图按需解码，见 [GrayMapCache]。 */
    val grayMapCache = GrayMapCache()

    @Volatile
    private var loadedTemplateLayers: List<BaseMapLayerByTemplateMatch> = emptyList()
    private val templateLayersLock = Any()

    /** 模板匹配图层，首次访问时加载（BGI 中以 `new` 隐藏了基类的 SIFT `Layers`）。 */
    val templateLayers: List<BaseMapLayerByTemplateMatch>
        get() {
            if (loadedTemplateLayers.isEmpty()) {
                synchronized(templateLayersLock) {
                    if (loadedTemplateLayers.isEmpty()) {
                        log("[TemplateMatch]地图模板加载中")
                        loadedTemplateLayers = BaseMapLayerByTemplateMatch.loadLayers(assets, type, grayMapCache)
                        log("地图模板加载完成，图层数 ${loadedTemplateLayers.size}")
                    }
                }
            }
            return loadedTemplateLayers
        }

    /** 上一次成功的匹配结果（C# 中为值类型 struct，这里存副本）。 */
    var prevSuccessResult: MatchResult = MatchResult()

    class MatchResult(
        var layer: BaseMapLayerByTemplateMatch? = null,
        var mapPos: Point2f = Point2f.DEFAULT,
        var confidence: Double = 0.0,
    ) {
        fun isSuccess(rank: Int): Boolean {
            val index = rank.coerceIn(0, CONFIDENCE_THRESHOLDS.size - 1)
            return confidence <= 1 && confidence >= CONFIDENCE_THRESHOLDS[index].toDouble()
        }

        fun copy(): MatchResult = MatchResult(layer, mapPos, confidence)
    }

    override fun warmUp() {
        templateLayers
    }

    override fun getMiniMapPosition(miniMapMat: Mat): Point2f {
        val result = MatchResult()
        val (miniMap, mask) = miniMapPreprocessor.getMiniMapAndMask(miniMapMat)
        try {
            globalMatch(miniMap, mask, result)
            return updateResult(result, 2)
        } finally {
            miniMap.release()
            mask.release()
        }
    }

    /** 小地图局部匹配，失败不进行全局匹配。 */
    override fun getMiniMapPosition(miniMapMat: Mat, prevX: Float, prevY: Float): Point2f {
        return getMiniMapPosition(miniMapMat, prevX, prevY, 2)
    }

    fun getMiniMapPosition(miniMapMat: Mat, prevX: Float, prevY: Float, rank: Int): Point2f {
        if (prevX <= 0 || prevY <= 0) {
            return getMiniMapPosition(miniMapMat)
        }
        val curResult = MatchResult()
        val (miniMap, mask) = miniMapPreprocessor.getMiniMapAndMask(miniMapMat)
        try {
            val pos = convertImageCoordinatesToGenshinMapCoordinates(Point2f(prevX, prevY))!!
            localMatch(miniMap, mask, pos, curResult)
            return updateResult(curResult, rank)
        } finally {
            miniMap.release()
            mask.release()
        }
    }

    private fun updateResult(result: MatchResult, rank: Int): Point2f {
        if (!result.isSuccess(rank)) return Point2f.DEFAULT
        prevSuccessResult = result.copy()
        return convertGenshinMapCoordinatesToImageCoordinates(prevSuccessResult.mapPos)
    }

    // region 模板匹配

    fun globalMatch(miniMap: Mat, mask: Mat, result: MatchResult) {
        MatchContext(miniMap, mask).use { context ->
            roughMatchGlobal(context, result)
            exactMatch(context, result)
        }
    }

    /** 局部匹配：在上一次匹配位置附近搜索。 */
    fun localMatch(miniMap: Mat, mask: Mat, pos: Point2f, result: MatchResult) {
        MatchContext(miniMap, mask).use { context ->
            roughMatchLocal(context, pos, result)
            exactMatch(context, result)
        }
    }

    fun roughMatchGlobal(context: MatchContext, result: MatchResult) {
        for (layer in templateLayers) {
            val (tempPos, tempVal) = layer.roughMatch(context.maskedMiniMapRoughs, context.maskRoughF)
            if (!context.normalizerRough.update(tempVal + context.tplSumSq)) continue
            result.layer = layer
            result.mapPos = tempPos
        }
        result.confidence = context.normalizerRough.confidence()
    }

    fun roughMatchLocal(context: MatchContext, pos: Point2f, result: MatchResult) {
        result.mapPos = pos
        if (prevSuccessResult.mapPos == pos) {
            result.layer = prevSuccessResult.layer
        }
        val preferred = result.layer
        if (preferred != null) {
            val (tempPos, tempVal) = preferred.roughMatch(context.maskedMiniMapRoughs, context.maskRoughF, pos)
            if (!tempPos.isDefault && context.normalizerRough.update(tempVal + context.tplSumSq)) {
                result.mapPos = tempPos
                result.confidence = context.normalizerRough.confidence()
            }
        }

        if (result.isSuccess(0)) {
            return
        }

        var flag = false
        val candidates = if (result.layer == null) {
            templateLayers
        } else {
            templateLayers.filter { it !== prevSuccessResult.layer }
        }
        for (layer in candidates) {
            val (tempPos, tempVal) = layer.roughMatch(context.maskedMiniMapRoughs, context.maskRoughF, pos)
            if (tempPos.isDefault || !context.normalizerRough.update(tempVal + context.tplSumSq)) continue
            result.layer = layer
            result.mapPos = tempPos
            flag = true
        }
        if (flag) result.confidence = context.normalizerRough.confidence()
    }

    fun exactMatch(context: MatchContext, result: MatchResult) {
        val layer = result.layer
        if (layer == null || !result.isSuccess(2)) return
        val (tempPos, tempVal) = layer.exactMatch(context.miniMapExact, context.maskExact, result.mapPos)
        if (!tempPos.isDefault && context.normalizerExact.update(tempVal)) {
            result.mapPos = tempPos
            result.confidence = context.normalizerExact.confidence()
        } else {
            result.confidence = 0.0
        }
    }

    // endregion

    override fun release() {
        synchronized(templateLayersLock) {
            loadedTemplateLayers.forEach { it.close() }
            loadedTemplateLayers = emptyList()
            grayMapCache.clear()
            prevSuccessResult = MatchResult()
        }
        super.release()
    }
}

package com.bettergi.pocket.pathing.map

import android.util.Log
import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.common.centerPointCs
import com.bettergi.pocket.pathing.common.csRoundToInt
import com.bettergi.pocket.pathing.common.isDefault
import com.bettergi.pocket.pathing.match.feature.DescriptorMatcherType
import com.bettergi.pocket.pathing.match.feature.SiftMatcher
import com.bettergi.pocket.recognition.IntSize
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import kotlin.math.max
import kotlin.math.min

/**
 * 场景地图基类：SIFT 匹配与坐标系转换。
 * 来源：BGI `GameTask/Common/Map/Maps/Base/SceneBaseMap.cs`。
 *
 * @param mapSize 地图图像大小，仅用于切割特征点
 * @param mapOriginInImageCoordinate 游戏坐标原点在图像坐标中的位置
 * @param mapImageBlockWidth 特征地图图像块大小（2048 或 1024），游戏坐标是 1024 级别
 */
abstract class SceneBaseMap(
    val type: MapTypes,
    val mapSize: IntSize,
    val mapOriginInImageCoordinate: Point2f,
    mapImageBlockWidth: Int,
    val splitRow: Int,
    val splitCol: Int,
    protected val assets: MapAssetSource,
) : SceneMap {
    /** 图像块大小 / 1024，用于坐标系转换。 */
    private val mapImageBlockWidthScale: Float = mapImageBlockWidth / 1024f

    @Volatile
    private var siftLayers: List<BaseMapLayer> = emptyList()
    private val layersLock = Any()

    /** 分层 SIFT 特征，0 是主地图。首次访问时加载（BGI 中为 `Layers`）。 */
    val layers: List<BaseMapLayer>
        get() {
            if (siftLayers.isEmpty()) {
                synchronized(layersLock) {
                    if (siftLayers.isEmpty()) {
                        log("[SIFT]地图特征点加载中")
                        siftLayers = BaseMapLayer.loadLayers(this, assets)
                        log("地图特征点加载完成，层数 ${siftLayers.size}")
                    }
                }
            }
            return siftLayers
        }

    protected val mainLayer: BaseMapLayer get() = layers[0]

    /** BGI 中为 `Feature2DFactory.Get(SIFT)` 全局单例；这里每张地图一个实例。 */
    val siftMatcher = SiftMatcher()

    override fun warmUp() {
        layers
    }

    override fun getBigMapPosition(greyBigMapMat: Mat): Point2f {
        val descriptors = mainLayer.trainDescriptors.floatMat()
        try {
            return siftMatcher.match(mainLayer.trainKeyPoints, descriptors, greyBigMapMat)
        } finally {
            mainLayer.trainDescriptors.releaseIfTemporary(descriptors)
        }
    }

    override fun getBigMapPosition(greyBigMapMat: Mat, expectedCenter: Point2f): Point2f {
        val layer = mainLayer
        if (!isValidPoint(expectedCenter) || layer.splitBlocks.isEmpty() || layer.splitBlocks[0].isEmpty()) {
            return getBigMapPosition(greyBigMapMat)
        }
        val searchRect = buildLocalSearchRect(expectedCenter, Size(greyBigMapMat.cols().toDouble(), greyBigMapMat.rows().toDouble()), mapSize)
        val result = siftMatcher.knnMatchLocal(
            layer.splitBlocks,
            layer.trainKeyPoints,
            layer.trainDescriptors,
            mapSize,
            searchRect,
            greyBigMapMat,
        )
        return if (result.isDefault) getBigMapPosition(greyBigMapMat) else result
    }

    override fun getBigMapRect(greyBigMapMat: Mat): Rect {
        val descriptors = mainLayer.trainDescriptors.floatMat()
        try {
            return siftMatcher.knnMatchRect(mainLayer.trainKeyPoints, descriptors, greyBigMapMat)
        } finally {
            mainLayer.trainDescriptors.releaseIfTemporary(descriptors)
        }
    }

    /** 全局匹配：从表到里逐层，FLANN。 */
    override fun getMiniMapPosition(miniMapMat: Mat): Point2f {
        for (layer in layers) {
            try {
                val descriptors = layer.trainDescriptors.floatMat()
                try {
                    val result = siftMatcher.knnMatch(layer.trainKeyPoints, descriptors, miniMapMat)
                    if (!result.isDefault) return result
                } finally {
                    layer.trainDescriptors.releaseIfTemporary(descriptors)
                }
            } catch (e: Exception) {
                log("地图${type}层数${layer.floor},特征匹配失败:${e.message}")
            }
        }
        return Point2f.DEFAULT
    }

    /** 局部匹配：取上一位置所在网格的 3×3 邻域，BruteForce。失败不回退全局。 */
    override fun getMiniMapPosition(miniMapMat: Mat, prevX: Float, prevY: Float): Point2f {
        if (prevX <= 0 && prevY <= 0) {
            return getMiniMapPosition(miniMapMat)
        }
        for (layer in layers) {
            try {
                val result = if (splitRow > 0 || splitCol > 0) {
                    layer.withChosenBlocks(prevX, prevY) { keyPoints, descriptors ->
                        siftMatcher.knnMatch(keyPoints, descriptors, miniMapMat, null, DescriptorMatcherType.BruteForce)
                    }
                } else {
                    val descriptors = layer.trainDescriptors.floatMat()
                    try {
                        siftMatcher.knnMatch(layer.trainKeyPoints, descriptors, miniMapMat, null, DescriptorMatcherType.BruteForce)
                    } finally {
                        layer.trainDescriptors.releaseIfTemporary(descriptors)
                    }
                }
                if (!result.isDefault) return result
            } catch (e: Exception) {
                log("地图${type}层数${layer.floor},特征匹配失败:${e.message}")
            }
        }
        return Point2f.DEFAULT
    }

    // region 坐标系转换

    override fun convertImageCoordinatesToGenshinMapCoordinates(imageCoordinates: Point2f): Point2f? {
        if (imageCoordinates.x == 0f && imageCoordinates.y == 0f) {
            return null
        }
        // 原神坐标系是 1024 级别的，图像坐标系不是 1024 级别时要做转换
        return Point2f(
            (mapOriginInImageCoordinate.x - imageCoordinates.x) / mapImageBlockWidthScale,
            (mapOriginInImageCoordinate.y - imageCoordinates.y) / mapImageBlockWidthScale,
        )
    }

    override fun convertImageCoordinatesToGenshinMapCoordinates(rect: Rect): Rect? {
        if (rect.isDefault) {
            return null
        }
        val center = rect.centerPointCs()
        val p = convertImageCoordinatesToGenshinMapCoordinates(Point2f(center.x.toFloat(), center.y.toFloat())) ?: return null
        return Rect(
            (p.x - rect.width / 2f / mapImageBlockWidthScale).toInt(),
            (p.y - rect.height / 2f / mapImageBlockWidthScale).toInt(),
            (rect.width / mapImageBlockWidthScale).toInt(),
            (rect.height / mapImageBlockWidthScale).toInt(),
        )
    }

    override fun convertGenshinMapCoordinatesToImageCoordinates(genshinMapCoordinates: Point2f?): Point2f {
        val p = genshinMapCoordinates ?: return Point2f.DEFAULT
        return Point2f(
            mapOriginInImageCoordinate.x - p.x * mapImageBlockWidthScale,
            mapOriginInImageCoordinate.y - p.y * mapImageBlockWidthScale,
        )
    }

    override fun convertGenshinMapCoordinatesToImageCoordinates(genshinMapRect: Rect?): Rect {
        val rect = genshinMapRect ?: return Rect(0, 0, 0, 0)
        val center = rect.centerPointCs()
        val c = convertGenshinMapCoordinatesToImageCoordinates(Point2f(center.x.toFloat(), center.y.toFloat()))
        return Rect(
            csRoundToInt(c.x - rect.width / 2f * mapImageBlockWidthScale),
            csRoundToInt(c.y - rect.height / 2f * mapImageBlockWidthScale),
            csRoundToInt(rect.width * mapImageBlockWidthScale),
            csRoundToInt(rect.height * mapImageBlockWidthScale),
        )
    }

    // endregion

    override fun release() {
        synchronized(layersLock) {
            siftLayers.forEach { it.close() }
            siftLayers = emptyList()
        }
    }

    protected fun log(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
            // JVM 单测中 android.util.Log 未实现
        }
    }

    companion object {
        private const val TAG = "BetterGI.Map"

        internal fun isValidPoint(point: Point2f): Boolean {
            return point.x.isFinite() && point.y.isFinite() &&
                (kotlin.math.abs(point.x) > Float.MIN_VALUE || kotlin.math.abs(point.y) > Float.MIN_VALUE)
        }

        /** 以预期中心为中心、至少两倍查询图（且不小于训练图 1/4）的搜索矩形。 */
        internal fun buildLocalSearchRect(center: Point2f, querySize: Size, trainImageSize: IntSize): Rect {
            val width = min(trainImageSize.width.toDouble(), max(querySize.width.toInt() * 2.0, trainImageSize.width / 4.0))
            val height = min(trainImageSize.height.toDouble(), max(querySize.height.toInt() * 2.0, trainImageSize.height / 4.0))
            val rectWidth = csRoundToInt(width)
            val rectHeight = csRoundToInt(height)
            val x = csRoundToInt(center.x - rectWidth / 2.0).coerceIn(0, max(0, trainImageSize.width - rectWidth))
            val y = csRoundToInt(center.y - rectHeight / 2.0).coerceIn(0, max(0, trainImageSize.height - rectHeight))
            return Rect(x, y, rectWidth, rectHeight)
        }
    }
}

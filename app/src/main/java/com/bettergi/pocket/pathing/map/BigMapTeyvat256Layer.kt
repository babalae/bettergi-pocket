package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.match.feature.DescriptorMatrix
import com.bettergi.pocket.pathing.match.feature.FeatureStorage
import com.bettergi.pocket.pathing.match.feature.KeyPointFeatureBlock
import com.bettergi.pocket.pathing.match.feature.KeyPointFeatureBlockHelper
import com.bettergi.pocket.pathing.match.feature.KeyPointPoints
import com.bettergi.pocket.pathing.match.feature.SiftMatcher
import com.bettergi.pocket.recognition.IntSize
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * 提瓦特大地图 SIFT 图层（256 级别，相对 2048 缩小 8 倍）。传送时识别大地图中心与视野矩形。
 * 来源：BGI `GameTask/Common/Map/Maps/Layer/BigMapTeyvat256Layer.cs`。
 *
 * 输入为整帧灰度图（BGI 为 1080p 截图），内部先缩小到 1/4 再匹配。
 * BGI 中还有 `GetBigMapRect(grey, prevRect)` 重载，传送流程没有用到，未移植。
 */
class BigMapTeyvat256Layer private constructor(baseMap: SceneBaseMap, assets: MapAssetSource) : AutoCloseable {
    val trainKeyPoints: KeyPointPoints
    val trainDescriptors: DescriptorMatrix
    val splitBlocks: Array<Array<KeyPointFeatureBlock?>>
    private val mapSize256 = IntSize(baseMap.mapSize.width / BIG_MAP_256_SCALE_TO_2048, baseMap.mapSize.height / BIG_MAP_256_SCALE_TO_2048)
    private val siftMatcher: SiftMatcher = baseMap.siftMatcher

    init {
        val layerDir = "Map/${baseMap.type.name}"
        trainKeyPoints = FeatureStorage.loadKeyPoints(assets, "$layerDir/Teyvat_0_256_SIFT.kp.bin")
        // 4 万个描述子，float 约 21 MiB，常驻
        trainDescriptors = FeatureStorage.loadDescriptors(assets, "$layerDir/Teyvat_0_256_SIFT.mat.png", cacheFloat = true)
        splitBlocks = KeyPointFeatureBlockHelper.splitFeatures(
            mapSize256,
            TeyvatMap.GAME_MAP_ROWS * 4,
            TeyvatMap.GAME_MAP_COLS * 4,
            trainKeyPoints,
        )
    }

    fun getBigMapRect(greyBigMapMat: Mat): Rect {
        val resized = resize(greyBigMapMat, 1.0 / 4)
        try {
            return siftMatcher.knnMatchRect(trainKeyPoints, trainDescriptors.floatMat(), resized)
        } finally {
            if (resized !== greyBigMapMat) resized.release()
        }
    }

    /** 无先验中心时的位置识别（BGI `TeyvatMap.GetBigMapPosition(Mat)`）。 */
    fun getBigMapPosition(greyBigMapMat: Mat): Point2f {
        val resized = resize(greyBigMapMat, 1.0 / 4)
        try {
            return siftMatcher.match(trainKeyPoints, trainDescriptors.floatMat(), resized)
        } finally {
            if (resized !== greyBigMapMat) resized.release()
        }
    }

    /** @param expectedCenter 256 级别图像坐标 */
    fun getBigMapPosition(greyBigMapMat: Mat, expectedCenter: Point2f): Point2f {
        val resized = resize(greyBigMapMat, 1.0 / 4)
        try {
            if (!isValidPoint(expectedCenter) || splitBlocks.isEmpty() || splitBlocks[0].isEmpty()) {
                return siftMatcher.match(trainKeyPoints, trainDescriptors.floatMat(), resized)
            }
            val searchRect = SceneBaseMap.buildLocalSearchRect(
                expectedCenter,
                Size(resized.cols().toDouble(), resized.rows().toDouble()),
                mapSize256,
            )
            val result = siftMatcher.knnMatchLocal(splitBlocks, trainKeyPoints, trainDescriptors, mapSize256, searchRect, resized)
            return if (result.isDefault) siftMatcher.match(trainKeyPoints, trainDescriptors.floatMat(), resized) else result
        } finally {
            if (resized !== greyBigMapMat) resized.release()
        }
    }

    override fun close() {
        trainDescriptors.close()
    }

    companion object {
        /** 大地图使用 256 级别，相对 2048 区块的缩放比例：2048 / 256 = 8。 */
        const val BIG_MAP_256_SCALE_TO_2048 = 8

        @Volatile
        private var instance: BigMapTeyvat256Layer? = null

        /** BGI 为进程级单例。 */
        fun getInstance(baseMap: SceneBaseMap, assets: MapAssetSource): BigMapTeyvat256Layer {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return BigMapTeyvat256Layer(baseMap, assets).also { instance = it }
            }
        }

        fun releaseInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        private fun isValidPoint(point: Point2f): Boolean {
            return point.x.isFinite() && point.y.isFinite() &&
                (abs(point.x) > Float.MIN_VALUE || abs(point.y) > Float.MIN_VALUE)
        }

        /**
         * BGI `ResizeHelper.Resize(src, scale)`：比例为 1 时返回原图；
         * 目标尺寸为 `(int)(w × scale)`，线性插值。
         */
        internal fun resize(src: Mat, scale: Double): Mat {
            if (abs(scale - 1) <= 0.00001) return src
            val dst = Mat()
            Imgproc.resize(src, dst, Size((src.cols() * scale).toInt().toDouble(), (src.rows() * scale).toInt().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            return dst
        }
    }
}

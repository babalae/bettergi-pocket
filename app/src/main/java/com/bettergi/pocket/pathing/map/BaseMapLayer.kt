package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.match.feature.DescriptorMatrix
import com.bettergi.pocket.pathing.match.feature.FeatureStorage
import com.bettergi.pocket.pathing.match.feature.KeyPointFeatureBlock
import com.bettergi.pocket.pathing.match.feature.KeyPointFeatureBlockHelper
import com.bettergi.pocket.pathing.match.feature.KeyPointPoints
import com.bettergi.pocket.pathing.match.feature.MergedFeatures
import org.opencv.core.Mat

/**
 * SIFT 分层地图。
 * 来源：BGI `GameTask/Common/Map/Maps/Base/BaseMapLayer.cs`。
 *
 * 资源路径 `Map/{地图}/`：
 * - `Teyvat_0_2048_SIFT.kp.bin` / `Teyvat_0_2048_SIFT.mat.png`（第二段数字为层号）
 * - `Teyvat_0_256_SIFT.*` 是大地图专用，不在这里加载
 */
class BaseMapLayer(private val baseMap: SceneBaseMap) : AutoCloseable {
    var floor: Int = 0
        internal set

    lateinit var trainKeyPoints: KeyPointPoints
        internal set

    lateinit var trainDescriptors: DescriptorMatrix
        internal set

    /** 切割后的特征块，下标 [行][列]。 */
    var splitBlocks: Array<Array<KeyPointFeatureBlock?>> = emptyArray()
        internal set

    /** 最近一次合并的特征块，中心网格不变时复用。 */
    private var lastMergedBlock: MergedFeatures? = null

    /**
     * 选择上一位置所在网格的 3×3 邻域特征（BGI `ChooseBlocks`），并交给 [action] 使用。
     * 未切块时使用全部特征。
     */
    @Synchronized
    fun <T> withChosenBlocks(prevX: Float, prevY: Float, action: (KeyPointPoints, Mat) -> T): T {
        if (baseMap.splitRow <= 0 || baseMap.splitCol <= 0 || splitBlocks.isEmpty()) {
            val descriptors = trainDescriptors.floatMat()
            try {
                return action(trainKeyPoints, descriptors)
            } finally {
                trainDescriptors.releaseIfTemporary(descriptors)
            }
        }
        val (cellRow, cellCol) = KeyPointFeatureBlockHelper.getCellIndex(baseMap.mapSize, baseMap.splitRow, baseMap.splitCol, prevX, prevY)
        val cached = lastMergedBlock
        val merged = if (cached == null || cached.mergedCenterCellRow != cellRow || cached.mergedCenterCellCol != cellCol) {
            cached?.close()
            KeyPointFeatureBlockHelper.mergeNeighboringFeatures(splitBlocks, trainKeyPoints, trainDescriptors, cellRow, cellCol)
                .also { lastMergedBlock = it }
        } else {
            cached
        }
        return action(merged.keyPoints, merged.descriptor)
    }

    @Synchronized
    override fun close() {
        lastMergedBlock?.close()
        lastMergedBlock = null
        if (this::trainDescriptors.isInitialized) {
            trainDescriptors.close()
        }
    }

    companion object {
        private const val BIG_MAP_KP = "Teyvat_0_256_SIFT.kp.bin"
        private const val BIG_MAP_MAT = "Teyvat_0_256_SIFT.mat.png"

        fun loadLayers(baseMap: SceneBaseMap, assets: MapAssetSource): List<BaseMapLayer> {
            val layerDir = "Map/${baseMap.type.name}"
            val files = assets.list(layerDir)
            if (files.isEmpty()) {
                return emptyList()
            }
            val validFiles = files.filter {
                (it.endsWith(".kp.bin") || it.endsWith(".mat.png")) && it != BIG_MAP_KP && it != BIG_MAP_MAT
            }
            // 按层号分组。C# GetFileNameWithoutExtension("Teyvat_0_2048_SIFT.kp.bin") = "Teyvat_0_2048_SIFT.kp"
            val grouped = validFiles.groupBy { file ->
                val parts = file.substringBeforeLast('.').split('_')
                if (parts.size < 3) throw IllegalStateException("分层地图特征文件名称有错误，无法解析层级: $file")
                parts[1].toIntOrNull() ?: throw IllegalStateException("分层地图特征文件名称有错误，无法解析层级: $file")
            }

            val layers = ArrayList<BaseMapLayer>()
            for ((floor, group) in grouped) {
                val kpFile = group.first { it.endsWith(".kp.bin") }
                val matFile = group.first { it.endsWith(".mat.png") }
                val layer = BaseMapLayer(baseMap)
                layer.floor = floor
                layer.trainKeyPoints = FeatureStorage.loadKeyPoints(assets, "$layerDir/$kpFile")
                // 2048 图层描述子 float 版约 357 MiB，不缓存，全局匹配时临时转换
                layer.trainDescriptors = FeatureStorage.loadDescriptors(assets, "$layerDir/$matFile", cacheFloat = false)
                if (baseMap.splitRow > 0 || baseMap.splitCol > 0) {
                    layer.splitBlocks = KeyPointFeatureBlockHelper.splitFeatures(
                        baseMap.mapSize,
                        baseMap.splitRow,
                        baseMap.splitCol,
                        layer.trainKeyPoints,
                    )
                }
                layers.add(layer)
            }
            // 按 0, -1, -2 的顺序排列
            layers.sortWith { a, b -> b.floor.compareTo(a.floor) }
            return layers
        }
    }
}

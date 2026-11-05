package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.recognition.IntSize
import org.opencv.core.Mat
import org.opencv.core.Rect

/**
 * 提瓦特大陆，小地图使用 SIFT 匹配（路线 `map_match_method = "SIFT"`）。
 * 来源：BGI `GameTask/Common/Map/Maps/TeyvatMap.cs`。
 */
class TeyvatMap(assets: MapAssetSource) : SceneBaseMap(
    type = MapTypes.Teyvat,
    mapSize = MAP_SIZE,
    mapOriginInImageCoordinate = MAP_ORIGIN,
    mapImageBlockWidth = TEYVAT_MAP_IMAGE_BLOCK_WIDTH,
    splitRow = GAME_MAP_ROWS * 2,
    splitCol = GAME_MAP_COLS * 2,
    assets = assets,
) {
    override fun getBigMapPosition(greyBigMapMat: Mat): Point2f =
        BigMapTeyvat256Layer.getInstance(this, assets).getBigMapPosition(greyBigMapMat)

    /** @param expectedCenter 2048 级别图像坐标 */
    override fun getBigMapPosition(greyBigMapMat: Mat, expectedCenter: Point2f): Point2f =
        BigMapTeyvat256Layer.getInstance(this, assets).getBigMapPosition(greyBigMapMat, toBigMap256(expectedCenter))

    override fun getBigMapRect(greyBigMapMat: Mat): Rect =
        BigMapTeyvat256Layer.getInstance(this, assets).getBigMapRect(greyBigMapMat)

    companion object {
        // 每次地图扩展需要更新的参数
        /** 游戏解包大地图的行数 */
        const val GAME_MAP_ROWS = 19

        /** 游戏解包大地图的列数 */
        const val GAME_MAP_COLS = 22

        /** 地图原点所在块的行（原点在块的右下角） */
        const val GAME_MAP_UP_ROWS = 11

        /** 地图原点所在块的列（原点在块的右下角） */
        const val GAME_MAP_LEFT_COLS = 15

        const val TEYVAT_MAP_IMAGE_BLOCK_WIDTH = 2048

        val MAP_SIZE = IntSize(GAME_MAP_COLS * TEYVAT_MAP_IMAGE_BLOCK_WIDTH, GAME_MAP_ROWS * TEYVAT_MAP_IMAGE_BLOCK_WIDTH)

        val MAP_ORIGIN = Point2f(
            ((GAME_MAP_LEFT_COLS + 1) * TEYVAT_MAP_IMAGE_BLOCK_WIDTH).toFloat(),
            ((GAME_MAP_UP_ROWS + 1) * TEYVAT_MAP_IMAGE_BLOCK_WIDTH).toFloat(),
        )

        /** 大地图 SIFT 结果为 256 级别，乘以该值得到 2048 级别图像坐标。 */
        const val BIG_MAP_256_SCALE_TO_2048 = BigMapTeyvat256Layer.BIG_MAP_256_SCALE_TO_2048

        internal fun toBigMap256(expectedCenter2048: Point2f): Point2f {
            return Point2f(expectedCenter2048.x / BIG_MAP_256_SCALE_TO_2048, expectedCenter2048.y / BIG_MAP_256_SCALE_TO_2048)
        }
    }
}

/**
 * 提瓦特大陆，小地图使用 TemplateMatch 匹配（BGI 默认，类名 `TeyvatMapTest`）。
 * 来源：BGI `GameTask/Common/Map/Maps/TeyvatMapTest.cs`。
 */
class TeyvatTemplateMatchMap(assets: MapAssetSource) : SceneBaseMapByTemplateMatch(
    type = MapTypes.Teyvat,
    mapSize = TeyvatMap.MAP_SIZE,
    mapOriginInImageCoordinate = TeyvatMap.MAP_ORIGIN,
    mapImageBlockWidth = TeyvatMap.TEYVAT_MAP_IMAGE_BLOCK_WIDTH,
    splitRow = TeyvatMap.GAME_MAP_ROWS * 2,
    splitCol = TeyvatMap.GAME_MAP_COLS * 2,
    assets = assets,
) {
    override fun getBigMapPosition(greyBigMapMat: Mat): Point2f =
        BigMapTeyvat256Layer.getInstance(this, assets).getBigMapPosition(greyBigMapMat)

    override fun getBigMapPosition(greyBigMapMat: Mat, expectedCenter: Point2f): Point2f =
        BigMapTeyvat256Layer.getInstance(this, assets).getBigMapPosition(greyBigMapMat, TeyvatMap.toBigMap256(expectedCenter))

    override fun getBigMapRect(greyBigMapMat: Mat): Rect =
        BigMapTeyvat256Layer.getInstance(this, assets).getBigMapRect(greyBigMapMat)
}

package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.common.Point2f
import org.opencv.core.Mat
import org.opencv.core.Rect

/**
 * 来源：BGI `GameTask/Common/Map/Maps/Base/MapTypes.cs`。第一版只支持 [Teyvat]。
 */
enum class MapTypes(val description: String) {
    Teyvat("提瓦特大陆"),
    TheChasm("层岩巨渊"),
    Enkanomiya("渊下宫"),
    SeaOfBygoneEras("旧日之海"),
    AncientSacredMountain("远古圣山"),
    TempleOfSpace("星之空间"),
    MoonCanon("霜月"),
    ;

    companion object {
        /** C# `Enum.TryParse(name, ignoreCase: true)`。 */
        fun parseFromName(name: String): MapTypes {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("无法找到名称为 '$name' 的枚举值")
        }

        fun parseFromDescription(description: String): MapTypes {
            return entries.firstOrNull { it.description == description }
                ?: throw IllegalArgumentException("无法找到描述为 '$description' 的枚举值")
        }
    }
}

/**
 * 场景地图接口。所有位置均为「地图图像坐标」（提瓦特为 2048 级别），与 BGI 一致；
 * 与原神游戏坐标的换算见 [convertImageCoordinatesToGenshinMapCoordinates]。
 * 来源：BGI `GameTask/Common/Map/Maps/Base/ISceneMap.cs`。
 */
interface SceneMap {
    fun warmUp()

    /** 大地图中心在图像坐标中的位置（提瓦特为 256 级别图像坐标）。 */
    fun getBigMapPosition(greyBigMapMat: Mat): Point2f

    /** 根据预期中心缩小匹配范围。[expectedCenter] 为 2048 级别图像坐标。 */
    fun getBigMapPosition(greyBigMapMat: Mat, expectedCenter: Point2f): Point2f

    /** 大地图视野矩形（提瓦特为 256 级别图像坐标）。 */
    fun getBigMapRect(greyBigMapMat: Mat): Rect

    /**
     * 小地图全局匹配。
     * @param miniMapMat 212×212 BGR 小地图（BGI 参数名为 greyMiniMapMat，实际传入彩图）
     */
    fun getMiniMapPosition(miniMapMat: Mat): Point2f

    /** 小地图局部匹配：以上一次位置缩小匹配范围。 */
    fun getMiniMapPosition(miniMapMat: Mat, prevX: Float, prevY: Float): Point2f

    fun convertImageCoordinatesToGenshinMapCoordinates(imageCoordinates: Point2f): Point2f?

    fun convertImageCoordinatesToGenshinMapCoordinates(rect: Rect): Rect?

    fun convertGenshinMapCoordinatesToImageCoordinates(genshinMapCoordinates: Point2f?): Point2f

    fun convertGenshinMapCoordinatesToImageCoordinates(genshinMapRect: Rect?): Rect

    /** 释放地图数据占用的原生内存（BGI 常驻不释放；手机上在任务结束时调用）。 */
    fun release()
}

package com.bettergi.pocket.pathing.navigation

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.map.MapManager
import com.bettergi.pocket.pathing.map.MapTypes
import com.bettergi.pocket.pathing.map.SceneBaseMapByTemplateMatch
import org.opencv.core.Mat
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 位置识别（小地图定位）的静态入口。
 * 来源：BGI `GameTask/AutoPathing/Navigation.cs` 与 `NavigationInstance.cs`。
 *
 * 与 BGI 的区别（结构调整，逻辑不变）：BGI 传入整帧 `ImageRegion` 再按 1080p 裁小地图；
 * 这里传入 [com.bettergi.pocket.pathing.minimap.MiniMapLocator] 输出的 212×212 小地图。
 * 所有坐标均为 2048 级别地图图像坐标。
 */
object Navigation {
    private var isWarmUp = false
    private val instance = NavigationInstance()

    /** 预加载地图数据。与 BGI 一致：只在第一次调用时加载，之后只重置上一位置。 */
    @Synchronized
    fun warmUp(mapMatchMethod: String?) {
        if (!isWarmUp) {
            MapManager.getMap(MapTypes.Teyvat, mapMatchMethod).warmUp()
        }
        isWarmUp = true
        reset()
    }

    fun reset() = instance.reset()

    fun setPrevPosition(x: Float, y: Float) = instance.setPrevPosition(x, y)

    fun getPosition(miniMap: Mat, mapName: String, mapMatchMethod: String?): Point2f =
        instance.getPosition(miniMap, mapName, mapMatchMethod)

    /** 稳定获取当前位置：局部匹配失败或跳变过大时改用全局匹配。地图追踪主流程不使用。 */
    fun getPositionStable(miniMap: Mat, mapName: String, mapMatchMethod: String?): Point2f =
        instance.getPositionStable(miniMap, mapName, mapMatchMethod)

    /**
     * 目标点相对当前位置的朝向角度（度，图像坐标系，y 轴向下，自 +x 顺时针），与视角角度同一口径。
     */
    fun getTargetOrientation(targetX: Double, targetY: Double, position: Point2f): Int {
        val deltaX = targetX - position.x
        val deltaY = targetY - position.y
        val vectorLength = sqrt(deltaX * deltaX + deltaY * deltaY)
        if (vectorLength == 0.0) {
            return 0
        }
        // 与 x 轴的夹角；y 为负时取补角
        var angle = acos(deltaX / vectorLength)
        if (deltaY < 0) {
            angle = 2 * Math.PI - angle
        }
        return (angle * (180.0 / Math.PI)).toInt()
    }

    fun getDistance(targetX: Double, targetY: Double, position: Point2f): Double {
        val x2 = position.x
        val y2 = position.y
        return sqrt((x2 - targetX) * (x2 - targetX) + (y2 - targetY) * (y2 - targetY))
    }

    /** 释放地图数据（BGI 常驻；手机上任务结束时调用），下次使用会重新加载。 */
    @Synchronized
    fun release() {
        MapManager.releaseAll()
        isWarmUp = false
        reset()
    }
}

class NavigationInstance {
    private var prevX = -1f
    private var prevY = -1f
    private var captureTime = Long.MIN_VALUE

    @Synchronized
    fun reset() {
        prevX = -1f
        prevY = -1f
    }

    @Synchronized
    fun setPrevPosition(x: Float, y: Float) {
        prevX = x
        prevY = y
    }

    @Synchronized
    fun getPosition(miniMap: Mat, mapName: String, mapMatchMethod: String?): Point2f {
        val now = System.nanoTime()
        val p = MapManager.getMap(mapName, mapMatchMethod).getMiniMapPosition(miniMap, prevX, prevY)
        if (!p.isDefault && now > captureTime) {
            prevX = p.x
            prevY = p.y
            captureTime = now
        }
        return p
    }

    @Synchronized
    fun getPositionStable(miniMap: Mat, mapName: String, mapMatchMethod: String?): Point2f {
        val now = System.nanoTime()
        // 先尝试局部匹配（TemplateMatch 用 rank 0 提高阈值）
        val sceneMap = MapManager.getMap(mapName, mapMatchMethod)
        var p = (sceneMap as? SceneBaseMapByTemplateMatch)?.getMiniMapPosition(miniMap, prevX, prevY, 0)
            ?: sceneMap.getMiniMapPosition(miniMap, prevX, prevY)

        // 局部匹配失败或点位跳跃过大，再尝试全地图匹配
        if (p.isDefault || (prevX > 0 && prevY > 0 && p.distanceTo(Point2f(prevX, prevY)) > 150)) {
            prevX = -1f
            prevY = -1f
            p = MapManager.getMap(mapName, mapMatchMethod).getMiniMapPosition(miniMap, prevX, prevY)
        }
        if (!p.isDefault && now > captureTime) {
            prevX = p.x
            prevY = p.y
            captureTime = now
        }
        return p
    }
}

package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.model.HandledException

/**
 * 传送与大地图读点。第四阶段只定义调用点，具体流程在第五阶段实现。
 */
interface PathingTeleport {
    /** @return 实际传送到的游戏坐标 */
    fun tp(gameX: Double, gameY: Double, mapName: String, force: Boolean): Point2f

    fun openBigMap(mapName: String)

    /** 大地图中心的游戏坐标。失败返回 null。 */
    fun positionFromBigMap(mapName: String): Point2f?

    fun closeMap()
}

object UnimplementedTeleport : PathingTeleport {
    override fun tp(gameX: Double, gameY: Double, mapName: String, force: Boolean): Point2f {
        throw HandledException("传送还没实现，带传送点的路线暂时不能执行")
    }

    override fun openBigMap(mapName: String) {
        throw HandledException("大地图定位还没实现")
    }

    override fun positionFromBigMap(mapName: String): Point2f? = null

    override fun closeMap() = Unit
}

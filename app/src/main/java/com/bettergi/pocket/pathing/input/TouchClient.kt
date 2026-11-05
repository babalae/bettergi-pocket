package com.bettergi.pocket.pathing.input

/**
 * 地图追踪发到无障碍进程的触控指令。坐标都是原生屏幕像素。
 * 持续触点与瞬时触点由 [com.bettergi.pocket.input.GestureComposer] 合并派发。
 */
interface TouchClient {
    fun padHold(x: Int, y: Int): Boolean
    fun padRelease(): Boolean
    fun buttonDown(id: String, x: Int, y: Int): Boolean
    fun buttonUp(id: String): Boolean
    fun tap(x: Int, y: Int, durationMs: Long): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean
    fun dragBegin(x: Int, y: Int): Boolean
    fun dragStep(x: Int, y: Int, durationMs: Long): Boolean
    fun dragEnd(): Boolean
    fun releaseAll(): Boolean
}

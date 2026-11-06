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
    /** 同步双指缩放，span 是两指间距；不支持的客户端明确返回失败。 */
    fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int, durationMs: Long): Boolean = false
    fun dragBegin(x: Int, y: Int): Boolean
    fun dragStep(x: Int, y: Int, durationMs: Long): Boolean
    fun dragEnd(): Boolean
    fun releaseAll(): Boolean
    /** 工作线程调用：等待实际抬手完成；同步客户端可沿用 releaseAll。 */
    fun releaseAllAndWait(timeoutMs: Long = 3000L): Boolean = releaseAll()
}

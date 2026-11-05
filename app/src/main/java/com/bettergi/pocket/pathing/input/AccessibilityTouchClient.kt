package com.bettergi.pocket.pathing.input

import com.bettergi.pocket.input.InputAccessibilityService

/** 主进程侧的触控客户端，经 ContentProvider 转到 `:a11y` 进程的 [com.bettergi.pocket.input.GestureComposer]。 */
object AccessibilityTouchClient : TouchClient {
    override fun padHold(x: Int, y: Int): Boolean = InputAccessibilityService.padHold(x, y)

    override fun padRelease(): Boolean = InputAccessibilityService.padRelease()

    override fun buttonDown(id: String, x: Int, y: Int): Boolean = InputAccessibilityService.buttonDown(id, x, y)

    override fun buttonUp(id: String): Boolean = InputAccessibilityService.buttonUp(id)

    override fun tap(x: Int, y: Int, durationMs: Long): Boolean = InputAccessibilityService.tap(x, y, durationMs)

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        return InputAccessibilityService.swipe(x1, y1, x2, y2, durationMs)
    }

    override fun dragBegin(x: Int, y: Int): Boolean = InputAccessibilityService.dragBegin(x, y)

    override fun dragStep(x: Int, y: Int, durationMs: Long): Boolean {
        return InputAccessibilityService.dragStep(x, y, durationMs)
    }

    override fun dragEnd(): Boolean = InputAccessibilityService.dragEnd()

    override fun releaseAll(): Boolean = InputAccessibilityService.releaseAllTouches()
}

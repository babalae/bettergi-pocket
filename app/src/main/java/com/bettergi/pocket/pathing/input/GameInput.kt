package com.bettergi.pocket.pathing.input

import com.bettergi.pocket.pathing.GameButton
import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.area.ReferenceSearch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

sealed class InputResult {
    data object Ok : InputResult()
    data class NotCalibrated(val what: String) : InputResult()
    data object Failed : InputResult()

    val succeeded: Boolean get() = this is Ok
}

/**
 * 把走路、转向、按钮这些逻辑动作换成原生屏幕上的触控。
 * 摇杆角度 0 朝前（屏幕上方），90 朝右，180 朝后，-90 朝左。力度 1 为推满。
 */
class GameInput(
    private val layout: PathingLayout,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val client: TouchClient,
) {
    /** 转向期间保持同一视角触点，微调不必反复跨过游戏的拖动起步阈值。 */
    private var viewTouch: Pair<Int, Int>? = null

    fun holdForward(): InputResult = holdDirection(0.0, 1.0)

    fun holdBackward(): InputResult = holdDirection(180.0, 1.0)

    fun holdLeft(): InputResult = holdDirection(-90.0, 1.0)

    fun holdRight(): InputResult = holdDirection(90.0, 1.0)

    fun holdDirection(angleDegrees: Double, magnitude: Double): InputResult {
        val stick = layout.joystick ?: return InputResult.NotCalibrated("摇杆")
        val mapped = mapPoint(stick.centerX, stick.centerY) ?: return InputResult.NotCalibrated("摇杆")
        val scale = mapScale() ?: return InputResult.NotCalibrated("参考画布")
        val radius = (stick.radius * scale).roundToInt().coerceAtLeast(1)
        val mag = magnitude.coerceIn(0.0, 1.0)
        val radians = Math.toRadians(angleDegrees)
        val x = mapped.first + (sin(radians) * radius * mag).roundToInt()
        val y = mapped.second - (cos(radians) * radius * mag).roundToInt()
        return if (client.padHold(x, y)) InputResult.Ok else InputResult.Failed
    }

    fun releasePad(): InputResult = if (client.padRelease()) InputResult.Ok else InputResult.Failed

    fun tapButton(button: GameButton, durationMs: Long = TAP_MS): InputResult {
        val point = buttonPoint(button) ?: return InputResult.NotCalibrated(button.id)
        return if (client.tap(point.first, point.second, durationMs)) InputResult.Ok else InputResult.Failed
    }

    fun holdButton(button: GameButton): InputResult {
        val point = buttonPoint(button) ?: return InputResult.NotCalibrated(button.id)
        return if (client.buttonDown(button.id, point.first, point.second)) InputResult.Ok else InputResult.Failed
    }

    fun releaseButton(button: GameButton): InputResult {
        return if (client.buttonUp(button.id)) InputResult.Ok else InputResult.Failed
    }

    fun tapPickup(durationMs: Long = TAP_MS): InputResult {
        val point = layout.pickup ?: return InputResult.NotCalibrated("拾取")
        val mapped = mapPoint(point.x, point.y) ?: return InputResult.NotCalibrated("拾取")
        return if (client.tap(mapped.first, mapped.second, durationMs)) InputResult.Ok else InputResult.Failed
    }

    /** 点击 `layout.json` 参考画布上的点，例如区域名。 */
    fun tapCalibrated(x: Int, y: Int, durationMs: Long = TAP_MS): InputResult {
        val mapped = mapPoint(x, y) ?: return InputResult.NotCalibrated("参考画布")
        return if (client.tap(mapped.first, mapped.second, durationMs)) InputResult.Ok else InputResult.Failed
    }

    /** 点击已经是原生坐标的位置，例如识别到的传送按钮。 */
    fun tapNative(x: Int, y: Int, durationMs: Long = TAP_MS): InputResult {
        return if (client.tap(x, y, durationMs)) InputResult.Ok else InputResult.Failed
    }

    /**
     * 按视角差角滑动。像素超出视角区时截断，剩余角度由下一帧重新计算。
     * 使用持续拖动：TB322FC 上重新按下后的小幅独立滑动会被游戏忽略，持续触点可正常微调。
     */
    fun rotateByDiff(diffDegrees: Float): InputResult {
        val gain = layout.cameraGain ?: return InputResult.NotCalibrated("视角灵敏度")
        val scale = mapScale() ?: return InputResult.NotCalibrated("参考画布")
        // 增益在参考分辨率下标定，换成当前分辨率的像素/度后再计算并取整。
        val pixels = CameraControl.swipePixels(diffDegrees, gain * scale)
        if (pixels == 0) return InputResult.Ok
        val area = viewArea() ?: return InputResult.NotCalibrated("视角区")
        var current = viewTouch
        if (current == null) {
            current = area.centerX to area.centerY
            if (!client.dragBegin(current.first, current.second)) return InputResult.Failed
            viewTouch = current
        }
        val nextX = (current.first + pixels).coerceIn(area.left, area.right - 1)
        if (nextX == current.first) {
            // 到边缘后先抬手，下一帧从中心重新按下，避免把回中动作算成反向转动。
            return releaseView()
        }
        if (!client.dragStep(nextX, current.second, CameraControl.SWIPE_MS)) return InputResult.Failed
        viewTouch = nextX to current.second
        return InputResult.Ok
    }

    /** 转向结束后抬起视角触点，防止后续点按与它冲突。 */
    fun releaseView(): InputResult {
        if (viewTouch == null) return InputResult.Ok
        viewTouch = null
        return if (client.dragEnd()) InputResult.Ok else InputResult.Failed
    }

    /**
     * 在视角区做一次指定像素的横向滑动。
     * @return 实际滑过的像素；未标定或派发失败时为 0
     */
    fun swipeViewPixels(dx: Int, durationMs: Long): Int {
        val area = viewArea() ?: return 0
        val segment = CameraControl.clampSwipe(area, dx)
        if (segment.appliedPixels == 0) return 0
        val ok = client.swipe(segment.x1, segment.y1, segment.x2, segment.y2, durationMs)
        return if (ok) segment.appliedPixels else 0
    }

    fun dragBegin(x: Int, y: Int): InputResult = if (client.dragBegin(x, y)) InputResult.Ok else InputResult.Failed

    fun dragStep(x: Int, y: Int, durationMs: Long): InputResult {
        return if (client.dragStep(x, y, durationMs)) InputResult.Ok else InputResult.Failed
    }

    fun dragEnd(): InputResult = if (client.dragEnd()) InputResult.Ok else InputResult.Failed

    /** 大地图双指开合，坐标和指间距都是原生屏幕像素。 */
    fun pinchMap(startSpan: Int, endSpan: Int, durationMs: Long): InputResult {
        return if (client.pinch(screenWidth / 2, screenHeight / 2, startSpan, endSpan, durationMs)) {
            InputResult.Ok
        } else InputResult.Failed
    }

    fun releaseAll(): InputResult {
        viewTouch = null
        if (!client.releaseAllAndWait()) throw TouchReleaseException()
        return InputResult.Ok
    }

    private fun buttonPoint(button: GameButton): Pair<Int, Int>? {
        val point = layout.buttons[button] ?: return null
        return mapPoint(point.x, point.y)
    }

    private fun viewArea(): IntRect? {
        val area = layout.viewArea ?: return null
        val reference = layout.referenceSize ?: return null
        return ReferenceSearch.mapReferenceRect(
            srcWidth = screenWidth,
            srcHeight = screenHeight,
            referenceImageSize = reference,
            referenceRect = area.bbox,
            anchorMode = area.anchorMode,
        )?.rect
    }

    private fun mapPoint(x: Int, y: Int): Pair<Int, Int>? {
        val reference = layout.referenceSize ?: return null
        val mapping = ReferenceSearch.mapReferenceRect(
            srcWidth = screenWidth,
            srcHeight = screenHeight,
            referenceImageSize = reference,
            referenceRect = IntRect(x, y, 1, 1),
        ) ?: return null
        return mapping.rect.x to mapping.rect.y
    }

    private fun mapScale(): Double? {
        val reference = layout.referenceSize ?: return null
        if (screenWidth <= 0 || screenHeight <= 0 || reference.width <= 0 || reference.height <= 0) return null
        return minOf(
            screenWidth / reference.width.toDouble(),
            screenHeight / reference.height.toDouble(),
        )
    }

    private companion object {
        const val TAP_MS = 50L
    }
}

/** 释放失败必须退出整个批次，不能被当作普通路线失败后继续下一条。 */
class TouchReleaseException : java.util.concurrent.CancellationException(
    "触点释放未确认，请关闭再重新开启无障碍服务后重试",
)

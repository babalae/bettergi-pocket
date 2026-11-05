package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.pathing.GameButton
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.model.WaypointForTrack
import com.bettergi.pocket.pathing.navigation.Navigation
import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.vision.MotionStatus
import com.bettergi.pocket.pathing.vision.PathingVision
import java.util.Random

/**
 * 卡死脱困。分支和时长对齐 BGI `TrapEscaper`。
 * 下落只在识别到攀爬或滑翔时点，避免平时点到别的按钮。
 */
class TrapEscaper(
    private val runtime: PathingRuntime,
    private val input: GameInput,
    private val rotate: CameraRotateTask,
    private val vision: PathingVision,
    private val locate: (CapturedBgrFrame, WaypointForTrack) -> Point2f,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long,
    private val random: Random = Random(),
) {
    private var lastActionIndex = 0
    private var lastActionAtMs = 0L
    private var randomAngle = 0

    fun moveTo(waypoint: WaypointForTrack) {
        val started = nowMs()
        var left = false
        var position = capturePosition(waypoint)
        lastActionAtMs = nowMs()
        rotate.waitUntilRotatedTo(orientation(waypoint, position) , 5)
        input.holdForward()
        while (!runtime.cancelled) {
            val now = nowMs()
            if (now - lastActionAtMs > 5_000L) break
            if (now - started > 25_000L) {
                log("卡死脱困超时")
                break
            }
            position = capturePosition(waypoint)
            val target = orientation(waypoint, position) + randomAngle
            rotate.waitUntilRotatedTo(normalizeAngle(target), 5)
            input.holdForward()
            if (randomAngle != 0) {
                randomAngle %= 360
                if (nowMs() - lastActionAtMs > 1_500L) randomAngle = 0
            }
            if (waypoint.moveMode.code != "climb" && waypoint.moveMode.code != "fly" && motion() == MotionStatus.Climb) {
                input.releasePad()
                input.tapButton(GameButton.Drop)
                runtime.delay(75)
                input.holdBackward()
                runtime.delay(700)
                input.releasePad()
                lastActionAtMs = nowMs()
                if (!left) increaseRandomAngle() else reduceRandomAngle()
                continue
            }
            runtime.delay(100)
        }
        input.releasePad()
    }

    fun rotateAndMove() {
        increaseRandomAngle()
        input.releasePad()
        if (motion() == MotionStatus.Climb || motion() == MotionStatus.Fly) {
            input.tapButton(GameButton.Drop)
        }
        runtime.delay(75)
        input.tapButton(GameButton.Attack)
        runtime.delay(500)
        if (nowMs() - lastActionAtMs >= 10_000L) {
            lastActionIndex = 0
        } else {
            lastActionIndex++
        }
        val extra = lastActionIndex * 1000
        when (lastActionIndex % 3) {
            0 -> moveBackward(1000 + extra)
            1 -> moveLeft(700 + extra)
            2 -> moveRight(700 + extra)
        }
        lastActionAtMs = nowMs()
    }

    private fun moveBackward(delayMs: Int) {
        input.holdBackward()
        runtime.delay(500)
        input.tapButton(GameButton.Jump)
        runtime.delay(delayMs.toLong())
        input.releasePad()
    }

    private fun moveLeft(delayMs: Int) {
        input.holdLeft()
        runtime.delay(300)
        input.tapButton(GameButton.Jump)
        runtime.delay(delayMs.toLong())
        input.releasePad()
        if (motion() == MotionStatus.Climb || motion() == MotionStatus.Fly) {
            input.tapButton(GameButton.Drop)
        }
    }

    private fun moveRight(delayMs: Int) {
        input.holdRight()
        runtime.delay(300)
        input.tapButton(GameButton.Jump)
        runtime.delay(delayMs.toLong())
        input.releasePad()
        if (motion() == MotionStatus.Climb || motion() == MotionStatus.Fly) {
            input.tapButton(GameButton.Drop)
        }
    }

    private fun increaseRandomAngle() {
        randomAngle += 30 + random.nextInt(15)
    }

    private fun reduceRandomAngle() {
        randomAngle += -45 + random.nextInt(15)
    }

    private fun orientation(waypoint: WaypointForTrack, position: Point2f): Int {
        return Navigation.getTargetOrientation(waypoint.imageX, waypoint.imageY, position)
    }

    private fun capturePosition(waypoint: WaypointForTrack): Point2f {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return Point2f.DEFAULT
        try {
            return locate(frame, waypoint)
        } finally {
            frame.bgr.release()
        }
    }

    private fun motion(): MotionStatus {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return MotionStatus.Unknown
        try {
            return vision.motionStatus(frame.bgr)
        } finally {
            frame.bgr.release()
        }
    }

    private fun normalizeAngle(angle: Int): Int {
        var value = angle % 360
        if (value < 0) value += 360
        return value
    }
}

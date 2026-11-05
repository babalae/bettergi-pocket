package com.bettergi.pocket.pathing.executor

import android.os.SystemClock
import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.pathing.GameButton
import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.input.InputResult
import com.bettergi.pocket.pathing.map.MapManager
import com.bettergi.pocket.pathing.minimap.MiniMapLocator
import com.bettergi.pocket.pathing.model.HandledException
import com.bettergi.pocket.pathing.model.MoveMode
import com.bettergi.pocket.pathing.model.NormalEndException
import com.bettergi.pocket.pathing.model.PathAction
import com.bettergi.pocket.pathing.model.PathingResult
import com.bettergi.pocket.pathing.model.PathingRoute
import com.bettergi.pocket.pathing.model.PathingRoutes
import com.bettergi.pocket.pathing.model.RetryException
import com.bettergi.pocket.pathing.model.RetryNoCountException
import com.bettergi.pocket.pathing.model.WaypointForTrack
import com.bettergi.pocket.pathing.model.WaypointKind
import com.bettergi.pocket.pathing.navigation.Navigation
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.pathing.tp.PathingTeleport
import com.bettergi.pocket.pathing.vision.MotionStatus
import com.bettergi.pocket.pathing.vision.PathingVision
import java.util.concurrent.CancellationException
import kotlin.math.abs

/**
 * 路线主循环。删除项（切队、回血、赶路、月卡、结束条件）不再进入循环。
 * 来源：BGI `PathExecutor`。
 */
class PathExecutor(
    private val runtime: PathingRuntime,
    private val input: GameInput,
    private val locator: MiniMapLocator,
    private val settings: PathingSettings,
    private val vision: PathingVision,
    private val teleport: PathingTeleport,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val rotate = CameraRotateTask(runtime, input, locator, log)
    private val party = PartyControl(runtime, input, settings, vision, log, nowMs)
    private val trap = TrapEscaper(runtime, input, rotate, vision, ::locate, log, nowMs)

    private var inTrap = 0
    private var skipping = false
    private var resumeSegment = -1
    private var resumePoint = -1
    private var activePoint = 0
    private var previousPosition: Point2f? = null
    private var lastGadgetAtMs = 0L
    private var lastPickupAtMs = 0L
    private var pickupWarned = false

    fun execute(route: PathingRoute): PathingResult {
        if (route.waypoints.isEmpty()) {
            return PathingResult(false, "路线没有路径点，未执行。")
        }
        PathingRoutes.rejectionReason(route)?.let { return PathingResult(false, it) }

        val segments = PathingRoutes.segment(route)
        runtime.delay(100)
        Navigation.warmUp(route.mapMatchMethod)
        val map = MapManager.getMap(route.mapName, route.mapMatchMethod)
        return try {
            runSegments(segments, map)
        } finally {
            Navigation.release()
        }
    }

    private fun runSegments(
        segments: List<List<WaypointForTrack>>,
        map: com.bettergi.pocket.pathing.map.SceneMap,
    ): PathingResult {
        var allCompleted = true
        var firstFailure: String? = null
        for (segmentIndex in segments.indices) {
            var completed = false
            var segmentError: String? = null
            var attempt = 0
            while (attempt < PathingSettings.RETRY_TIMES && !runtime.cancelled) {
                try {
                    activePoint = 0
                    resolveAnomalies(null)
                    val segment = segments[segmentIndex]
                    if (segment.first().kind != WaypointKind.Teleport) {
                        Navigation.setPrevPosition(segment.first().imageX.toFloat(), segment.first().imageY.toFloat())
                    }
                    for (pointIndex in segment.indices) {
                        activePoint = pointIndex
                        val waypoint = segment[pointIndex]
                        closeSkipIfResumed(segmentIndex, pointIndex)
                        if (waypoint.kind == WaypointKind.Teleport) {
                            if (segmentIndex > 0) {
                                val previous = segments[segmentIndex - 1].last()
                                val noDelay = previous.kind == WaypointKind.Teleport ||
                                    previous.action == PathAction.Fight ||
                                    previous.action == PathAction.NahidaCollect ||
                                    previous.action == PathAction.PickAround
                                if (!noDelay) runtime.delay(1000)
                            }
                            teleportTo(waypoint, map)
                        } else {
                            beforeMove(waypoint)
                            if (waypoint.kind == WaypointKind.Orientation) {
                                faceTo(waypoint)
                            } else if (waypoint.action != PathAction.UpDownGrabLeaf) {
                                moveTo(waypoint)
                            }
                            if (waypoint.moveMode == MoveMode.Fly && waypoint.action == PathAction.StopFlying) {
                                stopFlying(waypoint)
                            }
                            if (PathingRoutes.isTargetPoint(waypoint)) {
                                moveCloseTo(waypoint)
                                pickup()
                            }
                            if (!waypoint.actionCode.isNullOrBlank() && !skipping) {
                                afterMove(waypoint)
                            }
                        }
                    }
                    completed = true
                    break
                } catch (e: HandledException) {
                    segmentError = e.message
                    break
                } catch (e: NormalEndException) {
                    segmentError = e.message
                    log(e.message ?: "路线结束")
                    break
                } catch (e: CancellationException) {
                    segmentError = "地图追踪被取消。"
                    break
                } catch (e: RetryNoCountException) {
                    segmentError = e.message
                    startSkip(segmentIndex, activePoint)
                    log(e.message ?: "重试")
                } catch (e: RetryException) {
                    segmentError = e.message
                    startSkip(segmentIndex, activePoint)
                    log(e.message ?: "重试")
                    attempt++
                } finally {
                    input.releaseAll()
                }
            }
            allCompleted = allCompleted && completed
            if (!completed) {
                firstFailure = firstFailure ?: segmentError ?: "重试耗尽，当前路径段未完整执行。"
            }
        }
        val success = allCompleted && segments.isNotEmpty() && !runtime.cancelled
        return PathingResult(
            isSuccess = success,
            errorMessage = if (success) null else firstFailure ?: "地图追踪路线未完整执行。",
        )
    }

    private fun teleportTo(waypoint: WaypointForTrack, map: com.bettergi.pocket.pathing.map.SceneMap) {
        val arrived = teleport.tp(
            waypoint.gameX,
            waypoint.gameY,
            waypoint.mapName,
            force = waypoint.action == PathAction.ForceTp,
        )
        val image = map.convertGenshinMapCoordinatesToImageCoordinates(arrived)
        Navigation.setPrevPosition(image.x, image.y)
        runtime.delay(500)
    }

    private fun faceTo(waypoint: WaypointForTrack) {
        val position = sample(waypoint).point
        val target = Navigation.getTargetOrientation(waypoint.imageX, waypoint.imageY, position)
        log("朝向点 ${format(waypoint.gameX)}, ${format(waypoint.gameY)}")
        waitUntilRotated(target, 2)
        runtime.delay(500)
    }

    private fun moveTo(waypoint: WaypointForTrack) {
        party.switchTo(settings.mainAvatarIndex)
        val switchedAt = nowMs()
        var sample = sample(waypoint)
        var position = sample.point
        var extraMs = sample.extraMs
        log("走向 ${format(waypoint.gameX)}, ${format(waypoint.gameY)}")
        waitUntilRotated(Navigation.getTargetOrientation(waypoint.imageX, waypoint.imageY, position), 5)
        val started = nowMs()
        var lastRecord = started
        var sprintHeld = false
        var lastSprintAt = 0L
        val history = ArrayList<Point2f>()
        var stablePosition = position
        var farCount = 0
        var frame = 0
        var offAngleFrames = 0
        var offAngleSince = 0L
        input.holdForward()
        while (!runtime.cancelled) {
            input.holdForward()
            frame++
            if (nowMs() - started > 240_000L) {
                throw RetryException("路径点执行超时，放弃整条路径")
            }
            sample = sample(waypoint)
            position = sample.point
            extraMs = sample.extraMs
            if (extraMs > 0) {
                input.holdForward()
                extraMs += 1000
            }
            val distance = Navigation.getDistance(waypoint.imageX, waypoint.imageY, position)
            if (distance < 4) {
                log("到达路径点附近")
                break
            }
            if (distance > 500) {
                farCount++
                if (farCount > 50) {
                    throw HandledException(
                        if (position.isDefault) "重试多次后，当前点位无法被识别，放弃此路径！"
                        else "目标距离过远，可能是当前点位无法识别，放弃此路径！",
                    )
                }
                if (farCount % 5 == 0) {
                    log("距离过远 ${format(distance)}，重试")
                }
                if (farCount % 10 == 0) {
                    resolveAnomalies(null)
                    Navigation.setPrevPosition(stablePosition.x, stablePosition.y)
                    runtime.delay(500)
                }
                runtime.delay(50)
                continue
            } else {
                stablePosition = position
            }
            if (waypoint.moveMode != MoveMode.Climb && nowMs() - lastRecord > 1000L + extraMs) {
                lastRecord = nowMs()
                history += position
                if (history.size > 8) {
                    val newest = history.last()
                    val older = history[history.size - 8]
                    if (abs(newest.x - older.x) + abs(newest.y - older.y) < 3f) {
                        inTrap++
                        if (inTrap > 2) throw RetryException("此路线出现3次卡死，重试一次路线或放弃此路线！")
                        log("疑似卡死，尝试脱离...")
                        trap.rotateAndMove()
                        trap.moveTo(waypoint)
                        input.holdForward()
                        log("卡死脱离结束")
                        continue
                    }
                }
            }
            val target = Navigation.getTargetOrientation(waypoint.imageX, waypoint.imageY, position)
            val diff = rotateCurrent(target)
            if (nowMs() - started > 2_000L && frame >= 5) {
                if (abs(diff) > 5f) {
                    offAngleFrames++
                    if (offAngleSince == 0L) offAngleSince = nowMs()
                } else {
                    offAngleFrames = 0
                    offAngleSince = 0L
                }
                if (offAngleFrames >= 3 && offAngleSince > 0L && nowMs() - offAngleSince > 2_000L) {
                    input.releasePad()
                    waitUntilRotated(target, 2)
                    input.holdForward()
                }
            }
            when (waypoint.moveMode) {
                MoveMode.Fly -> {
                    if (motion() != MotionStatus.Fly) {
                        input.tapButton(GameButton.Jump)
                        runtime.delay(200)
                    }
                    runtime.delay(100)
                    continue
                }
                MoveMode.Jump -> {
                    input.tapButton(GameButton.Jump)
                    runtime.delay(200)
                    continue
                }
                MoveMode.Run -> {
                    val wantSprint = distance > 20
                    if (wantSprint != sprintHeld) {
                        if (wantSprint) input.holdButton(GameButton.Sprint) else input.releaseButton(GameButton.Sprint)
                        sprintHeld = wantSprint
                    }
                }
                MoveMode.Dash -> {
                    if (distance > 20 && nowMs() - lastSprintAt > 1000L) {
                        lastSprintAt = nowMs()
                        input.tapButton(GameButton.Sprint)
                    }
                }
                MoveMode.Climb -> Unit
                else -> {
                    if (distance > 10 && settings.guardianAvatarIndex != null && party.skillIntervalElapsed()) {
                        if (nowMs() - switchedAt < 1000L &&
                            settings.mainAvatarIndex != null &&
                            settings.guardianAvatarIndex != settings.mainAvatarIndex
                        ) {
                            runtime.delay(800)
                        }
                        party.useGuardianSkill(walking = true)
                    }
                    if (distance > 20 && PathingSettings.AUTO_RUN && nowMs() - lastSprintAt > 2500L) {
                        lastSprintAt = nowMs()
                        input.tapButton(GameButton.Sprint)
                    }
                }
            }
            if (settings.useGadgetIntervalMs > 0 && nowMs() - lastGadgetAtMs > settings.useGadgetIntervalMs) {
                input.tapButton(GameButton.Gadget)
                lastGadgetAtMs = nowMs()
            }
            if (nowMs() - lastPickupAtMs > PathingSettings.PICKUP_INTERVAL_MS) {
                pickup()
                lastPickupAtMs = nowMs()
            }
            runtime.delay(PathingSettings.FRAME_INTERVAL_MS)
        }
        input.releasePad()
        input.releaseButton(GameButton.Sprint)
    }

    private fun moveCloseTo(waypoint: WaypointForTrack) {
        log("精确接近 ${format(waypoint.gameX)}, ${format(waypoint.gameY)}")
        var steps = 0
        while (!runtime.cancelled) {
            steps++
            if (steps > 25) {
                log("精确接近超时")
                break
            }
            val position = sample(waypoint).point
            if (Navigation.getDistance(waypoint.imageX, waypoint.imageY, position) < 2) {
                log("已到达路径点")
                break
            }
            val target = Navigation.getTargetOrientation(waypoint.imageX, waypoint.imageY, position)
            waitUntilRotated(target, 2)
            input.holdForward()
            runtime.delay(60)
            input.releasePad()
            runtime.delay(20)
        }
        input.releasePad()
        runtime.delay(1000)
    }

    private fun beforeMove(waypoint: WaypointForTrack) {
        if (waypoint.action == PathAction.LogOutput) {
            log(waypoint.actionParams ?: "")
        }
    }

    private fun afterMove(waypoint: WaypointForTrack) {
        if (waypoint.action == PathAction.UseGadget) {
            useGadget(waypoint)
            runtime.delay(1000)
        }
    }

    private fun stopFlying(waypoint: WaypointForTrack) {
        val wait = waypoint.actionParams?.toIntOrNull()
        if (wait != null) {
            input.tapButton(GameButton.Jump)
            runtime.delay(wait.toLong())
            input.tapButton(GameButton.Jump)
            runtime.delay(300)
        }
        log("动作：下落攻击")
        input.tapButton(GameButton.Attack)
        var i = 0
        while (i < 50) {
            if (motion() != MotionStatus.Fly) break
            runtime.delay(300)
            i++
        }
        log(if (i == 50) "动作：下落攻击 超时结束" else "动作：下落攻击 结束")
    }

    private fun useGadget(waypoint: WaypointForTrack) {
        log("执行 使用小道具")
        input.tapButton(GameButton.Gadget)
        val params = waypoint.actionParams.orEmpty()
        if (params.contains("not_wait", ignoreCase = true)) {
            input.tapButton(GameButton.Gadget)
        } else {
            var maxWait = 100.0
            params.toDoubleOrNull()?.let { maxWait = it }
            val cd = readGadgetCd()
            when {
                cd != null && cd > 100 -> {
                    log("小道具 CD ${cd} 秒，时间过长，跳过等待")
                    input.tapButton(GameButton.Gadget)
                }
                cd != null && cd > 0 -> {
                    val waitMs = if (cd > maxWait) (maxWait * 1000).toLong() else (cd * 1000).toLong() + 100
                    log("小道具 CD ${cd} 秒，等待")
                    runtime.delay(waitMs)
                    input.tapButton(GameButton.Gadget)
                }
                else -> input.tapButton(GameButton.Gadget)
            }
        }
        runtime.delay(300)
    }

    private fun sample(waypoint: WaypointForTrack): PositionSample {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS)
            ?: return PositionSample(Point2f.DEFAULT, 0)
        try {
            return positionAndTime(frame, waypoint)
        } finally {
            frame.bgr.release()
        }
    }

    private fun positionAndTime(frame: CapturedBgrFrame, waypoint: WaypointForTrack): PositionSample {
        var position = locate(frame, waypoint)
        var extra = 0
        if (position.isDefault && !vision.isInMainUi(frame.bgr)) {
            log("小地图定位失败，且当前不是主界面，进入异常处理")
            resolveAnomalies(frame.bgr)
        }
        val distance = Navigation.getDistance(waypoint.imageX, waypoint.imageY, position)
        val types = waypoint.misidentification.types
        val unrecognized = position.isDefault && types.contains("unrecognized")
        val tooFar = distance > 500 && types.contains("pathTooFar")
        if (unrecognized || tooFar) {
            when (waypoint.misidentification.handlingMode) {
                "previousDetectedPoint" -> {
                    val previous = previousPosition
                    if (previous != null && !previous.isDefault) {
                        position = previous
                        log("未识别到具体路径，取上次点位")
                    }
                }
                "mapRecognition" -> {
                    val started = nowMs()
                    try {
                        teleport.openBigMap(waypoint.mapName)
                        val game = teleport.positionFromBigMap(waypoint.mapName)
                        if (game != null) {
                            position = MapManager.getMap(waypoint.mapName, waypoint.mapMatchMethod)
                                .convertGenshinMapCoordinatesToImageCoordinates(game)
                        }
                        teleport.closeMap()
                        waitForMainUi()
                    } catch (e: Exception) {
                        log(e.message ?: "地图中心点识别失败")
                    }
                    extra = (nowMs() - started).toInt()
                }
            }
        } else {
            previousPosition = position
        }
        return PositionSample(position, extra)
    }

    private fun locate(frame: CapturedBgrFrame, waypoint: WaypointForTrack): Point2f {
        val miniMap = locator.extract(frame.bgr) ?: return Point2f.DEFAULT
        try {
            return Navigation.getPosition(miniMap, waypoint.mapName, waypoint.mapMatchMethod)
        } finally {
            miniMap.release()
        }
    }

    private fun rotateCurrent(target: Int): Float {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return 0f
        try {
            val miniMap = locator.extract(frame.bgr) ?: return 0f
            try {
                return rotate.rotateToApproach(target, miniMap)
            } finally {
                miniMap.release()
            }
        } finally {
            frame.bgr.release()
        }
    }

    private fun waitUntilRotated(target: Int, maxDiff: Int) {
        if (rotate.waitUntilRotatedTo(target, maxDiff)) return
        resolveAnomalies(null)
        rotate.waitUntilRotatedTo(target, maxDiff)
    }

    private fun motion(): MotionStatus {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return MotionStatus.Unknown
        try {
            return vision.motionStatus(frame.bgr)
        } finally {
            frame.bgr.release()
        }
    }

    private fun readGadgetCd(): Double? {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return null
        try {
            return vision.gadgetCooldownSeconds(frame.bgr)
        } finally {
            frame.bgr.release()
        }
    }

    private fun resolveAnomalies(frame: org.opencv.core.Mat?) {
        val owned = if (frame == null) runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) else null
        val mat = frame ?: owned?.bgr ?: return
        try {
            val close = vision.closeButton(mat)
            if (close != null && !vision.isInBigMap(mat)) {
                log("检测到其他界面，点击关闭")
                input.tapNative(close.first, close.second)
                runtime.delay(1000)
            }
        } finally {
            owned?.bgr?.release()
        }
        if (PathingSettings.AUTO_SKIP) autoSkip()
    }

    private fun autoSkip() {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return
        val dialogue = try {
            vision.inDialogue(frame.bgr)
        } finally {
            frame.bgr.release()
        }
        if (!dialogue) return
        log("进入剧情，自动点击直到结束")
        var missed = 0
        while (!runtime.cancelled && missed <= 10) {
            val current = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: break
            try {
                if (vision.inDialogue(current.bgr)) {
                    missed = 0
                    val option = vision.lastDialogueOption(current.bgr)
                    if (option != null) input.tapNative(option.first, option.second)
                } else {
                    missed++
                }
            } finally {
                current.bgr.release()
            }
            runtime.delay(210)
        }
    }

    private fun waitForMainUi() {
        runtime.delay(200)
        repeat(10) {
            val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return
            try {
                if (vision.isInMainUi(frame.bgr)) return
            } finally {
                frame.bgr.release()
            }
            runtime.delay(200)
        }
    }

    private fun pickup() {
        val result = input.tapPickup()
        if (result is InputResult.NotCalibrated && !pickupWarned) {
            pickupWarned = true
            log("拾取位置未标定，走路时不会自动拾取")
        }
        if (result is InputResult.Ok) lastPickupAtMs = nowMs()
    }

    private fun closeSkipIfResumed(segment: Int, point: Int) {
        if (skipping && resumeSegment == segment && point < resumePoint) return
        if (skipping) log("已到达上次点位，地图追踪功能恢复")
        skipping = false
    }

    private fun startSkip(segment: Int, point: Int) {
        log("记录恢复点位，到达上次点位之前将跳过走路之外的操作")
        skipping = true
        resumeSegment = segment
        resumePoint = point
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private data class PositionSample(val point: Point2f, val extraMs: Int)
}

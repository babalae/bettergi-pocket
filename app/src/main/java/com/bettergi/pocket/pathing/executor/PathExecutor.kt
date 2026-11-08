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
import com.bettergi.pocket.pathing.party.PathingParty
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.opencv.core.Mat

/**
 * 路线主循环。早期删除项（切队、回血、赶路、月卡、结束条件）不再进入循环；现已恢复队伍内切人和生存位战技。
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
    private val party: PathingParty? = null,
    /** Debug 入口只请求一次实际脱离，正常路线默认关闭；仍在同一条路线线程内执行。 */
    private val requestRecovery: () -> Boolean = { false },
) {
    private val rotate = CameraRotateTask(runtime, input, locator, log)
    private val trap = TrapEscaper({ input.releaseAll() }, log)

    private var inTrap = 0
    private var skipping = false
    private var resumeSegment = -1
    private var resumePoint = -1
    private var activePoint = 0
    private var previousPosition: Point2f? = null
    private var lastGadgetAtMs = 0L
    private var lastPickupAtMs = 0L
    private var pickupWarned = false
    private var routeActions: Set<PathAction> = emptySet()

    fun execute(route: PathingRoute): PathingResult = input.withReleasedInput { executeRoute(route) }

    private fun executeRoute(route: PathingRoute): PathingResult {
        if (route.waypoints.isEmpty()) {
            return PathingResult(false, "路线没有路径点，未执行。")
        }
        PathingRoutes.rejectionReason(route)?.let { return PathingResult(false, it) }
        routeActions = route.waypoints.mapNotNull { it.action }.toSet()
        if (routeActions.any { PathingParty.elementFor(it) != null } && party == null) {
            return PathingResult(false, "元素采集需要启用队伍识别")
        }

        val resolvedRoute = route.withMatchingMethod(settings.mapMatchingMethod)
        log("小地图匹配方式：${resolvedRoute.mapMatchMethod}")
        val segments = PathingRoutes.segment(resolvedRoute)
        runtime.delay(100)
        // BGI 只在第一次加载，之后只重置上一位置。地图数据由调用方在整个任务结束时释放
        Navigation.warmUp(resolvedRoute.mapMatchMethod)
        val map = MapManager.getMap(resolvedRoute.mapName, resolvedRoute.mapMatchMethod)
        return runSegments(segments, map)
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
                    input.releaseAll()
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
                        input.withReleasedInput {
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
                                party?.prepare(routeActions)
                                beforeMove(waypoint)
                                if (waypoint.kind == WaypointKind.Orientation) {
                                    faceTo(waypoint)
                                } else if (waypoint.action != PathAction.UpDownGrabLeaf) {
                                    moveTo(waypoint, segment.getOrNull(pointIndex - 1))
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
                    }
                    if (segment.last().kind != WaypointKind.Teleport) {
                        input.releaseAll()
                        log("路径段结束，停留拾取")
                        EndpointPickup.collect(runtime::delay, ::pickup)
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
        party?.invalidate()
    }

    private fun faceTo(waypoint: WaypointForTrack) {
        val position = sample(waypoint).point
        val target = Navigation.getTargetOrientation(waypoint.imageX, waypoint.imageY, position)
        log("朝向点 ${format(waypoint.gameX)}, ${format(waypoint.gameY)}")
        waitUntilRotated(target, 2)
        runtime.delay(500)
    }

    private fun moveTo(waypoint: WaypointForTrack, previousWaypoint: WaypointForTrack?) {
        if (waypoint.moveMode !in listOf(MoveMode.Fly, MoveMode.Climb, MoveMode.Swim)) party?.walk()
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
            if (!input.holdForward().succeeded) throw HandledException("摇杆手势派发失败，停止当前任务")
            frame++
            if (nowMs() - started > 240_000L) {
                throw RetryException("路径点执行超时，放弃整条路径")
            }
            // 与 BGI 相同：同一帧既定位又算视角，小地图只裁一次
            val screen = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS)
            val miniMap = screen?.let { locator.extract(it.bgr) }
            try {
                sample = positionAndTime(screen, miniMap, waypoint)
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
                        resolveAnomalies(screen?.bgr)
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
                    val forcedRecovery = previousWaypoint != null &&
                        Navigation.getDistance(previousWaypoint.imageX, previousWaypoint.imageY, position) >= 4.0 &&
                        requestRecovery()
                    if (history.size > 8 || forcedRecovery) {
                        val newest = history.last()
                        val older = history.getOrElse(history.size - 8) { newest }
                        if (forcedRecovery || abs(newest.x - older.x) + abs(newest.y - older.y) < 3f) {
                            if (forcedRecovery) log("调试：在当前路线线程内触发一次卡死回退")
                            inTrap++
                            if (inTrap > 2) throw RetryException("此路线出现3次卡死，重试一次路线或放弃此路线！")
                            log("疑似卡死，尝试脱离...")
                            val stuckPosition = recoveryPosition(waypoint)
                            val previous = previousWaypoint?.let { Point2f(it.imageX.toFloat(), it.imageY.toFloat()) }
                            if (!trap.retreat(stuckPosition, previous) { moveBackTo(it, waypoint) }) {
                                throw RetryException("卡死回退未成功，重试当前路径段")
                            }
                            // 脱离结束后重新建立方向、按键和卡死采样，旧历史不能立即触发下一次脱离。
                            history.clear()
                            lastRecord = nowMs()
                            sprintHeld = false
                            lastSprintAt = nowMs()
                            offAngleFrames = 0
                            offAngleSince = 0L
                            position = recoveryPosition(waypoint)
                            if (position.isDefault) throw RetryException("回退后定位失败，停止当前行走")
                            stablePosition = position
                            waitUntilRotated(Navigation.getTargetOrientation(waypoint.imageX, waypoint.imageY, position), 5)
                            input.holdForward()
                            log("卡死脱离结束，重试当前目标点")
                            continue
                        }
                    }
                }
                val target = Navigation.getTargetOrientation(waypoint.imageX, waypoint.imageY, position)
                if (distance > 10 && waypoint.moveMode == MoveMode.Walk && party?.renewGuardian() == true) {
                    // 战技会暂停移动并改变朝向，下一帧重新定位、转向。
                    input.holdForward()
                    continue
                }
                val diff = if (miniMap != null) rotate.rotateToApproach(target, miniMap) else 0f
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
                        val status = screen?.let { vision.motionStatus(it.bgr) } ?: MotionStatus.Unknown
                        if (status != MotionStatus.Fly) {
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
                        if (distance > PathingSettings.AUTO_RUN_MIN_DISTANCE && PathingSettings.AUTO_RUN && nowMs() - lastSprintAt > 2500L) {
                            lastSprintAt = nowMs()
                            input.tapButton(GameButton.Sprint)
                        }
                    }
                }
                if (settings.useGadgetEnabled && settings.useGadgetIntervalMs > 0 &&
                    nowMs() - lastGadgetAtMs > settings.useGadgetIntervalMs) {
                    input.tapButton(GameButton.Gadget)
                    lastGadgetAtMs = nowMs()
                }
                if (nowMs() - lastPickupAtMs > PathingSettings.PICKUP_INTERVAL_MS) {
                    pickup()
                    lastPickupAtMs = nowMs()
                }
                runtime.delay(PathingSettings.FRAME_INTERVAL_MS)
            } finally {
                miniMap?.release()
                screen?.bgr?.release()
            }
        }
        input.releaseAll()
    }

    /** 在原路线线程上回走，不执行跳跃、攻击、拾取、续盾或再次脱离。 */
    private fun moveBackTo(target: Point2f, waypoint: WaypointForTrack): Boolean {
        val started = nowMs()
        while (true) {
            runtime.delay(0)
            if (nowMs() - started >= 30_000L) {
                log("卡死回退超时，停止移动")
                return false
            }
            val position = recoveryPosition(waypoint)
            if (position.isDefault) {
                log("卡死回退定位失败，停止移动")
                return false
            }
            if (Navigation.getDistance(target.x.toDouble(), target.y.toDouble(), position) <= 1.0) return true
            val orientation = Navigation.getTargetOrientation(target.x.toDouble(), target.y.toDouble(), position)
            if (!rotate.waitUntilRotatedTo(orientation, 5, maxTryTimes = 40)) return false
            try {
                if (!input.holdForward().succeeded) throw HandledException("卡死回退摇杆按下失败")
                runtime.delay(100)
            } finally {
                input.releaseAll()
            }
            runtime.delay(50)
        }
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
        waypoint.action?.let { if (PathingParty.elementFor(it) != null) party?.collect(it) }
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
            // BGI 用 double.TryParse 读最大等待秒数，解析失败时为 0
            var maxWait = 100.0
            if (params.isNotEmpty()) maxWait = params.toDoubleOrNull() ?: 0.0
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
        val miniMap = frame?.let { locator.extract(it.bgr) }
        try {
            return positionAndTime(frame, miniMap, waypoint)
        } finally {
            miniMap?.release()
            frame?.bgr?.release()
        }
    }

    /**
     * 来源：BGI `GetPositionAndTime`。没有新画面或小地图不在画面内时按识别失败 (0,0) 处理，
     * 仍走 `misidentification` 的兜底。
     */
    private fun positionAndTime(frame: CapturedBgrFrame?, miniMap: Mat?, waypoint: WaypointForTrack): PositionSample {
        var position = if (miniMap != null) {
            Navigation.getPosition(miniMap, waypoint.mapName, waypoint.mapMatchMethod)
        } else {
            Point2f.DEFAULT
        }
        var extra = 0
        if (position.isDefault && frame != null && !vision.isInMainUi(frame.bgr)) {
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
                    teleport.openBigMap(waypoint.mapName)
                    try {
                        val game = teleport.positionFromBigMap(waypoint.mapName)
                        if (game != null) {
                            position = MapManager.getMap(waypoint.mapName, waypoint.mapMatchMethod)
                                .convertGenshinMapCoordinatesToImageCoordinates(game)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("地图中心点识别失败！")
                    }
                    teleport.closeMap()
                    waitForMainUi()
                    extra = (nowMs() - started).toInt()
                    log("未识别到具体路径，打开地图计算中心点(${format(position.x.toDouble())}, ${format(position.y.toDouble())})")
                }
            }
        } else {
            previousPosition = position
        }
        return PositionSample(position, extra)
    }

    /** 卡死脱困用的原始定位，不走识别失败兜底（BGI `TrapEscaper` 直接调 `Navigation.GetPosition`）。 */
    private fun recoveryPosition(waypoint: WaypointForTrack): Point2f {
        val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return Point2f.DEFAULT
        return try { locate(frame, waypoint) } finally { frame.bgr.release() }
    }

    /** 对当前回退帧定位，失败时返回空位置，不能沿用旧位置盲走。 */
    private fun locate(frame: CapturedBgrFrame, waypoint: WaypointForTrack): Point2f {
        val miniMap = locator.extract(frame.bgr) ?: return Point2f.DEFAULT
        try {
            return Navigation.getPosition(miniMap, waypoint.mapName, waypoint.mapMatchMethod)
        } finally {
            miniMap.release()
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

    private fun resolveAnomalies(frame: Mat?) {
        val owned = if (frame == null) runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) else null
        val mat = frame ?: owned?.bgr ?: return
        try {
            val close = vision.closeButton(mat)
            if (close != null) {
                // 排除大地图。BGI 在这里直接 return，不再判断剧情
                if (vision.isInBigMap(mat)) return
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

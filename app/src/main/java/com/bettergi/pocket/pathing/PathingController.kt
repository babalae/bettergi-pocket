package com.bettergi.pocket.pathing

import android.os.Build
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.pathing.executor.LocationReadout
import com.bettergi.pocket.pathing.executor.LocationReadoutChecks
import com.bettergi.pocket.pathing.executor.PathExecutor
import com.bettergi.pocket.pathing.executor.PathingFrameSource
import com.bettergi.pocket.pathing.executor.PathingRuntime
import com.bettergi.pocket.pathing.executor.RouteBatchRunner
import com.bettergi.pocket.pathing.input.AccessibilityTouchClient
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.input.TouchClient
import com.bettergi.pocket.pathing.input.TouchReleaseException
import com.bettergi.pocket.pathing.minimap.MiniMapLocator
import com.bettergi.pocket.pathing.settings.PathingRouteSource
import com.bettergi.pocket.pathing.navigation.Navigation
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.pathing.tp.BigMapUi
import com.bettergi.pocket.pathing.tp.MapTeleport
import com.bettergi.pocket.pathing.tp.TpCatalog
import com.bettergi.pocket.pathing.tp.UnavailableBigMapUi
import com.bettergi.pocket.pathing.vision.PathingVision
import com.bettergi.pocket.pathing.vision.UnavailablePathingVision
import java.util.concurrent.CancellationException

/**
 * 挂在前台服务上的地图追踪入口。只读定位不注入触控，路线执行复用同一入口。
 * 两类任务都走这一条 `PathingWorker` 线程。
 */
class PathingController(
    private val frames: PathingFrameSource,
    private val loadLayout: () -> PathingLayout,
    private val genshinInForeground: () -> Boolean?,
    private val log: (String) -> Unit,
    private val onReadoutChanged: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRouteChanged: (Boolean) -> Unit = {},
    private val captureSize: () -> Pair<Int, Int>? = { null },
    private val pathingSettings: () -> PathingSettings = { PathingSettings() },
    private val loadRoutes: () -> List<PathingRouteSource> = { emptyList() },
    private val touch: TouchClient = AccessibilityTouchClient,
    private val vision: PathingVision = UnavailablePathingVision,
    private val bigMap: BigMapUi = UnavailableBigMapUi,
    private val partyAssets: android.content.res.AssetManager? = null,
    private val onProgress: (PathingProgress) -> Unit = {},
) {
    private val lock = Any()
    private var runtime: PathingRuntime? = null
    private var worker: Thread? = null

    private var job = Job.None
    private var routeAfterReadout = false

    fun isRouteRunning(): Boolean = synchronized(lock) { job == Job.Route }

    fun isReadoutRunning(): Boolean = synchronized(lock) { job == Job.Readout }

    fun toggleReadout() {
        synchronized(lock) {
            if (job == Job.Route) {
                log("路线还在执行，请先停止路线")
                return
            }
            val running = worker?.isAlive == true && runtime?.cancelled != true
            if (running) {
                stopLocked()
            } else if (worker?.isAlive != true) {
                startReadoutLocked()
            }
        }
    }

    /** 调试用：只启动坐标显示，已在运行时不重复启动。 */
    fun startReadout() {
        synchronized(lock) {
            if (job == Job.Route) {
                log("路线还在执行，请先停止路线")
                return
            }
            if (worker?.isAlive != true) {
                startReadoutLocked()
            }
        }
    }

    fun startRoute() {
        synchronized(lock) {
            if (job == Job.Readout) {
                routeAfterReadout = true
                stopLocked()
                return
            }
            if (worker?.isAlive == true) {
                stopLocked()
                return
            }
            startRouteLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            routeAfterReadout = false
            stopLocked()
        }
    }

    private fun startReadoutLocked() {
        val layout = try {
            loadLayout()
        } catch (e: Exception) {
            val message = "无法读取标定数据：${e.message ?: e.javaClass.simpleName}"
            log(message)
            onStatus(message)
            return
        }
        val reason = LocationReadoutChecks.refusal(
            capturing = frames.isCapturing(),
            minimapCalibrated = layout.miniMap != null,
            genshinInForeground = genshinInForeground(),
        )
        if (reason != null) {
            log(reason)
            onStatus(reason)
            return
        }
        if (genshinInForeground() == null) {
            log("无法确认前台应用，跳过前台检查")
        }
        val runtime = PathingRuntime(frames)
        this.runtime = runtime
        TaskGate.exclusiveRunning = true
        job = Job.Readout
        onReadoutChanged(true)
        onStatus("正在加载地图…")
        val thread = Thread({
            try {
                LocationReadout(
                    runtime = runtime,
                    layout = layout,
                    matchMethod = pathingSettings().mapMatchingMethod.value,
                    genshinInForeground = genshinInForeground,
                    log = log,
                    onStatus = onStatus,
                ).run()
            } catch (e: CancellationException) {
                // 停止
            } catch (e: Exception) {
                log("定位中断：${e.message ?: e.javaClass.simpleName}")
                onStatus("定位中断")
            } finally {
                TaskGate.exclusiveRunning = false
                onReadoutChanged(false)
                onStatus("已停止")
                synchronized(lock) {
                    if (this.runtime === runtime) {
                        this.runtime = null
                        worker = null
                        job = Job.None
                        if (routeAfterReadout) {
                            routeAfterReadout = false
                            startRouteLocked()
                        }
                    }
                }
            }
        }, WORKER_NAME)
        worker = thread
        try {
            thread.start()
        } catch (e: Exception) {
            TaskGate.exclusiveRunning = false
            job = Job.None
            this.runtime = null
            worker = null
            onReadoutChanged(false)
            log("无法启动定位：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 与启动共用的只读前置检查，界面可以提前展示拒绝原因。 */
    fun preflight(): PathingPreflight = checkRoutePrerequisites().result

    private data class RoutePrerequisites(
        val result: PathingPreflight,
        val layout: PathingLayout? = null,
        val size: Pair<Int, Int>? = null,
    )

    private fun checkRoutePrerequisites(): RoutePrerequisites {
        fun refused(reason: String, action: PathingPreflight.Action? = null) =
            RoutePrerequisites(PathingPreflight(reason, action))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return refused("地图追踪需要 Android 8.0 以上")
        if (!InputAccessibilityService.isConnected()) return refused("无障碍未连接", PathingPreflight.Action.Accessibility)
        val size = captureSize() ?: return refused(LocationReadoutChecks.NEED_CAPTURE, PathingPreflight.Action.ScreenShare)
        if (genshinInForeground() == false) return refused(LocationReadoutChecks.GENSHIN_BACKGROUND)
        val layout = try { loadLayout() } catch (e: Exception) {
            return refused("无法读取标定数据：${e.message ?: e.javaClass.simpleName}")
        }
        val missing = missingCalibration(layout)
        if (missing.isNotEmpty()) return refused("还没标定：${missing.joinToString("、")}")
        return RoutePrerequisites(PathingPreflight(), layout, size)
    }

    private fun startRouteLocked() {
        val prerequisites = checkRoutePrerequisites()
        val check = prerequisites.result
        if (!check.ready) { log(check.reason!!); onStatus(check.reason); return }
        val size = prerequisites.size!!
        val layout = prerequisites.layout!!
        val runtime = PathingRuntime(frames, stopCheck = { routeStopReason() })
        this.runtime = runtime
        TaskGate.exclusiveRunning = true
        job = Job.Route
        onRouteChanged(true)
        onStatus("正在释放触点…")
        val thread = Thread({
            var outcome: String? = null
            try {
                if (!touch.releaseAllAndWait()) throw TouchReleaseException()
                runtime.delay(0)
                log("任务开始前已释放触点")
                onStatus("正在读取路线…")
                val routes = loadRoutes()
                runtime.delay(0)
                if (routes.isEmpty()) {
                    outcome = "还没选择路线"
                    log(outcome)
                    return@Thread
                }
                val input = GameInput(layout, size.first, size.second, touch)
                val locator = MiniMapLocator(layout.miniMap!!)
                val points = try {
                    TpCatalog.load(com.bettergi.pocket.pathing.map.MapManager.assets!!)
                } catch (e: Exception) {
                    log("传送点数据未加载：${e.message ?: "缺少 tp.json"}")
                    emptyList()
                }
                val teleport = MapTeleport(
                    runtime = runtime,
                    input = input,
                    locator = locator,
                    screenWidth = size.first,
                    screenHeight = size.second,
                    points = points,
                    bigMap = bigMap,
                    regionButton = layout.regionButton,
                    log = log,
                )
                val settings = pathingSettings()
                val summary = RouteBatchRunner({ runtime.delay(0) }, log, onStatus, onProgress).run(routes) { route ->
                    // 每条路线一个执行器，卡死次数和跳过状态不跨路线（BGI 每条路线 new PathExecutor）
                    val needsParty = settings.needsParty ||
                        route.waypoints.any { it.action?.let(com.bettergi.pocket.pathing.party.PathingParty::elementFor) != null }
                    val catalog = if (needsParty) com.bettergi.pocket.pathing.party.AvatarCatalog.load(
                        partyAssets ?: error("队伍识别资源未初始化")) else null
                    val classifier = catalog?.let { com.bettergi.pocket.pathing.party.NcnnAvatarClassifier(partyAssets!!, it) }
                    try {
                        val party = classifier?.let { com.bettergi.pocket.pathing.party.PathingParty(
                            runtime, input, com.bettergi.pocket.pathing.party.PartyRecognition(layout, it), settings, vision, log) }
                        val executor = PathExecutor(
                            runtime = runtime,
                            input = input,
                            locator = locator,
                            settings = settings,
                            vision = vision,
                            teleport = teleport,
                            log = log,
                            party = party,
                        )
                        try {
                            executor.execute(route)
                        } finally {
                            input.releaseAll()
                        }
                    } finally { classifier?.close() }
                }
                outcome = summary.message
            } catch (e: TouchReleaseException) {
                outcome = e.message
                log(e.message ?: "触点释放未确认")
            } catch (e: CancellationException) {
                // 用户停止或外部条件打断
            } catch (_: InterruptedException) {
                // 停止也可能发生在跨进程触控等待中，不应作为路线故障显示。
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                val reason = "路线中断：${e.message ?: e.javaClass.simpleName}"
                log(reason)
                outcome = reason
            } finally {
                // 释放触点需要等待主线程；先清中断位，确保停止后的清理能真正完成。
                val interrupted = Thread.interrupted()
                runtime.stopReason?.let {
                    log("地图追踪已停止：$it")
                    outcome = it
                }
                if (!touch.releaseAllAndWait()) {
                    outcome = TouchReleaseException().message
                    log(outcome ?: "触点释放未确认")
                }
                Navigation.release()
                TaskGate.exclusiveRunning = false
                job = Job.None
                synchronized(lock) {
                    if (this.runtime === runtime) this.runtime = null
                }
                onRouteChanged(false)
                onStatus(outcome ?: "已停止")
                if (interrupted) Thread.currentThread().interrupt()
            }
        }, WORKER_NAME)
        worker = thread
        try {
            thread.start()
        } catch (e: Exception) {
            TaskGate.exclusiveRunning = false
            job = Job.None
            this.runtime = null
            worker = null
            onRouteChanged(false)
            log("无法开始路线：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 设计第 6 节的停止来源：屏幕共享停止、无障碍断开、原神离开前台。 */
    private fun routeStopReason(): String? = when {
        !frames.isCapturing() -> "屏幕共享已停止"
        !InputAccessibilityService.isConnected() -> "无障碍已断开"
        genshinInForeground() == false -> LocationReadoutChecks.GENSHIN_BACKGROUND
        else -> null
    }

    private fun missingCalibration(layout: PathingLayout): List<String> {
        val missing = ArrayList<String>()
        if (layout.miniMap == null) missing += "小地图"
        if (layout.joystick == null) missing += "摇杆"
        if (layout.viewArea == null) missing += "视角区"
        if (layout.cameraGain == null) missing += "视角灵敏度"
        if (layout.pickup == null) missing += "拾取"
        val required = listOf(
            GameButton.Jump to "跳跃",
            GameButton.Drop to "下落",
            GameButton.Sprint to "冲刺",
            GameButton.Gadget to "小道具",
            GameButton.Attack to "攻击",
        )
        required.forEach { (button, label) ->
            if (layout.buttons[button] == null) missing += label
        }
        return missing
    }

    private fun stopLocked() {
        runtime?.cancel()
        worker?.interrupt()
    }

    private enum class Job { None, Readout, Route }

    private companion object {
        const val WORKER_NAME = "PathingWorker"
    }
}

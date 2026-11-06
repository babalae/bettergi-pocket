package com.bettergi.pocket.pathing

import android.os.Build
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.pathing.executor.LocationReadout
import com.bettergi.pocket.pathing.executor.LocationReadoutChecks
import com.bettergi.pocket.pathing.executor.PathExecutor
import com.bettergi.pocket.pathing.executor.PathingFrameSource
import com.bettergi.pocket.pathing.executor.PathingRuntime
import com.bettergi.pocket.pathing.input.AccessibilityTouchClient
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.input.TouchClient
import com.bettergi.pocket.pathing.minimap.MiniMapLocator
import com.bettergi.pocket.pathing.model.PathingRoutes
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
 * 挂在前台服务上的地图追踪入口。第二阶段只启动只读定位，不注入触控。
 * 后续路线执行也走这一条 `PathingWorker` 线程。
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
    private val loadRoutes: () -> List<Pair<String, String>> = { emptyList() },
    private val touch: TouchClient = AccessibilityTouchClient,
    private val vision: PathingVision = UnavailablePathingVision,
    private val bigMap: BigMapUi = UnavailableBigMapUi,
) {
    private val lock = Any()
    private var runtime: PathingRuntime? = null
    private var worker: Thread? = null

    private var job = Job.None

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
                log("坐标显示还在运行，请先停止显示")
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
                job = Job.None
                synchronized(lock) {
                    if (this.runtime === runtime) {
                        this.runtime = null
                    }
                }
                onReadoutChanged(false)
                onStatus("已停止")
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

    private fun startRouteLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            log("地图追踪需要 Android 8.0 以上")
            return
        }
        if (!InputAccessibilityService.isConnected()) {
            log("请先开启无障碍服务，才能模拟触控")
            onStatus("无障碍未连接")
            return
        }
        val size = captureSize()
        if (size == null) {
            log(LocationReadoutChecks.NEED_CAPTURE)
            onStatus(LocationReadoutChecks.NEED_CAPTURE)
            return
        }
        if (genshinInForeground() == false) {
            log(LocationReadoutChecks.GENSHIN_BACKGROUND)
            onStatus(LocationReadoutChecks.GENSHIN_BACKGROUND)
            return
        }
        val layout = try {
            loadLayout()
        } catch (e: Exception) {
            val message = "无法读取标定数据：${e.message ?: e.javaClass.simpleName}"
            log(message)
            onStatus(message)
            return
        }
        val missing = missingCalibration(layout)
        if (missing.isNotEmpty()) {
            val message = "还没标定：${missing.joinToString("、")}"
            log(message)
            onStatus(message)
            return
        }
        val routes = try {
            loadRoutes()
        } catch (e: Exception) {
            val message = "无法读取路线：${e.message ?: e.javaClass.simpleName}"
            log(message)
            onStatus(message)
            return
        }
        if (routes.isEmpty()) {
            log("还没选择路线")
            onStatus("还没选择路线")
            return
        }
        val runtime = PathingRuntime(frames, stopCheck = { routeStopReason() })
        this.runtime = runtime
        TaskGate.exclusiveRunning = true
        job = Job.Route
        onRouteChanged(true)
        onStatus(routes.first().first)
        val thread = Thread({
            var outcome: String? = null
            try {
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
                var completed = 0
                var failure: String? = null
                for ((name, json) in routes) {
                    if (runtime.cancelled) break
                    onStatus(name)
                    log("开始路线 $name")
                    val route = try {
                        PathingRoutes.parse(json)
                    } catch (e: Exception) {
                        val reason = "$name 无法解析：${e.message ?: e.javaClass.simpleName}"
                        failure = reason
                        log(reason)
                        continue
                    }
                    // 每条路线一个执行器，卡死次数和跳过状态不跨路线（BGI 每条路线 new PathExecutor）
                    val executor = PathExecutor(
                        runtime = runtime,
                        input = input,
                        locator = locator,
                        settings = settings,
                        vision = vision,
                        teleport = teleport,
                        log = log,
                    )
                    val result = executor.execute(route)
                    if (result.isSuccess) {
                        completed++
                        log("$name 完成")
                    } else {
                        // 与 BGI 调度一致：一条失败不影响后面的路线，停止时才退出
                        val reason = result.errorMessage ?: "未知原因"
                        failure = reason
                        log("$name 未完成：$reason")
                    }
                }
                if (routes.size > 1) log("路线执行结束：完成 $completed / ${routes.size}")
                outcome = failure
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
                touch.releaseAll()
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

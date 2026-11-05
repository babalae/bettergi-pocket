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

    fun toggleReadout() {
        synchronized(lock) {
            val running = worker?.isAlive == true && runtime?.cancelled != true
            if (running) {
                stopLocked()
            } else if (worker?.isAlive != true) {
                startReadoutLocked()
            }
        }
    }

    fun startRoute() {
        synchronized(lock) {
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
            log("无障碍未连接，不检查原神是否在前台")
        }
        val runtime = PathingRuntime(frames)
        this.runtime = runtime
        TaskGate.exclusiveRunning = true
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
        val runtime = PathingRuntime(frames)
        this.runtime = runtime
        TaskGate.exclusiveRunning = true
        onRouteChanged(true)
        onStatus(routes.first().first)
        val thread = Thread({
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
                    log = log,
                )
                val executor = PathExecutor(
                    runtime = runtime,
                    input = input,
                    locator = locator,
                    settings = pathingSettings(),
                    vision = vision,
                    teleport = teleport,
                    log = log,
                )
                for ((name, json) in routes) {
                    if (runtime.cancelled) break
                    onStatus(name)
                    log("开始路线 $name")
                    val result = executor.execute(PathingRoutes.parse(json))
                    if (result.isSuccess) {
                        log("$name 完成")
                    } else {
                        log("$name 未完成：${result.errorMessage ?: "未知原因"}")
                        break
                    }
                }
            } catch (e: CancellationException) {
                // 停止
            } catch (e: Exception) {
                log("路线中断：${e.message ?: e.javaClass.simpleName}")
                onStatus("路线中断")
            } finally {
                TaskGate.exclusiveRunning = false
                synchronized(lock) {
                    if (this.runtime === runtime) this.runtime = null
                }
                onRouteChanged(false)
                onStatus("已停止")
            }
        }, WORKER_NAME)
        worker = thread
        try {
            thread.start()
        } catch (e: Exception) {
            TaskGate.exclusiveRunning = false
            this.runtime = null
            worker = null
            onRouteChanged(false)
            log("无法开始路线：${e.message ?: e.javaClass.simpleName}")
        }
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
            GameButton.Skill to "元素战技",
            GameButton.Gadget to "小道具",
            GameButton.Attack to "攻击",
            GameButton.Party1 to "1 号位",
            GameButton.Party2 to "2 号位",
            GameButton.Party3 to "3 号位",
            GameButton.Party4 to "4 号位",
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

    private companion object {
        const val WORKER_NAME = "PathingWorker"
    }
}

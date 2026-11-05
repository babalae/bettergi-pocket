package com.bettergi.pocket.pathing

import com.bettergi.pocket.pathing.executor.LocationReadout
import com.bettergi.pocket.pathing.executor.LocationReadoutChecks
import com.bettergi.pocket.pathing.executor.PathingFrameSource
import com.bettergi.pocket.pathing.executor.PathingRuntime
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

    private fun stopLocked() {
        runtime?.cancel()
        worker?.interrupt()
    }

    private companion object {
        const val WORKER_NAME = "PathingWorker"
    }
}

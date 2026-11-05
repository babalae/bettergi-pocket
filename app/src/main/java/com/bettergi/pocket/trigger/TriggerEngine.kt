package com.bettergi.pocket.trigger

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.input.AutomationAction
import com.bettergi.pocket.input.AutomationController
import com.bettergi.pocket.pathing.TaskGate
import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.settings.TriggerSettingsRepository

class TriggerEngine(
    private val settingsRepository: TriggerSettingsRepository,
    private val captureController: ScreenCaptureController,
    private val features: List<TriggerFeature>,
    private val actionController: AutomationController,
) {
    private val thread = HandlerThread("TriggerEngine").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        handler.post(tickRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            // 地图追踪独占取帧。不取图、不丢帧，等它结束后再继续实时任务。
            if (TaskGate.exclusiveRunning) {
                handler.postDelayed(this, WAIT_CAPTURE_MS)
                return
            }

            val settings = settingsRepository.get()
            if (!settings.screenShareEnabled) {
                stop()
                return
            }

            if (!captureController.isRunning()) {
                handler.postDelayed(this, WAIT_CAPTURE_MS)
                return
            }

            val enabled = features.filter { it.isEnabled(settings) }
            val needFrame = enabled.any { it.needsFrame(settings) }

            if (enabled.isEmpty()) {
                captureController.discardLatestImages()
                handler.postDelayed(this, TICK_INTERVAL_MS)
                return
            }

            try {
                val emitter = BufferedActionEmitter()
                if (needFrame) {
                    val captured = captureController.acquireLatestBgr()
                    if (captured != null) {
                        CaptureContent.fromBgr(captured.bgr, captured.width, captured.height).use { content ->
                            val tick = FeatureTick(captured.width, captured.height, content)
                            enabled.forEach { it.onTick(tick, settings, emitter) }
                        }
                    } else {
                        val size = captureController.capturedSize()
                        if (size != null) {
                            val tick = FeatureTick(size.first, size.second, content = null)
                            enabled.filterNot { it.needsFrame(settings) }
                                .forEach { it.onTick(tick, settings, emitter) }
                        }
                    }
                } else {
                    captureController.discardLatestImages()
                    val size = captureController.capturedSize()
                    if (size != null) {
                        val tick = FeatureTick(size.first, size.second, content = null)
                        enabled.forEach { it.onTick(tick, settings, emitter) }
                    }
                }
                emitter.flushTo(actionController)
            } catch (e: Exception) {
                Log.e(TAG, "recognize frame failed", e)
            }

            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private class BufferedActionEmitter : ActionEmitter {
        private val pending = ArrayList<AutomationAction>(4)

        override fun emit(action: AutomationAction) {
            pending.add(action)
        }

        fun flushTo(controller: AutomationController) {
            pending.forEach { controller.execute(it) }
            pending.clear()
        }
    }

    private companion object {
        const val TAG = "BetterGI.Engine"
        const val TICK_INTERVAL_MS = 100L
        const val WAIT_CAPTURE_MS = 300L
    }
}

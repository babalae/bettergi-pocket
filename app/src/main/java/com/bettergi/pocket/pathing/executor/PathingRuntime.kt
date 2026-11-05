package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.capture.CapturedBgrFrame
import java.util.concurrent.CancellationException
import kotlin.math.min

/**
 * 地图追踪工作线程上的取帧、等待和取消。
 * BGI 的 async/await 按顺序执行；这里在一条线程上阻塞，`delay` 在停止时抛 [CancellationException]，对应 BGI 的 `ct`。
 *
 * @param stopCheck 每 [STOP_CHECK_INTERVAL_MS] 检查一次外部停止条件（原神离开前台、无障碍断开等），
 *   返回非空原因时停止，原因记在 [stopReason]
 */
class PathingRuntime(
    private val frames: PathingFrameSource,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nanoTime: () -> Long = { System.nanoTime() },
    private val stopCheck: (() -> String?)? = null,
) {
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var stopReason: String? = null
        private set

    private var lastSequence = 0L
    private var stopCheckedAtNs = 0L
    private var stopCheckedOnce = false

    fun cancel() {
        cancelled = true
    }

    fun isCapturing(): Boolean = frames.isCapturing()

    /**
     * 等到 [ms] 结束。停止或线程中断时抛 [CancellationException]。
     */
    fun delay(ms: Long) {
        checkCancelled()
        if (ms <= 0L) return
        val end = nanoTime() + ms * 1_000_000L
        while (nanoTime() < end) {
            checkCancelled()
            val leftMs = (end - nanoTime()) / 1_000_000L
            if (leftMs <= 0L) break
            try {
                sleep(min(leftMs, SLEEP_SLICE_MS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException()
            }
        }
        checkCancelled()
    }

    /**
     * 取一张比上次更新的画面。超时返回 null，本帧按识别失败处理。
     * 返回的 Mat 由调用方释放。
     */
    fun nextFrame(timeoutMs: Long): CapturedBgrFrame? {
        checkCancelled()
        val frame = frames.acquireNewer(lastSequence, timeoutMs)
        if (cancelled || Thread.currentThread().isInterrupted) {
            frame?.bgr?.release()
            throw CancellationException(stopReason)
        }
        if (frame != null) {
            lastSequence = frame.sequence
        }
        return frame
    }

    private fun checkCancelled() {
        val check = stopCheck
        if (!cancelled && check != null) {
            val now = nanoTime()
            if (!stopCheckedOnce || now - stopCheckedAtNs >= STOP_CHECK_INTERVAL_MS * 1_000_000L) {
                stopCheckedOnce = true
                stopCheckedAtNs = now
                check()?.let {
                    stopReason = it
                    cancelled = true
                }
            }
        }
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException(stopReason)
        }
    }

    private companion object {
        const val SLEEP_SLICE_MS = 50L
        const val STOP_CHECK_INTERVAL_MS = 500L
    }
}

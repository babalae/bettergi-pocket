package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.capture.CapturedBgrFrame
import java.util.concurrent.CancellationException
import kotlin.math.min

/**
 * 地图追踪工作线程上的取帧、等待和取消。
 * BGI 的 async/await 按顺序执行；这里在一条线程上阻塞，`delay` 在停止时抛 [CancellationException]，对应 BGI 的 `ct`。
 */
class PathingRuntime(
    private val frames: PathingFrameSource,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nanoTime: () -> Long = { System.nanoTime() },
) {
    @Volatile
    var cancelled: Boolean = false
        private set

    private var lastSequence = 0L

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
            throw CancellationException()
        }
        if (frame != null) {
            lastSequence = frame.sequence
        }
        return frame
    }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException()
        }
    }

    private companion object {
        const val SLEEP_SLICE_MS = 50L
    }
}

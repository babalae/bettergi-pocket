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

    private val gateLock = Object()
    private var pauseRequested = false
    private var quiescedCallback: (() -> Unit)? = null

    fun cancel() {
        cancelled = true
    }

    /**
     * 请求引擎在下一个安全点挂起注入。[onQuiesced] 在引擎线程上回调一次，此时触点已释放、
     * 穿透窗口已恢复，悬浮窗面板可以安全展开。由 [PathingController] 切换到主线程。
     */
    fun requestPause(onQuiesced: () -> Unit) {
        synchronized(gateLock) {
            pauseRequested = true
            quiescedCallback = onQuiesced
        }
    }

    /** 恢复注入；未请求挂起时无操作。 */
    fun resume() {
        synchronized(gateLock) {
            if (!pauseRequested) return
            pauseRequested = false
            quiescedCallback = null
            (gateLock as Object).notifyAll()
        }
    }

    /**
     * 线程退出（正常结束、取消或异常）时的兜底：引擎不再注入手势即视为静默，
     * 回调一次，避免展开请求无限等待。
     */
    fun notifyQuiescedOnExit() {
        val callback = synchronized(gateLock) {
            pauseRequested = false
            val callback = quiescedCallback
            quiescedCallback = null
            (gateLock as Object).notifyAll()
            callback
        }
        callback?.invoke()
    }

    /**
     * 安全点：已请求挂起时先执行 [quiesce]（释放全部触点），等待穿透窗口的延迟恢复结束，
     * 回调静默确认，然后阻塞到 [resume]；停止或中断时抛 [CancellationException]。
     * 未请求挂起时立即返回。
     */
    fun pausePoint(quiesce: () -> Unit) {
        val callback = synchronized(gateLock) { if (pauseRequested) quiescedCallback else return } ?: return
        quiesce()
        // 覆盖穿透的延迟 restore（40ms）与跨进程抬手确认，保证回调时窗口已恢复可见可交互。
        sleep(QUIESCE_SETTLE_MS)
        // 等待期间若已 resume 又再次 requestPause，本次确认作废，留给下一个安全点。
        val stillCurrent = synchronized(gateLock) { quiescedCallback === callback }
        if (stillCurrent) {
            synchronized(gateLock) { quiescedCallback = null }
            callback()
        }
        synchronized(gateLock) {
            while (pauseRequested && !cancelled && !Thread.currentThread().isInterrupted) {
                try {
                    (gateLock as Object).wait(STOP_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                checkCancelled()
            }
        }
        checkCancelled()
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
        const val QUIESCE_SETTLE_MS = 150L
    }
}

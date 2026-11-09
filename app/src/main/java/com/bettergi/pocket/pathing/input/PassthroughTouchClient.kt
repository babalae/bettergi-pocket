package com.bettergi.pocket.pathing.input

import android.os.Handler
import android.os.Looper
import com.bettergi.pocket.overlay.OverlayWindowController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 地图追踪的触控先让开挡住落点的悬浮窗，再把笔画交给无障碍服务。
 * 悬浮窗标志只能在主线程改，所以从路线线程进来时会切过去等它改完再派发。
 */
class PassthroughTouchClient(
    private val overlay: OverlayWindowController,
    private val delegate: TouchClient = AccessibilityTouchClient,
) : TouchClient {
    private val main = Handler(Looper.getMainLooper())
    private var holdArmed = false
    private var dragArmed = false
    private var lastDragX = 0
    private var lastDragY = 0
    private val armedButtons = HashSet<String>()

    override fun padHold(x: Int, y: Int): Boolean = onMain {
        // PathExecutor 每帧重复按下摇杆；按住期间不重复登记穿透，padRelease 仅对应一次 restore。
        val armed = if (holdArmed) false else overlay.prepareAreaPassthrough(x, y, x, y)
        val ok = delegate.padHold(x, y)
        if (armed) {
            if (ok) holdArmed = true else overlay.restoreClickPassthrough()
        }
        ok
    }

    override fun padRelease(): Boolean = onMain {
        val ok = delegate.padRelease()
        if (holdArmed) {
            holdArmed = false
            overlay.restoreClickPassthrough()
        }
        ok
    }

    override fun buttonDown(id: String, x: Int, y: Int): Boolean = onMain {
        // 同一按键重复按下时 armedButtons 已去重，穿透登记不随之叠加。
        val armed = if (id in armedButtons) false else overlay.prepareAreaPassthrough(x, y, x, y)
        val ok = delegate.buttonDown(id, x, y)
        if (armed) {
            if (ok) armedButtons += id else overlay.restoreClickPassthrough()
        }
        ok
    }

    override fun buttonUp(id: String): Boolean = onMain {
        val ok = delegate.buttonUp(id)
        if (armedButtons.remove(id)) overlay.restoreClickPassthrough()
        ok
    }

    override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        var armed = false
        if (!onMain { armed = overlay.prepareAreaPassthrough(x, y, x, y); true }) {
            // 超时/中断时 block 仍可能在主线程排队执行 prepare，补发一次 restore 避免计数泄漏。
            main.post { overlay.restoreClickPassthrough() }
            return false
        }
        // WindowManager 的输入窗口更新异步生效；路线线程等待两帧，避免 DOWN 仍落到旧悬浮窗。
        if (armed && Looper.myLooper() != main.looper) {
            val ready = CountDownLatch(1)
            main.postDelayed({ ready.countDown() }, 64L)
            val updated = try { ready.await(SYNC_TIMEOUT_MS,TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            if (!updated) {
                main.post { overlay.restoreClickPassthrough() }
                return false
            }
        }
        return onMain {
            val ok = delegate.tap(x, y, durationMs)
            if (armed) scheduleRestore(if (ok) durationMs + RESTORE_DELAY_MS else 0L)
            ok
        }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = onMain {
        val armed = overlay.prepareAreaPassthrough(x1, y1, x2, y2)
        val ok = delegate.swipe(x1, y1, x2, y2, durationMs)
        if (armed) scheduleRestore(if (ok) durationMs + RESTORE_DELAY_MS else 0L)
        ok
    }

    override fun dragBegin(x: Int, y: Int): Boolean = onMain {
        lastDragX = x
        lastDragY = y
        // 上一段拖动未正常结束时 dragArmed 仍为真，不重复登记穿透。
        val armed = if (dragArmed) false else overlay.prepareAreaPassthrough(x, y, x, y)
        val ok = delegate.dragBegin(x, y)
        if (armed) {
            if (ok) dragArmed = true else overlay.restoreClickPassthrough()
        }
        ok
    }

    override fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int, durationMs: Long): Boolean = onMain {
        val radius = (maxOf(startSpan, endSpan) + 1) / 2
        val armed = overlay.prepareAreaPassthrough(centerX - radius, centerY, centerX + radius, centerY)
        val ok = delegate.pinch(centerX, centerY, startSpan, endSpan, durationMs)
        if (armed) scheduleRestore(if (ok) durationMs + RESTORE_DELAY_MS else 0L)
        ok
    }

    override fun dragStep(x: Int, y: Int, durationMs: Long): Boolean = onMain {
        if (!dragArmed && overlay.prepareAreaPassthrough(lastDragX, lastDragY, x, y)) {
            dragArmed = true
        }
        lastDragX = x
        lastDragY = y
        val ok = delegate.dragStep(x, y, durationMs)
        if (!ok && dragArmed) {
            dragArmed = false
            overlay.restoreClickPassthrough()
        }
        ok
    }

    override fun dragEnd(): Boolean = onMain {
        val ok = delegate.dragEnd()
        if (dragArmed) {
            dragArmed = false
            scheduleRestore(RESTORE_DELAY_MS)
        }
        ok
    }

    override fun releaseAll(): Boolean = onMain {
        val ok = delegate.releaseAll()
        restoreHolds()
        ok
    }

    override fun releaseAllAndWait(timeoutMs: Long): Boolean {
        if (Looper.myLooper() == main.looper) {
            releaseAll()
            return false
        }
        var interrupted = Thread.interrupted()
        try {
            // 不占住应用主线程；实际抬手完成后再恢复悬浮窗的触摸拦截。
            val ok = delegate.releaseAllAndWait(timeoutMs)
            interrupted = Thread.interrupted() || interrupted
            return onMain { restoreHolds(); true } && ok
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun restoreHolds() {
        var armed = armedButtons.size
        armedButtons.clear()
        if (holdArmed) {
            holdArmed = false
            armed++
        }
        if (dragArmed) {
            dragArmed = false
            armed++
        }
        repeat(armed) { overlay.restoreClickPassthrough() }
    }

    private fun scheduleRestore(delayMs: Long) {
        if (delayMs <= 0L) {
            overlay.restoreClickPassthrough()
        } else {
            main.postDelayed({ overlay.restoreClickPassthrough() }, delayMs)
        }
    }

    private fun onMain(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == main.looper) return block()
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val posted = main.post {
            try {
                result.set(block())
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        val completed = try {
            latch.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // 停止会中断路线线程，主线程仍会执行已提交的释放；把取消交回 runtime 处理。
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) return false
        return result.get()
    }

    private companion object {
        const val RESTORE_DELAY_MS = 40L
        const val SYNC_TIMEOUT_MS = 3000L
    }
}

package com.bettergi.pocket.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把持续触点（摇杆、长按）和瞬时触点（点按、滑动）合成同一次 [GestureDescription]。
 *
 * `dispatchGesture` 会取消本服务正在进行的手势，所以每次派发都要把仍按住的触点用
 * `continueStroke` 续进新手势。被系统取消（例如用户自己触屏）时丢掉旧笔画并重建。
 * `continueStroke` 需要 Android 8.0。更低版本只派发不含持续触点的点按。
 *
 * 系统（`MotionEventInjector.prepareToContinueOldGesture`）对续接有三条硬要求：
 * 1. 续段起点必须与上一段终点完全相同，所以每个触点记录上一段的实际终点。
 * 2. 新手势第 0 ms 的触点数必须等于上一手势未结束的触点数，所以新笔画从第 1 ms 开始。
 * 3. 续接手势排在上一手势已排期事件之后执行，所以按住段要短，且位置没变时不重复派发。
 * `willContinue` 的笔画结束后触点保持按下，直到续段到来，没有超时。
 * 4. 等待上一手势的完成回调后才派发下一段。尤其不能用下一个 80 ms 视角滑动
 *    打断还没结束的滑动；Android 16 会因此残留注入触点，并拒绝后续 DOWN/MOVE。
 */
class GestureComposer(
    private val service: AccessibilityService,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private class Finger(
        val id: String,
        /** 目标位置。 */
        var x: Float,
        var y: Float,
    ) {
        /** 上一段笔画的终点，续段从这里开始。 */
        var endX: Float = x
        var endY: Float = y
        var segmentMs: Long = HOLD_SLICE_MS
        var stroke: GestureDescription.StrokeDescription? = null
        var lift: Boolean = false
    }

    private val fingers = LinkedHashMap<String, Finger>()
    private var generation = 0
    private var externalRestarts = 0
    private var dispatching = false
    private var pendingHoldChange = false
    private val pendingInstants = ArrayDeque<Instant>()

    private val renew = Runnable {
        if (fingers.any { !it.value.lift }) {
            emit()
        }
    }

    fun padHold(x: Int, y: Int): Boolean = buttonDown(PAD_ID, x, y)

    fun padRelease(): Boolean = buttonUp(PAD_ID)

    fun buttonDown(id: String, x: Int, y: Int): Boolean = runSync {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || x < 0 || y < 0) return@runSync false
        val fx = x.toFloat()
        val fy = y.toFloat()
        val existing = fingers[id]
        if (existing != null && !existing.lift && existing.stroke != null && existing.x == fx && existing.y == fy) {
            // 已经按在这里。再派发只会在系统队列末尾多排一段，累积延迟。
            return@runSync true
        }
        val finger = existing ?: Finger(id, fx, fy).also { fingers[id] = it }
        finger.x = fx
        finger.y = fy
        finger.segmentMs = HOLD_SLICE_MS
        finger.lift = false
        externalRestarts = 0
        emit()
    }

    fun buttonUp(id: String): Boolean = runSync {
        val finger = fingers[id] ?: return@runSync true
        finger.segmentMs = LIFT_MS
        finger.lift = true
        externalRestarts = 0
        emit()
    }

    fun tap(x: Int, y: Int, durationMs: Long): Boolean = runSync {
        if (x < 0 || y < 0) return@runSync false
        val duration = durationMs.coerceAtLeast(1L)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return@runSync dispatchSimple(pointPath(x.toFloat(), y.toFloat()), duration)
        }
        emit(listOf(Instant(pointPath(x.toFloat(), y.toFloat()), duration)))
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = runSync {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return@runSync false
        val duration = durationMs.coerceAtLeast(1L)
        val path = movePath(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return@runSync dispatchSimple(path, duration)
        }
        emit(listOf(Instant(path, duration)))
    }

    fun dragBegin(x: Int, y: Int): Boolean = buttonDown(DRAG_ID, x, y)

    fun dragStep(x: Int, y: Int, durationMs: Long): Boolean = runSync {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || x < 0 || y < 0) return@runSync false
        val finger = fingers[DRAG_ID] ?: return@runSync false
        finger.x = x.toFloat()
        finger.y = y.toFloat()
        finger.segmentMs = durationMs.coerceAtLeast(1L)
        finger.lift = false
        emit()
    }

    fun dragEnd(): Boolean = buttonUp(DRAG_ID)

    fun releaseAll(): Boolean = runSync {
        // 停止后不再执行排队的点按或转向；正在执行的笔画完成后立即松开持续触点。
        pendingInstants.clear()
        handler.removeCallbacks(renew)
        if (fingers.isEmpty()) return@runSync true
        fingers.values.forEach {
            it.lift = true
            it.segmentMs = LIFT_MS
        }
        emit()
    }

    fun hasHold(): Boolean = fingers.isNotEmpty()

    private class Instant(val path: Path, val durationMs: Long)

    private fun emit(instants: List<Instant> = emptyList()): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (dispatching) {
            pendingInstants.addAll(instants)
            pendingHoldChange = true
            return true
        }
        // 从未派发过的触点抬起时不用派发，否则会变成一次多余的点按
        fingers.values.removeAll { it.lift && it.stroke == null }
        // AOSP MotionEventInjector 不为静止的继续按住生成 MOVE；空事件列表会回报取消。
        // willContinue 本身已保留按住状态，只在移动、新增、抬起或有瞬时动作时续接。
        if (instants.isEmpty() && fingers.values.none {
                it.stroke == null || it.lift || it.x != it.endX || it.y != it.endY
            }) return true
        val continuing = fingers.values.any { it.stroke != null }
        val freshStart = if (continuing) FRESH_STROKE_DELAY_MS else 0L
        val instantEnd = instants.maxOfOrNull { freshStart + it.durationMs } ?: 0L

        val prepared = ArrayList<Pair<Finger, GestureDescription.StrokeDescription>>(fingers.size)
        for (finger in fingers.values) {
            val path = movePath(finger.endX, finger.endY, finger.x, finger.y)
            val start = if (finger.stroke == null) freshStart else 0L
            val duration = if (finger.lift) {
                finger.segmentMs.coerceAtLeast(1L)
            } else {
                // 按住的触点陪瞬时笔画一起结束，下一次派发才能紧接着排上
                maxOf(finger.segmentMs, instantEnd - start, 1L)
            }
            prepared += finger to continueOrNew(finger.stroke, path, start, duration, willContinue = !finger.lift)
        }
        val total = prepared.size + instants.size
        if (total == 0) return true
        if (total > GestureDescription.getMaxStrokeCount()) {
            return false
        }
        val builder = GestureDescription.Builder()
        prepared.forEach { builder.addStroke(it.second) }
        instants.forEach {
            builder.addStroke(GestureDescription.StrokeDescription(it.path, freshStart, it.durationMs, false))
        }
        generation += 1
        val gen = generation
        dispatching = true
        val accepted = service.dispatchGesture(
            builder.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.post { onDispatchCompleted(gen) }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.post { onExternalCancel(gen) }
                }
            },
            handler,
        )
        if (!accepted) {
            dispatching = false
            prepared.forEach { it.first.stroke = null }
            return false
        }
        prepared.forEach { (finger, stroke) ->
            if (finger.lift) {
                fingers.remove(finger.id)
            } else {
                finger.stroke = stroke
                finger.endX = finger.x
                finger.endY = finger.y
                finger.segmentMs = HOLD_SLICE_MS
            }
        }
        scheduleRenew()
        return true
    }

    /** 连续笔画在完成后仍按下；只在系统确认完成后消费下一个瞬时动作或触点更新。 */
    private fun onDispatchCompleted(gen: Int) {
        if (gen != generation) return
        dispatching = false
        val instant = pendingInstants.removeFirstOrNull()
        if (instant != null || pendingHoldChange) {
            pendingHoldChange = false
            emit(if (instant == null) emptyList() else listOf(instant))
        }
    }

    private fun onExternalCancel(gen: Int) {
        if (gen != generation) return
        dispatching = false
        pendingInstants.clear()
        pendingHoldChange = false
        Log.w("BetterGI.Gesture", "手势被取消，重建持续触点 count=${fingers.size}")
        fingers.values.forEach { it.stroke = null }
        if (fingers.isEmpty()) return
        externalRestarts += 1
        if (externalRestarts > MAX_EXTERNAL_RESTARTS) {
            fingers.clear()
            handler.removeCallbacks(renew)
            return
        }
        emit()
    }

    /** 定期检查待续接状态；静止时由 emit 跳过，不能派发空续段。 */
    private fun scheduleRenew() {
        handler.removeCallbacks(renew)
        if (fingers.isNotEmpty()) {
            handler.postDelayed(renew, RENEW_AFTER_MS)
        }
    }

    private fun continueOrNew(
        previous: GestureDescription.StrokeDescription?,
        path: Path,
        startMs: Long,
        duration: Long,
        willContinue: Boolean,
    ): GestureDescription.StrokeDescription {
        if (previous == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return GestureDescription.StrokeDescription(path, startMs, duration, willContinue)
        }
        return try {
            previous.continueStroke(path, 0, duration, willContinue)
        } catch (_: IllegalStateException) {
            GestureDescription.StrokeDescription(path, startMs, duration, willContinue)
        }
    }

    private fun dispatchSimple(path: Path, duration: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun runSync(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == handler.looper) return block()
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val posted = handler.post {
            try {
                result.set(block())
            } catch (_: IllegalArgumentException) {
                // 坐标为负等非法路径。不能让异常打到主线程 Looper 上把无障碍进程带崩
                result.set(false)
            } finally {
                latch.countDown()
            }
        }
        if (!posted || !latch.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return false
        return result.get()
    }

    private companion object {
        const val PAD_ID = "pad"
        const val DRAG_ID = "drag"

        /** 按住和改变方向时每段的时长。续段排在队尾，太长会让后面的点按变慢。 */
        const val HOLD_SLICE_MS = 30L
        const val RENEW_AFTER_MS = 400L
        const val LIFT_MS = 20L

        /** 续接手势里新笔画的起始时刻，见类注释第 2 条。 */
        const val FRESH_STROKE_DELAY_MS = 1L
        const val MAX_EXTERNAL_RESTARTS = 3
        const val SYNC_TIMEOUT_MS = 3000L

        /** 零长度路径由系统按点按处理，终点等于起点，便于续接。 */
        fun pointPath(x: Float, y: Float): Path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }

        fun movePath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
            return Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
        }
    }
}

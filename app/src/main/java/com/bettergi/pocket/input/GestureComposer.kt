package com.bettergi.pocket.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把持续触点（摇杆、长按）和瞬时触点（点按、滑动）合成同一次 [GestureDescription]。
 *
 * `dispatchGesture` 会取消本服务正在进行的手势，所以每次派发都要把仍按住的触点用
 * `continueStroke` 续进新手势。被系统取消（例如用户自己触屏）时丢掉旧笔画并重建。
 * `continueStroke` 需要 Android 8.0。更低版本只派发不含持续触点的点按。
 */
class GestureComposer(
    private val service: AccessibilityService,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private class Finger(
        val id: String,
        var x: Float,
        var y: Float,
        var fromX: Float = x,
        var fromY: Float = y,
        var segmentMs: Long = HOLD_SLICE_MS,
        var stroke: GestureDescription.StrokeDescription? = null,
        var lift: Boolean = false,
    )

    private val fingers = LinkedHashMap<String, Finger>()
    private var generation = 0
    private var externalRestarts = 0

    private val renew = Runnable {
        if (fingers.any { !it.value.lift }) {
            emit()
        }
    }

    fun padHold(x: Int, y: Int): Boolean = buttonDown(PAD_ID, x, y)

    fun padRelease(): Boolean = buttonUp(PAD_ID)

    fun buttonDown(id: String, x: Int, y: Int): Boolean = runSync {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runSync false
        val finger = fingers.getOrPut(id) { Finger(id, x.toFloat(), y.toFloat()) }
        finger.fromX = finger.x
        finger.fromY = finger.y
        finger.x = x.toFloat()
        finger.y = y.toFloat()
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
        val duration = durationMs.coerceAtLeast(1L)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || fingers.isEmpty()) {
            return@runSync dispatchSimple(pointPath(x.toFloat(), y.toFloat()), duration)
        }
        emit(listOf(instantStroke(pointPath(x.toFloat(), y.toFloat()), duration)))
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = runSync {
        val duration = durationMs.coerceAtLeast(1L)
        val path = movePath(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || fingers.isEmpty()) {
            return@runSync dispatchSimple(path, duration)
        }
        emit(listOf(instantStroke(path, duration)))
    }

    fun dragBegin(x: Int, y: Int): Boolean = buttonDown(DRAG_ID, x, y)

    fun dragStep(x: Int, y: Int, durationMs: Long): Boolean = runSync {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runSync false
        val finger = fingers[DRAG_ID] ?: return@runSync false
        finger.fromX = finger.x
        finger.fromY = finger.y
        finger.x = x.toFloat()
        finger.y = y.toFloat()
        finger.segmentMs = durationMs.coerceAtLeast(1L)
        finger.lift = false
        emit()
    }

    fun dragEnd(): Boolean = buttonUp(DRAG_ID)

    fun releaseAll(): Boolean = runSync {
        if (fingers.isEmpty()) return@runSync true
        fingers.values.forEach {
            it.lift = true
            it.segmentMs = LIFT_MS
        }
        emit()
    }

    fun hasHold(): Boolean = fingers.isNotEmpty()

    private fun emit(instants: List<GestureDescription.StrokeDescription> = emptyList()): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val prepared = ArrayList<Pair<Finger, GestureDescription.StrokeDescription>>(fingers.size)
        for (finger in fingers.values) {
            val path = movePath(finger.fromX, finger.fromY, finger.x, finger.y)
            val duration = finger.segmentMs.coerceAtLeast(1L)
            val willContinue = !finger.lift
            val stroke = continueOrNew(finger.stroke, path, duration, willContinue)
            prepared += finger to stroke
            finger.fromX = finger.x
            finger.fromY = finger.y
        }
        val maxStrokes = GestureDescription.getMaxStrokeCount()
        if (prepared.size + instants.size > maxStrokes || prepared.size + instants.size == 0) {
            return false
        }
        val builder = GestureDescription.Builder()
        prepared.forEach { builder.addStroke(it.second) }
        instants.forEach { builder.addStroke(it) }
        generation += 1
        val gen = generation
        val accepted = service.dispatchGesture(
            builder.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.post { onExternalCancel(gen) }
                }
            },
            handler,
        )
        if (!accepted) {
            prepared.forEach { it.first.stroke = null }
            return false
        }
        prepared.forEach { (finger, stroke) ->
            if (finger.lift) {
                fingers.remove(finger.id)
            } else {
                finger.stroke = stroke
            }
        }
        scheduleRenew()
        return true
    }

    private fun onExternalCancel(gen: Int) {
        if (gen != generation) return
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

    private fun scheduleRenew() {
        handler.removeCallbacks(renew)
        if (fingers.isNotEmpty()) {
            handler.postDelayed(renew, RENEW_AFTER_MS)
        }
    }

    private fun continueOrNew(
        previous: GestureDescription.StrokeDescription?,
        path: Path,
        duration: Long,
        willContinue: Boolean,
    ): GestureDescription.StrokeDescription {
        if (previous == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return GestureDescription.StrokeDescription(path, 0, duration, willContinue)
        }
        return try {
            previous.continueStroke(path, 0, duration, willContinue)
        } catch (_: IllegalStateException) {
            GestureDescription.StrokeDescription(path, 0, duration, willContinue)
        }
    }

    private fun instantStroke(path: Path, duration: Long): GestureDescription.StrokeDescription {
        return GestureDescription.StrokeDescription(path, 0, duration, false)
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
        const val HOLD_SLICE_MS = 500L
        const val RENEW_AFTER_MS = 400L
        const val LIFT_MS = 20L
        const val MAX_EXTERNAL_RESTARTS = 3
        const val SYNC_TIMEOUT_MS = 3000L

        fun pointPath(x: Float, y: Float): Path = movePath(x, y, x, y)

        fun movePath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
            return Path().apply {
                moveTo(x1, y1)
                if (x1 == x2 && y1 == y2) {
                    lineTo(x2 + 0.5f, y2)
                } else {
                    lineTo(x2, y2)
                }
            }
        }
    }
}

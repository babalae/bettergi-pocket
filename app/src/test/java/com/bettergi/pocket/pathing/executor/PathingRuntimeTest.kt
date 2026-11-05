package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.pathing.PathingTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.CancellationException

class PathingRuntimeTest {
    @Test
    fun `next frame skips anything that is not newer`() {
        val first = frame(1)
        val same = frame(1)
        val second = frame(2)
        val source = object : PathingFrameSource {
            private val queued = ArrayDeque(listOf(first, same, second))
            override fun isCapturing() = true
            override fun acquireNewer(afterSequence: Long, timeoutMs: Long): CapturedBgrFrame? {
                val next = queued.removeFirstOrNull() ?: return null
                return if (next.sequence > afterSequence) next else {
                    next.bgr.release()
                    null
                }
            }
        }
        val runtime = PathingRuntime(source, sleep = {}, nanoTime = { 0L })
        val got = runtime.nextFrame(0)
        assertEquals(1L, got!!.sequence)
        got.bgr.release()
        assertNull(runtime.nextFrame(0))
        val later = runtime.nextFrame(0)
        assertEquals(2L, later!!.sequence)
        later.bgr.release()
    }

    @Test
    fun `delay waits the requested time and cancel aborts it`() {
        var now = 0L
        val slept = ArrayList<Long>()
        val runtime = PathingRuntime(
            frames = idleFrames(),
            sleep = { ms ->
                slept += ms
                now += ms * 1_000_000L
            },
            nanoTime = { now },
        )
        runtime.delay(120)
        assertEquals(120L, slept.sum())

        runtime.cancel()
        assertThrows(CancellationException::class.java) {
            runtime.delay(50)
        }
    }

    private fun idleFrames() = object : PathingFrameSource {
        override fun isCapturing() = false
        override fun acquireNewer(afterSequence: Long, timeoutMs: Long) = null
    }

    private fun frame(sequence: Long): CapturedBgrFrame {
        return CapturedBgrFrame(1, 1, Mat(1, 1, CvType.CV_8UC3), sequence)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            PathingTestData.ensureOpenCv()
        }
    }
}

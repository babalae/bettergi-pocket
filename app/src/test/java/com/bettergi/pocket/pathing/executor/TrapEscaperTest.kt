package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.input.TouchReleaseException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TrapEscaperTest {
    @Test
    fun `retreat goes halfway toward previous point on caller thread and releases before retry`() {
        val events = mutableListOf<String>()
        val thread = Thread.currentThread()
        val escaper = TrapEscaper({ assertSame(thread, Thread.currentThread()); events += "release" }, {})
        assertTrue(escaper.retreat(Point2f(30f, 50f), Point2f(10f, 10f)) {
            assertSame(thread, Thread.currentThread())
            assertEquals(Point2f(20f, 30f), it)
            events += "walk"
            true
        })
        events += "retry"
        assertEquals(listOf("release", "walk", "release", "retry"), events)
    }

    @Test
    fun `cancel error and unsuccessful retreat all release before returning`() {
        for (error in listOf<RuntimeException?>(null, CancellationException(), IllegalStateException("定位失败"))) {
            var releases = 0
            val escaper = TrapEscaper({ releases++ }, {})
            try {
                assertFalse(escaper.retreat(Point2f(30f, 50f), Point2f(10f, 10f)) {
                    error?.let { throw it }
                    false
                })
                assertNull(error)
            } catch (caught: RuntimeException) {
                assertSame(error, caught)
            }
            assertEquals(2, releases)
        }
    }

    @Test
    fun `invalid or too close previous point never causes blind movement`() {
        var releases = 0
        val escaper = TrapEscaper({ releases++ }, {})
        for (previous in listOf(null, Point2f.DEFAULT, Point2f(Float.NaN, 1f), Point2f(30.5f, 50f))) {
            assertFalse(escaper.retreat(Point2f(30f, 50f), previous) { fail("无效点不能移动"); true })
        }
        assertEquals(8, releases)
    }

    @Test(expected = TouchReleaseException::class)
    fun `unconfirmed cleanup prevents retreat from starting`() {
        TrapEscaper({ throw TouchReleaseException() }, {}).retreat(Point2f(30f, 50f), Point2f(10f, 10f)) {
            fail("释放失败后不能移动")
            true
        }
    }
}

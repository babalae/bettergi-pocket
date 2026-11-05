package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.pathing.common.Point2f
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationReportTest {
    @Test
    fun `formats game coordinates and camera angle`() {
        val line = LocationReport.line(
            image = Point2f(34520f, 20020f),
            game = Point2f(-876f, 2278f),
            angleDegrees = 91.26f,
            elapsedMs = 12,
        )
        assertEquals("坐标 -876.0, 2278.0  视角 91.3°  12ms", line)
        assertEquals("ok", LocationReport.kind(line))
    }

    @Test
    fun `failed recognition keeps the angle`() {
        val line = LocationReport.line(Point2f.DEFAULT, game = null, angleDegrees = 10f, elapsedMs = 4)
        assertEquals("定位失败  视角 10.0°  4ms", line)
        assertEquals("miss", LocationReport.kind(line))
    }

    @Test
    fun `refuses to start without capture, calibration, or the game`() {
        assertEquals(
            LocationReadoutChecks.NEED_CAPTURE,
            LocationReadoutChecks.refusal(capturing = false, minimapCalibrated = true, genshinInForeground = true),
        )
        assertEquals(
            LocationReadoutChecks.NEED_CALIBRATION,
            LocationReadoutChecks.refusal(capturing = true, minimapCalibrated = false, genshinInForeground = true),
        )
        assertEquals(
            LocationReadoutChecks.GENSHIN_BACKGROUND,
            LocationReadoutChecks.refusal(capturing = true, minimapCalibrated = true, genshinInForeground = false),
        )
        assertNull(LocationReadoutChecks.refusal(capturing = true, minimapCalibrated = true, genshinInForeground = null))
    }

    @Test
    fun `uncalibrated layout stops before loading the map`() {
        val logs = ArrayList<String>()
        val statuses = ArrayList<String>()
        LocationReadout(
            runtime = PathingRuntime(idleFrames()),
            layout = PathingLayout(referenceSize = null, miniMap = null),
            log = logs::add,
            onStatus = statuses::add,
            nowMs = { 0L },
        ).run()
        assertEquals(listOf(LocationReadoutChecks.NEED_CALIBRATION), logs)
        assertEquals(logs, statuses)
        assertTrue(logs.single().contains("layout.json"))
    }

    private fun idleFrames() = object : PathingFrameSource {
        override fun isCapturing(): Boolean = true
        override fun acquireNewer(afterSequence: Long, timeoutMs: Long) = null
    }
}

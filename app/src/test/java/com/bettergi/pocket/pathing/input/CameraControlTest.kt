package com.bettergi.pocket.pathing.input

import com.bettergi.pocket.pathing.GameButton
import com.bettergi.pocket.pathing.JoystickLayout
import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.pathing.ViewAreaLayout
import com.bettergi.pocket.pathing.executor.PathingFrameSource
import com.bettergi.pocket.pathing.executor.PathingRuntime
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlTest {
    @Test
    fun `angle diff matches the BetterGI wrap`() {
        assertEquals(90f, CameraControl.angleDiff(90f, 0f))
        assertEquals(-90f, CameraControl.angleDiff(0f, 90f))
        assertEquals(20f, CameraControl.angleDiff(10f, 350f))
        assertEquals(0f, CameraControl.angleDiff(12f, 12f))
    }

    @Test
    fun `gain table and banker rounding follow CameraRotateTask`() {
        assertEquals(1.0, CameraControl.controlRatio(5f), 0.0)
        assertEquals(2.0, CameraControl.controlRatio(5.1f), 0.0)
        assertEquals(3.0, CameraControl.controlRatio(90f), 0.0)
        assertEquals(4.0, CameraControl.controlRatio(90.1f), 0.0)
        // -2 * 5.25 = -10.5，银行家舍入到 -10
        assertEquals(-10, CameraControl.swipePixels(5.25f, 1.0))
        assertEquals(-270, CameraControl.swipePixels(90f, 1.0))
    }

    @Test
    fun `swipe is clamped inside the view area`() {
        val area = IntRect(100, 100, 200, 80)
        val segment = CameraControl.clampSwipe(area, 1000)
        assertTrue(segment.x1 >= area.left && segment.x2 < area.right)
        assertEquals(area.centerY, segment.y1)
        assertEquals(segment.x2 - segment.x1, segment.appliedPixels)
        assertEquals(0, CameraControl.clampSwipe(area, 0).appliedPixels)
    }

    @Test
    fun `least squares accepts a linear response and rejects a kink`() {
        val linear = listOf(
            GainSample(100.0, 20.0),
            GainSample(-100.0, -20.0),
            GainSample(200.0, 40.0),
            GainSample(-200.0, -40.0),
        )
        val q = CameraControl.fitQm(linear)
        assertEquals(0.2, q, 1e-9)
        assertTrue(CameraControl.linearity(linear, q).ok)

        val kinked = listOf(GainSample(100.0, 20.0), GainSample(-100.0, -40.0))
        assertTrue(!CameraControl.linearity(kinked, CameraControl.fitQm(kinked)).ok)
    }

    @Test
    fun `layout keeps empty controls and parses calibrated ones`() {
        val empty = PathingLayout.parse("""{"version":1,"referenceSize":null,"miniMap":null,"buttons":null,"cameraGain":null}""")
        assertNull(empty.joystick)
        assertNull(empty.cameraGain)
        assertTrue(empty.buttons.isEmpty())

        val parsed = PathingLayout.parse(
            """
            {
              "referenceSize": [1000, 1000],
              "joystick": { "center": [200, 200], "radius": 100 },
              "viewArea": { "bbox": "rect(400, 200, 500, 600)" },
              "buttons": { "jump": [900, 800] },
              "pickup": [500, 100],
              "cameraGain": 1.5
            }
            """.trimIndent(),
        )
        assertEquals(JoystickLayout(200, 200, 100), parsed.joystick)
        assertEquals(IntRect(400, 200, 500, 600), parsed.viewArea!!.bbox)
        assertEquals(900, parsed.buttons[GameButton.Jump]!!.x)
        assertEquals(1.5, parsed.cameraGain!!, 0.0)
    }

    @Test
    fun `stick and buttons map with the reference anchor`() {
        val layout = PathingLayout(
            referenceSize = IntSize(1000, 1000),
            miniMap = null,
            joystick = JoystickLayout(200, 200, 100),
            buttons = mapOf(GameButton.Jump to com.bettergi.pocket.pathing.ControlPoint(900, 800)),
        )
        val calls = ArrayList<String>()
        val input = GameInput(layout, 1200, 1000, recordingClient(calls))
        assertTrue(input.holdForward() is InputResult.Ok)
        assertEquals("pad 200,100", calls.single())
        calls.clear()
        assertTrue(input.tapButton(GameButton.Jump) is InputResult.Ok)
        assertEquals("tap 1100,800", calls.single())
        assertTrue(input.rotateByDiff(10f) is InputResult.NotCalibrated)
    }

    @Test
    fun `calibration reports q_m until the pc constant exists`() {
        var angle = 10f
        val client = object : TouchClient by idleClient() {
            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
                angle += (x2 - x1) * 0.2f
                return true
            }
        }
        val layout = PathingLayout(
            referenceSize = IntSize(1000, 1000),
            miniMap = null,
            viewArea = ViewAreaLayout(IntRect(50, 100, 900, 500)),
        )
        var now = 0L
        val runtime = PathingRuntime(
            frames = idleFrames(),
            sleep = { now += it * 1_000_000L },
            nanoTime = { now },
        )
        val result = CameraGainCalibrator(runtime, GameInput(layout, 1000, 1000, client)) { angle }.measureQm()
        val measured = result as QmMeasurement.NeedPcConstant
        assertEquals(0.2, measured.qMDegreesPerPixel, 1e-6)
        assertEquals(null, CameraControl.Q_PC_DEGREES_PER_PIXEL)
    }

    private fun recordingClient(calls: MutableList<String>): TouchClient = object : TouchClient by idleClient() {
        override fun padHold(x: Int, y: Int): Boolean {
            calls += "pad $x,$y"
            return true
        }

        override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
            calls += "tap $x,$y"
            return true
        }
    }

    private fun idleClient() = object : TouchClient {
        override fun padHold(x: Int, y: Int) = false
        override fun padRelease() = false
        override fun buttonDown(id: String, x: Int, y: Int) = false
        override fun buttonUp(id: String) = false
        override fun tap(x: Int, y: Int, durationMs: Long) = false
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = false
        override fun dragBegin(x: Int, y: Int) = false
        override fun dragStep(x: Int, y: Int, durationMs: Long) = false
        override fun dragEnd() = false
        override fun releaseAll() = false
    }

    private fun idleFrames() = object : PathingFrameSource {
        override fun isCapturing() = true
        override fun acquireNewer(afterSequence: Long, timeoutMs: Long) = null
    }
}

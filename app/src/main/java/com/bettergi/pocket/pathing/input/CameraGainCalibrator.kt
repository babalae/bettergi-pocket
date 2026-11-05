package com.bettergi.pocket.pathing.input

import com.bettergi.pocket.pathing.executor.PathingRuntime

/**
 * 任务开始前测量手机「每滑动像素多少度」。
 * 步骤见概要设计第 13 节。`c = q_pc / q_m`，[CameraControl.Q_PC_DEGREES_PER_PIXEL] 仍待 PC 实测。
 */
class CameraGainCalibrator(
    private val runtime: PathingRuntime,
    private val input: GameInput,
    private val readAngle: () -> Float?,
) {
    fun measureQm(): QmMeasurement {
        val samples = ArrayList<GainSample>(DISTANCES.size * 2)
        for (distance in DISTANCES) {
            repeat(2) {
                val before = readMedian() ?: return QmMeasurement.Failed("读不到视角")
                val applied = input.swipeViewPixels(distance, CameraControl.CALIBRATION_SWIPE_MS)
                if (applied == 0) return QmMeasurement.Failed("视角区无法滑动")
                runtime.delay(CameraControl.CALIBRATION_HOLD_MS)
                runtime.delay(CameraControl.CALIBRATION_SETTLE_MS)
                val after = readMedian() ?: return QmMeasurement.Failed("滑动后读不到视角")
                val delta = CameraControl.wrapDegrees(after - before).toDouble()
                samples += GainSample(applied.toDouble(), delta)
            }
        }
        val qM = CameraControl.fitQm(samples)
        val check = CameraControl.linearity(samples, qM)
        if (!check.ok) return QmMeasurement.Failed(check.reason ?: "线性检查未通过")
        val qPc = CameraControl.Q_PC_DEGREES_PER_PIXEL
            ?: return QmMeasurement.NeedPcConstant(qM, samples)
        return QmMeasurement.Ready(qM, CameraControl.gain(qPc, qM), samples)
    }

    private fun readMedian(): Float? {
        val values = ArrayList<Float>(3)
        repeat(3) {
            values += readAngle() ?: return null
        }
        return CameraControl.circularMedian(values)
    }

    companion object {
        val DISTANCES: IntArray = intArrayOf(100, -100, 200, -200, 400, -400)
    }
}

sealed class QmMeasurement {
    data class Ready(
        val qMDegreesPerPixel: Double,
        val gainPixelsPerDegree: Double,
        val samples: List<GainSample>,
    ) : QmMeasurement()

    /** q_m 已测出，但 PC 常量还没填，不能换算成滑动增益。 */
    data class NeedPcConstant(
        val qMDegreesPerPixel: Double,
        val samples: List<GainSample>,
    ) : QmMeasurement()

    data class Failed(val reason: String) : QmMeasurement()
}

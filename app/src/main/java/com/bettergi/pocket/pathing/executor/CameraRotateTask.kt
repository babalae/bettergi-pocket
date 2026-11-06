package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.input.CameraControl
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.input.InputResult
import com.bettergi.pocket.pathing.minimap.CameraOrientation
import com.bettergi.pocket.pathing.minimap.MiniMapLocator
import com.bettergi.pocket.pathing.model.HandledException
import org.opencv.core.Mat
import kotlin.math.abs

/**
 * 把视角转到目标角度。计算与 BGI `CameraRotateTask` 相同，输出改成视角区滑动。
 */
class CameraRotateTask(
    private val runtime: PathingRuntime,
    private val input: GameInput,
    private val locator: MiniMapLocator,
    private val log: (String) -> Unit,
) {
    /** @return 滑动前的差角。差角为 0 时不滑动。 */
    fun rotateToApproach(targetOrientation: Int, miniMap: Mat): Float {
        val diff = CameraControl.angleDiff(CameraOrientation.computeMiniMap(miniMap), targetOrientation.toFloat())
        if (diff == 0f) return 0f
        val result = input.rotateByDiff(diff)
        if (result is InputResult.NotCalibrated) {
            throw HandledException("${result.what}未标定，无法转动视角")
        }
        return diff
    }

    fun waitUntilRotatedTo(targetOrientation: Int, maxDiff: Int, maxTryTimes: Int = 160): Boolean {
        var count = 0
        try {
            while (!runtime.cancelled) {
                val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS) ?: return false
                try {
                    val miniMap = locator.extract(frame.bgr) ?: return false
                    try {
                        if (abs(rotateToApproach(targetOrientation, miniMap)) < maxDiff) return true
                    } finally {
                        miniMap.release()
                    }
                } finally {
                    frame.bgr.release()
                }
                if (count >= maxTryTimes) {
                    log("视角转动到目标角度超时，停止转动")
                    return false
                }
                // PC 鼠标位移即时完成；触屏滑动有持续时间，先等笔画和游戏响应，再读下一帧。
                // 手机默认最多约 24 秒，允许慢设备完成连续触控和小角度微调。
                runtime.delay(CameraControl.SWIPE_MS + 50)
                count++
            }
            return false
        } finally {
            input.releaseView()
        }
    }
}

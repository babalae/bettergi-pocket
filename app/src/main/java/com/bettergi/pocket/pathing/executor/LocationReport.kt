package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.common.Point2f
import java.util.Locale

/**
 * 只读定位的一行状态。坐标是原神游戏坐标，视角是图像坐标系下的角度。
 */
object LocationReport {
    /** BGI `HurryOnFrameInterval`，固定 100 ms。 */
    const val FRAME_INTERVAL_MS = 100L

    /** 等一张新画面的最长时间。超时则本帧识别失败。 */
    const val FRESH_FRAME_TIMEOUT_MS = 500L

    /** 相同结果写日志的最短间隔，状态行本身每帧都更新。 */
    const val LOG_INTERVAL_MS = 1000L

    fun status(image: Point2f, game: Point2f?, angleDegrees: Float): String {
        val angle = format(angleDegrees)
        if (image.isDefault || game == null) {
            return "定位失败  视角 $angle°"
        }
        return "坐标 ${format(game.x)}, ${format(game.y)}  视角 $angle°"
    }

    fun line(image: Point2f, game: Point2f?, angleDegrees: Float, elapsedMs: Long): String {
        return "${status(image, game, angleDegrees)}  ${elapsedMs}ms"
    }

    fun kind(line: String): String = when {
        line.startsWith("坐标") -> "ok"
        line.startsWith("定位失败") -> "miss"
        else -> line
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.1f", value)
}

object LocationReadoutChecks {
    const val NEED_CAPTURE = "请先打开屏幕共享"
    const val NEED_CALIBRATION = "小地图未标定。请在 assets/pathing/layout.json 填写 referenceSize 和 miniMap"
    const val GENSHIN_BACKGROUND = "原神不在前台，无法显示坐标"

    fun refusal(capturing: Boolean, minimapCalibrated: Boolean, genshinInForeground: Boolean?): String? {
        if (!capturing) return NEED_CAPTURE
        if (!minimapCalibrated) return NEED_CALIBRATION
        if (genshinInForeground == false) return GENSHIN_BACKGROUND
        return null
    }
}

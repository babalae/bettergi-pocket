package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.common.Point2f
import kotlin.math.hypot

/**
 * 卡死脱困。参考 BGI `TrapEscaper`，Android 按上一点位的方向回走一半距离，再交还正常行走。
 * 手机的下落按钮只在攀爬/滑翔时出现；回退流程只行走，退出时统一释放全部触点。
 */
class TrapEscaper(
    private val releaseAll: () -> Unit,
    private val log: (String) -> Unit,
) {
    /** 同步使用调用方的路线线程；定位失败、超时、取消或异常都必须完成输入清理。 */
    fun retreat(current: Point2f, previous: Point2f?, walkBack: (Point2f) -> Boolean): Boolean {
        releaseAll()
        try {
            val target = halfway(current, previous)
            if (target == null) {
                log("没有有效的上一点位或回退距离过短，停止本次脱离")
                return false
            }
            log("卡死脱离：向上一点位回走一半距离，目标地图坐标 ${target.x}, ${target.y}")
            return walkBack(target)
        } finally {
            releaseAll()
        }
    }

    companion object {
        fun halfway(current: Point2f, previous: Point2f?): Point2f? {
            if (previous == null || current.isDefault || previous.isDefault ||
                !current.x.isFinite() || !current.y.isFinite() ||
                !previous.x.isFinite() || !previous.y.isFinite()) return null
            if (hypot((previous.x - current.x).toDouble(), (previous.y - current.y).toDouble()) < 2.0) return null
            return Point2f((current.x + previous.x) / 2f, (current.y + previous.y) / 2f)
        }
    }
}

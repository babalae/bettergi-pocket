package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.capture.ScreenCaptureController

/**
 * 地图追踪的取帧入口。要求帧序号递增，避免把最多 2.5 秒前的缓存画面当成新位置。
 */
interface PathingFrameSource {
    fun isCapturing(): Boolean

    /**
     * @param afterSequence 上一张已用帧的序号；0 表示还没有用过
     * @return 更新的帧，调用方释放 [CapturedBgrFrame.bgr]；超时或中断返回 null
     */
    fun acquireNewer(afterSequence: Long, timeoutMs: Long): CapturedBgrFrame?
}

class ScreenCaptureFrameSource(
    private val capture: ScreenCaptureController,
) : PathingFrameSource {
    override fun isCapturing(): Boolean = capture.isRunning()

    override fun acquireNewer(afterSequence: Long, timeoutMs: Long): CapturedBgrFrame? {
        return capture.acquireNewerBgr(afterSequence, timeoutMs)
    }
}

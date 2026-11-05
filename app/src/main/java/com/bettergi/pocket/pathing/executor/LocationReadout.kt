package com.bettergi.pocket.pathing.executor

import android.os.SystemClock
import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.pathing.map.MapManager
import com.bettergi.pocket.pathing.minimap.CameraOrientation
import com.bettergi.pocket.pathing.minimap.MiniMapLocator
import com.bettergi.pocket.pathing.navigation.Navigation
import java.util.concurrent.CancellationException

/**
 * 第二阶段：只读显示坐标和视角，不产生任何触控。
 *
 * 循环与 BGI 主循环的取位顺序一致：裁小地图、算视角、再定位。帧间隔为 [LocationReport.FRAME_INTERVAL_MS]。
 */
class LocationReadout(
    private val runtime: PathingRuntime,
    private val layout: PathingLayout,
    private val mapName: String = "Teyvat",
    private val matchMethod: String? = null,
    private val genshinInForeground: () -> Boolean? = { true },
    private val log: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    fun run() {
        val miniMapLayout = layout.miniMap
        if (miniMapLayout == null) {
            publish(LocationReadoutChecks.NEED_CALIBRATION, forceLog = true)
            return
        }
        val locator = MiniMapLocator(miniMapLayout)
        try {
            val loadStarted = nowMs()
            Navigation.warmUp(matchMethod)
            runtime.delay(0)
            val method = matchMethod?.takeIf { it.isNotEmpty() } ?: MapManager.DEFAULT_MATCHING_METHOD
            log("地图数据已加载，用时 ${nowMs() - loadStarted} ms（$method）")
            onStatus("地图已加载")
            val map = MapManager.getMap(mapName, matchMethod)
            var loggedRoi = false
            var lastLogMs = Long.MIN_VALUE
            var lastKind: String? = null
            while (true) {
                if (!runtime.isCapturing()) {
                    publish("屏幕共享已停止", forceLog = true)
                    break
                }
                if (genshinInForeground() == false) {
                    publish("原神离开前台，已停止显示坐标", forceLog = true)
                    break
                }
                val frame = runtime.nextFrame(LocationReport.FRESH_FRAME_TIMEOUT_MS)
                if (frame == null) {
                    note("没有新画面", "没有新画面", nowMs(), lastLogMs, lastKind) { at, kind ->
                        lastLogMs = at
                        lastKind = kind
                    }
                    runtime.delay(LocationReport.FRAME_INTERVAL_MS)
                    continue
                }
                try {
                    if (!loggedRoi) {
                        val rect = locator.nativeRect(frame.width, frame.height)
                        log("画面 ${frame.width}×${frame.height}，小地图 ${rect ?: "超出画面"}")
                        loggedRoi = true
                    }
                    val miniMap = locator.extract(frame.bgr)
                    if (miniMap == null) {
                        note("小地图不在画面内", "小地图不在画面内", nowMs(), lastLogMs, lastKind) { at, kind ->
                            lastLogMs = at
                            lastKind = kind
                        }
                    } else {
                        val started = nowMs()
                        try {
                        val angle = CameraOrientation.computeMiniMap(miniMap)
                        val image = Navigation.getPosition(miniMap, mapName, matchMethod)
                        val game = map.convertImageCoordinatesToGenshinMapCoordinates(image)
                        val status = LocationReport.status(image, game, angle)
                        val line = LocationReport.line(image, game, angle, nowMs() - started)
                        note(status, line, nowMs(), lastLogMs, lastKind) { at, kind ->
                            lastLogMs = at
                            lastKind = kind
                        }
                        } finally {
                            miniMap.release()
                        }
                    }
                } finally {
                    frame.bgr.release()
                }
                runtime.delay(LocationReport.FRAME_INTERVAL_MS)
            }
        } catch (_: CancellationException) {
            // 用户停止，或等待被中断
        } finally {
            Navigation.release()
        }
    }

    private fun publish(line: String, forceLog: Boolean) {
        onStatus(line)
        if (forceLog) log(line)
    }

    private fun note(
        status: String,
        logLine: String,
        now: Long,
        lastLogMs: Long,
        lastKind: String?,
        remember: (Long, String) -> Unit,
    ) {
        onStatus(status)
        val kind = LocationReport.kind(logLine)
        if (kind != lastKind || now - lastLogMs >= LocationReport.LOG_INTERVAL_MS) {
            log(logLine)
            remember(now, kind)
        }
    }
}

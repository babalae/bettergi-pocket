package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.executor.PathingRuntime
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.map.MapManager
import com.bettergi.pocket.pathing.minimap.MiniMapLocator
import com.bettergi.pocket.pathing.model.HandledException
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.min

/**
 * 传送任务。拖图步进、传送点选择和缩放读数已按 BGI 实现。
 * 大地图按钮、图标和滑条还没有手机模板，[BigMapUi] 看不到界面时会停在对应步骤并说明原因。
 */
class MapTeleport(
    private val runtime: PathingRuntime,
    private val input: GameInput,
    private val locator: MiniMapLocator?,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val points: List<TpPoint>,
    private val bigMap: BigMapUi,
    private val log: (String) -> Unit,
) : PathingTeleport {
    override fun tp(gameX: Double, gameY: Double, mapName: String, force: Boolean): Point2f {
        if (!mapName.equals("Teyvat", ignoreCase = true)) {
            throw HandledException("只支持提瓦特传送，当前地图是 $mapName")
        }
        if (points.isEmpty()) {
            throw HandledException("没有传送点数据。请先运行 tools/sync-pathing-assets.ps1")
        }
        val nearest = TpCatalog.nearest(points, gameX, gameY, 2)
        if (nearest.size < 2) throw HandledException("传送点不足 2 个，无法选择目标")
        val target = if (force) {
            TpPoint(id = -1, type = "", name = "强制坐标", country = "", x = gameX, y = gameY)
        } else {
            nearest[0]
        }
        log("开始传送：${target.name.ifBlank { "坐标" }} ${target.x}, ${target.y}")
        openBigMap(mapName)
        throw HandledException("大地图已打开，但图标对齐、区域列表和传送按钮还没有手机模板，传送停在这里")
    }

    override fun openBigMap(mapName: String) {
        val miniMap = locator ?: throw HandledException("小地图未标定，无法打开大地图")
        val rect = miniMap.nativeRect(screenWidth, screenHeight)
            ?: throw HandledException("小地图不在当前画面内")
        input.tapNative(rect.centerX, rect.centerY)
        repeat(63) {
            runtime.delay(80)
            if (isBigMapOpen()) return
        }
        throw HandledException("没有识别到大地图。地图界面模板还没标定")
    }

    override fun positionFromBigMap(mapName: String): Point2f? {
        val frame = runtime.nextFrame(500) ?: return null
        val scaled = scaleTo1080p(frame.bgr)
        val grey = Mat()
        try {
            Imgproc.cvtColor(scaled, grey, Imgproc.COLOR_BGR2GRAY)
            val map = MapManager.getMap(mapName, MapManager.SIFT)
            val center256 = map.getBigMapPosition(grey)
            if (center256.isDefault) return null
            val center2048 = Point2f(center256.x * 8f, center256.y * 8f)
            return map.convertImageCoordinatesToGenshinMapCoordinates(center2048)
        } catch (e: Exception) {
            log("大地图定位失败：${e.message ?: e.javaClass.simpleName}")
            return null
        } finally {
            grey.release()
            if (scaled !== frame.bgr) scaled.release()
            frame.bgr.release()
        }
    }

    override fun closeMap() {
        val frame = runtime.nextFrame(500) ?: return
        try {
            val close = bigMap.closeButton(frame.bgr) ?: return
            input.tapNative(close.first, close.second)
        } finally {
            frame.bgr.release()
        }
    }

    private fun isBigMapOpen(): Boolean {
        val frame = runtime.nextFrame(200) ?: return false
        try {
            return bigMap.isOpen(frame.bgr)
        } finally {
            frame.bgr.release()
        }
    }

    /** `k > 1` 时缩到 1080p 内容尺度；`k < 1` 不放大。 */
    private fun scaleTo1080p(bgr: Mat): Mat {
        val k = min(bgr.cols() / 1920.0, bgr.rows() / 1080.0)
        if (k <= 1.0) return bgr
        val dst = Mat()
        Imgproc.resize(
            bgr,
            dst,
            Size(bgr.cols() / k, bgr.rows() / k),
            0.0,
            0.0,
            Imgproc.INTER_AREA,
        )
        return dst
    }
}

interface BigMapUi {
    fun isOpen(frame: Mat): Boolean
    fun closeButton(frame: Mat): Pair<Int, Int>?
}

object UnavailableBigMapUi : BigMapUi {
    override fun isOpen(frame: Mat) = false
    override fun closeButton(frame: Mat) = null
}

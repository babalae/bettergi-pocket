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
        dragToward(target.x, target.y, mapName)
        val frame = runtime.nextFrame(500) ?: throw HandledException("打开大地图后没有新画面")
        try {
            val observed = IconAlignment.mergeNearby(bigMap.observedIcons(frame.bgr))
            if (observed.isEmpty()) {
                throw HandledException("没有识别到传送图标。图标模板还没标定")
            }
            val center = positionFromMat(frame.bgr, mapName) ?: throw HandledException("大地图中心没有识别出来")
            val clickPoint = iconScreen(center, target.x, target.y)
            val expected = points.map { point ->
                val screen = iconScreen(center, point.x, point.y)
                MapIcon(screen.first, screen.second, setOf(point.type))
            }
            val alignment = IconAlignment.align(expected, observed)
            val click = alignment.apply(clickPoint.first, clickPoint.second)
            val neighbor = iconScreen(center, nearest[0].x, nearest[0].y).let { first ->
                val second = iconScreen(center, nearest[1].x, nearest[1].y)
                kotlin.math.hypot(first.first - second.first, first.second - second.second)
            }
            if (IconAlignment.uncertainty(alignment) > IconAlignment.allowedUncertainty(neighbor)) {
                throw HandledException("地图定位精度不足")
            }
            input.tapNative(click.first.toInt(), click.second.toInt())
            throw HandledException("已点击传送点，但传送按钮和加载完成还没有手机模板")
        } finally {
            frame.bgr.release()
        }
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
        try {
            return positionFromMat(frame.bgr, mapName)
        } finally {
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

    private fun dragToward(targetX: Double, targetY: Double, mapName: String) {
        val start = positionFromBigMap(mapName) ?: throw HandledException("大地图中心没有识别出来")
        MapDrag.moveToward(
            start = start,
            targetX = targetX,
            targetY = targetY,
            zoom = 1.0,
            readCenter = { positionFromBigMap(mapName) },
            drag = { dx, dy -> dragBySteps(dx, dy) },
        )
    }

    private fun dragBySteps(dx: Int, dy: Int): Pair<Int, Int> {
        if (dx == 0 && dy == 0) return 0 to 0
        val length = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
        val count = MapDrag.stepCount(length)
        val stepX = MapDrag.generateSteps(dx, count)
        val stepY = MapDrag.generateSteps(dy, count)
        var x = screenWidth / 2
        var y = screenHeight / 2
        input.dragBegin(x, y)
        for (index in stepX.indices) {
            runtime.delay(MapDrag.STEP_MS)
            x += stepX[index]
            y += stepY[index]
            input.dragStep(x, y, MapDrag.STEP_MS)
        }
        runtime.delay(MapDrag.RELEASE_HOLD_MS)
        input.dragEnd()
        return dx to dy
    }

    private fun iconScreen(center: Point2f, gameX: Double, gameY: Double): Pair<Double, Double> {
        val (dx, dy) = MapDrag.dragPixels(center, gameX, gameY, zoom = 1.0)
        return screenWidth / 2.0 + dx to screenHeight / 2.0 + dy
    }

    private fun positionFromMat(bgr: Mat, mapName: String): Point2f? {
        val scaled = scaleTo1080p(bgr)
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
            if (scaled !== bgr) scaled.release()
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
    fun observedIcons(frame: Mat): List<MapIcon> = emptyList()
}

object UnavailableBigMapUi : BigMapUi {
    override fun isOpen(frame: Mat) = false
    override fun closeButton(frame: Mat) = null
}

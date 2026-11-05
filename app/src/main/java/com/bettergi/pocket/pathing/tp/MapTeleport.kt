package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.executor.PathingRuntime
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.map.MapManager
import com.bettergi.pocket.pathing.map.TeyvatMap
import com.bettergi.pocket.pathing.minimap.MiniMapLocator
import com.bettergi.pocket.pathing.model.HandledException
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.CancellationException
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    private val random: () -> Double = { Math.random() },
) : PathingTeleport {
    /** 原生 / 1080p 的内容尺度 `k`，与 BGI `ScaleTo1080PRatio` 相同。 */
    private val k = min(screenWidth / 1920.0, screenHeight / 1080.0)

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
            val view = bigMapView(frame.bgr, mapName) ?: throw HandledException("大地图视野没有识别出来")
            val clickPoint = view.project(target.x, target.y)
            // BGI `GetVisibleExpectedMapIcons`：只取视野内的传送点
            val expected = points.mapNotNull { point ->
                val screen = view.project(point.x, point.y)
                if (screen.first in 0.0..screenWidth.toDouble() && screen.second in 0.0..screenHeight.toDouble()) {
                    MapIcon(screen.first, screen.second, setOf(point.type))
                } else {
                    null
                }
            }
            val alignment = IconAlignment.align(expected, observed, k)
            val click = alignment.apply(clickPoint.first, clickPoint.second)
            val first = view.project(nearest[0].x, nearest[0].y)
            val second = view.project(nearest[1].x, nearest[1].y)
            val neighbor = hypot(first.first - second.first, first.second - second.second)
            val allowed = IconAlignment.allowedUncertainty(neighbor, k)
            if (IconAlignment.uncertainty(alignment, k) > allowed) {
                throw HandledException("地图定位精度不足")
            }
            if (hypot(click.first - clickPoint.first, click.second - clickPoint.second) > allowed) {
                throw HandledException("地图校正量超过相邻点安全距离")
            }
            input.tapNative(click.first.roundToInt(), click.second.roundToInt())
            throw HandledException("已点击传送点，但传送按钮和加载完成还没有手机模板")
        } finally {
            frame.bgr.release()
        }
    }

    /** 来源：BGI `OpenBigMapUi` / `TryToOpenBigMapUi`：先松开所有触点，已在大地图就不再点。 */
    override fun openBigMap(mapName: String) {
        input.releaseAll()
        runtime.delay(20)
        if (isBigMapOpen()) return
        val miniMap = locator ?: throw HandledException("小地图未标定，无法打开大地图")
        val rect = miniMap.nativeRect(screenWidth, screenHeight)
            ?: throw HandledException("小地图不在当前画面内")
        input.tapNative(rect.centerX, rect.centerY)
        runtime.delay(100)
        repeat(OPEN_MAP_ATTEMPTS) { attempt ->
            if (isBigMapOpen()) return
            if (attempt + 1 < OPEN_MAP_ATTEMPTS) runtime.delay(80)
        }
        throw HandledException("没有识别到大地图。地图界面模板还没标定")
    }

    override fun positionFromBigMap(mapName: String): Point2f? = positionFromBigMap(mapName, null)

    private fun positionFromBigMap(mapName: String, expected: Point2f?): Point2f? {
        val frame = runtime.nextFrame(500) ?: return null
        try {
            return positionFromMat(frame.bgr, mapName, expected)
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
            // BGI `MoveMapToCore`：用惯性预测的中心做本地 SIFT
            readCenter = { predicted -> positionFromBigMap(mapName, predicted) },
            drag = { dx, dy -> dragBySteps(dx, dy) },
        )
    }

    /**
     * 来源：BGI `MouseMoveMap`。[dx]/[dy] 是 1080p 像素，换到原生要乘 `k`。
     * @return 实际发送的 1080p 像素，用于惯性预测
     */
    private fun dragBySteps(dx: Int, dy: Int): Pair<Int, Int> {
        val plan = MapDrag.planDrag(dx, dy, screenWidth.toDouble(), screenHeight.toDouble(), k, random)
        if (plan.sentX == 0 && plan.sentY == 0) {
            // BGI 此时仍按下再抬起。手机上这等于点一下地图，可能点中图标，所以不发
            runtime.delay(30)
            return 0 to 0
        }
        val length = sqrt((plan.sentX * plan.sentX + plan.sentY * plan.sentY).toDouble())
        val count = MapDrag.stepCount(length)
        val stepX = MapDrag.generateSteps(plan.sentX, count)
        val stepY = MapDrag.generateSteps(plan.sentY, count)
        input.dragBegin(plan.startX.roundToInt(), plan.startY.roundToInt())
        var movedX = 0
        var movedY = 0
        for (index in stepX.indices) {
            runtime.delay(MapDrag.STEP_MS)
            movedX += stepX[index]
            movedY += stepY[index]
            input.dragStep(
                (plan.startX + movedX * k).roundToInt(),
                (plan.startY + movedY * k).roundToInt(),
                MapDrag.STEP_MS,
            )
        }
        runtime.delay(MapDrag.RELEASE_HOLD_MS)
        input.dragEnd()
        runtime.delay(30)
        return plan.sentX to plan.sentY
    }

    /** 大地图视野矩形，2048 级别图像坐标。点击位置按 BGI `ConvertToGameRegionPosition` 比例投影。 */
    private inner class BigMapView(val x: Double, val y: Double, val width: Double, val height: Double, mapName: String) {
        private val map = MapManager.getMap(mapName, MapManager.DEFAULT_MATCHING_METHOD)

        fun project(gameX: Double, gameY: Double): Pair<Double, Double> {
            val pic = map.convertGenshinMapCoordinatesToImageCoordinates(Point2f(gameX.toFloat(), gameY.toFloat()))
            return projectMapClick(
                pic.x.toDouble(), pic.y.toDouble(),
                x, y, width, height,
                screenWidth.toDouble(), screenHeight.toDouble(),
            )
        }
    }

    private fun bigMapView(bgr: Mat, mapName: String): BigMapView? {
        val scaled = scaleTo1080p(bgr)
        val grey = Mat()
        try {
            Imgproc.cvtColor(scaled, grey, Imgproc.COLOR_BGR2GRAY)
            val rect = MapManager.getMap(mapName, MapManager.DEFAULT_MATCHING_METHOD).getBigMapRect(grey)
            if (rect.width <= 0 || rect.height <= 0) return null
            val s = TeyvatMap.BIG_MAP_256_SCALE_TO_2048.toDouble()
            return BigMapView(rect.x * s, rect.y * s, rect.width * s, rect.height * s, mapName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("大地图视野识别失败：${e.message ?: e.javaClass.simpleName}")
            return null
        } finally {
            grey.release()
            if (scaled !== bgr) scaled.release()
        }
    }

    /** @param expected 预期中心的游戏坐标；有值时先做本地匹配，失败回退全图 */
    private fun positionFromMat(bgr: Mat, mapName: String, expected: Point2f?): Point2f? {
        val scaled = scaleTo1080p(bgr)
        val grey = Mat()
        try {
            Imgproc.cvtColor(scaled, grey, Imgproc.COLOR_BGR2GRAY)
            val map = MapManager.getMap(mapName, MapManager.DEFAULT_MATCHING_METHOD)
            val center256 = if (expected != null) {
                map.getBigMapPosition(grey, map.convertGenshinMapCoordinatesToImageCoordinates(expected))
            } else {
                map.getBigMapPosition(grey)
            }
            if (center256.isDefault) return null
            val s = TeyvatMap.BIG_MAP_256_SCALE_TO_2048.toFloat()
            return map.convertImageCoordinatesToGenshinMapCoordinates(Point2f(center256.x * s, center256.y * s))
        } catch (e: CancellationException) {
            throw e
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

    private companion object {
        /** BGI `WaitForBigMapUiAppear(63)`。 */
        const val OPEN_MAP_ATTEMPTS = 63
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

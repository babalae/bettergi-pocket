package com.bettergi.pocket.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.bettergi.pocket.recognition.area.GameUiCalibration
import com.bettergi.pocket.recognition.area.GameUiCalibrator
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.opencv.MatOps
import kotlin.math.min

private data class DisplaySpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

class ScreenCaptureController(
    private val context: Context,
    private val onStoppedExternally: () -> Unit = {},
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    private var densityDpi: Int = DisplayMetrics.DENSITY_DEVICE_STABLE
    private var rgbaScratch = ByteArray(0)
    private var hasCachedFrame = false
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var lastFrameElapsedMs = 0L
    private var lastRecoverElapsedMs = 0L
    private var displayCreatedElapsedMs = 0L
    private var frameSequence = 0L
    private var calibrationElapsedMs = 0L
    private val uiCalibrator by lazy { GameUiCalibrator.load(RecognitionAssets(context.assets)) }

    fun isRunning(): Boolean = synchronized(lock) {
        mediaProjection != null && virtualDisplay != null && imageReader != null
    }

    fun capturedSize(): Pair<Int, Int>? = synchronized(lock) {
        val reader = imageReader ?: return null
        if (reader.width <= 0 || reader.height <= 0) return null
        reader.width to reader.height
    }

    fun start(resultCode: Int, data: Intent) {
        synchronized(lock) {
            stopLocked()

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data) ?: return
            mediaProjection = projection

            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    handleProjectionStoppedExternally()
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    synchronized(lock) {
                        resizeCapturedContentLocked(width, height)
                    }
                }
            }
            callback = cb
            projection.registerCallback(cb, mainHandler)

            val spec = defaultDisplaySpec()
            densityDpi = spec.densityDpi
            createVirtualDisplayLocked(spec.width, spec.height, spec.densityDpi)
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    /**
     * 丢掉积压帧，不拷像素、不转 Mat。
     * VirtualDisplay 仍会按 vsync 产出，但 ImageReader 最多缓存 2 张，多的由系统丢弃。
     */
    fun discardLatestImages() {
        synchronized(lock) {
            try {
                imageReader?.acquireLatestImage()?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 只在识别需要时调用：拷一帧最新图并转 BGR。中间帧已被 [android.media.ImageReader.acquireLatestImage] 丢掉。
     *
     * 单应用共享在画面几乎静止时可能暂时不再出帧；换 ImageReader 时也会空一拍。
     * 这两种情况都复用上一帧 RGBA，避免识别直接停掉。拷像素期间持锁，防止
     * [MediaProjection.Callback.onCapturedContentResize] 关掉仍在使用的 ImageReader。
     */
    fun acquireLatestBgr(): CapturedBgrFrame? = synchronized(lock) {
        val image = try {
            imageReader?.acquireLatestImage()
        } catch (_: Throwable) {
            null
        }
        if (image != null) {
            try {
                val width = image.width
                val height = image.height
                val packed = rgbaScratch(width * height * 4)
                MatOps.copyRgbaImage(image, packed)
                cachedWidth = width
                cachedHeight = height
                hasCachedFrame = true
                lastFrameElapsedMs = SystemClock.elapsedRealtime()
                val bgr = MatOps.rgbaToBgr(width, height, packed)
                if (lastFrameElapsedMs - calibrationElapsedMs >= 3000L) {
                    calibrationElapsedMs = lastFrameElapsedMs
                    try {
                        val previous = GameUiCalibration.current
                        if (previous != null && previous.frameSize != com.bettergi.pocket.recognition.IntSize(width, height)) {
                            GameUiCalibration.clear()
                        }
                        uiCalibrator.detect(bgr, GameUiCalibration.current)?.let { geometry ->
                            if (geometry != GameUiCalibration.current) Log.i(TAG, "HUD 自动标定: $geometry")
                            GameUiCalibration.current = geometry
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "HUD 标定失败，保留原有适配", e)
                    }
                }
                frameSequence += 1
                return CapturedBgrFrame(
                    width = width,
                    height = height,
                    bgr = bgr,
                    sequence = frameSequence,
                )
            } finally {
                image.close()
            }
        }

        val now = SystemClock.elapsedRealtime()
        maybeRecoverStalledReaderLocked(now)
        if (!hasCachedFrame || now - lastFrameElapsedMs > CACHE_MAX_AGE_MS) {
            return null
        }
        return CapturedBgrFrame(
            width = cachedWidth,
            height = cachedHeight,
            bgr = MatOps.rgbaToBgr(cachedWidth, cachedHeight, rgbaScratch),
            sequence = frameSequence,
        )
    }

    /**
     * 取一张比 [afterSequence] 更新的画面。没有新帧时等待，最多 [timeoutMs]。
     * 超时或线程被中断时返回 null；中断会重新置上，供调用方取消任务。
     * 返回的 Mat 由调用方释放。等待期间拿到的旧帧会在这里释放。
     */
    fun acquireNewerBgr(afterSequence: Long, timeoutMs: Long): CapturedBgrFrame? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (true) {
            val frame = acquireLatestBgr()
            if (frame != null && frame.sequence > afterSequence) {
                return frame
            }
            frame?.bgr?.release()
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) return null
            try {
                Thread.sleep(min(POLL_INTERVAL_MS, deadline - now))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    private fun handleProjectionStoppedExternally() {
        val notify = synchronized(lock) {
            if (mediaProjection == null) {
                false
            } else {
                val projection = mediaProjection
                val cb = callback
                mediaProjection = null
                callback = null
                releaseDisplayLocked()
                if (projection != null && cb != null) {
                    try {
                        projection.unregisterCallback(cb)
                    } catch (_: Throwable) {
                    }
                }
                true
            }
        }
        if (notify) {
            onStoppedExternally()
        }
    }

    private fun createVirtualDisplayLocked(width: Int, height: Int, densityDpi: Int) {
        val projection = mediaProjection ?: return
        releaseDisplayLocked()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        imageReader = reader
        displayCreatedElapsedMs = SystemClock.elapsedRealtime()
        virtualDisplay = projection.createVirtualDisplay(
            "BetterGIPocketShare",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
    }

    /**
     * 同一 MediaProjection 只能 createVirtualDisplay 一次。
     * 选单个应用或旋转时，系统会回调新尺寸，只能 resize 现有 VirtualDisplay 并换 ImageReader。
     */
    private fun resizeCapturedContentLocked(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        if (width <= 0 || height <= 0) return
        val current = imageReader
        if (current != null && current.width == width && current.height == height) return

        GameUiCalibration.clear()
        calibrationElapsedMs = 0L
        hasCachedFrame = false
        Log.i(TAG, "captured content resized to ${width}x$height")
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        display.setSurface(newReader.surface)
        display.resize(width, height, densityDpi)
        imageReader = newReader
        lastRecoverElapsedMs = SystemClock.elapsedRealtime()
        try {
            current?.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * ImageReader 不再出帧时，用同一 VirtualDisplay 换一块 surface。
     * 每个 MediaProjection 只能 createVirtualDisplay 一次，不能整段重建。
     */
    private fun maybeRecoverStalledReaderLocked(now: Long) {
        if (virtualDisplay == null || imageReader == null) return
        val stalledSince = if (lastFrameElapsedMs > 0) lastFrameElapsedMs else displayCreatedElapsedMs
        if (stalledSince <= 0) return
        if (now - stalledSince < RECOVER_AFTER_MS) return
        if (now - lastRecoverElapsedMs < RECOVER_COOLDOWN_MS) return
        lastRecoverElapsedMs = now
        Log.w(TAG, "screen capture produced no frames for ${now - stalledSince}ms, recreating ImageReader")
        val current = imageReader ?: return
        val width = current.width
        val height = current.height
        if (width <= 0 || height <= 0) return
        val display = virtualDisplay ?: return
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        display.setSurface(newReader.surface)
        imageReader = newReader
        try {
            current.close()
        } catch (_: Throwable) {
        }
    }

    private fun stopLocked() {
        val projection = mediaProjection
        val cb = callback
        mediaProjection = null
        callback = null
        releaseDisplayLocked()
        if (projection != null && cb != null) {
            try {
                projection.unregisterCallback(cb)
            } catch (_: Throwable) {
            }
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
    }

    private fun releaseDisplayLocked() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        } finally {
            virtualDisplay = null
        }

        try {
            imageReader?.close()
        } catch (_: Throwable) {
        } finally {
            imageReader = null
        }
        hasCachedFrame = false
        cachedWidth = 0
        cachedHeight = 0
        lastFrameElapsedMs = 0L
        lastRecoverElapsedMs = 0L
        displayCreatedElapsedMs = 0L
        frameSequence = 0L
        calibrationElapsedMs = 0L
        GameUiCalibration.clear()
    }

    private fun rgbaScratch(size: Int): ByteArray {
        if (rgbaScratch.size != size) {
            rgbaScratch = ByteArray(size)
        }
        return rgbaScratch
    }

    private fun defaultDisplaySpec(): DisplaySpec {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("DEFAULT_DISPLAY is missing")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayContext = context.createDisplayContext(display)
            val windowManager = displayContext.getSystemService(WindowManager::class.java)
            val bounds = windowManager.maximumWindowMetrics.bounds
            return DisplaySpec(
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = displayContext.resources.displayMetrics.densityDpi,
            )
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return DisplaySpec(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private companion object {
        const val TAG = "BetterGI.Capture"
        const val IMAGE_READER_MAX_IMAGES = 2
        const val CACHE_MAX_AGE_MS = 2500L
        const val RECOVER_AFTER_MS = 800L
        const val RECOVER_COOLDOWN_MS = 2500L
        const val POLL_INTERVAL_MS = 16L
    }
}

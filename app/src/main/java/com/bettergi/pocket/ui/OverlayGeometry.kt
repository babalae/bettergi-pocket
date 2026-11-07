package com.bettergi.pocket.ui

import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager

/** 所有面板共用可用区域，避开刘海与四周 8dp；沉浸式游戏不扣除系统栏。 */
object OverlayGeometry {
    /** 用通用 Rect 保存四边内边距，避免 API 24～27 的公开签名依赖 DisplayCutout。 */
    fun cutoutInsets(view: android.view.View?): Rect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view?.rootWindowInsets?.displayCutout?.let { Rect(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom) }
    } else null

    fun safeBounds(windows: WindowManager, density: Float, cutoutInsets: Rect? = null): Rect {
        val margin = (8 * density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windows.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.displayCutout(),
            )
            return Rect(metrics.bounds).apply {
                left += insets.left + margin
                top += insets.top + margin
                right -= insets.right + margin
                bottom -= insets.bottom + margin
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windows.defaultDisplay.getRealMetrics(metrics)
        return Rect(margin, margin, metrics.widthPixels - margin, metrics.heightPixels - margin).apply {
            if (cutoutInsets != null) {
                left += cutoutInsets.left; top += cutoutInsets.top
                right -= cutoutInsets.right; bottom -= cutoutInsets.bottom
            }
        }
    }

    fun centeredParams(windows: WindowManager, density: Float, maxWidthDp: Int, maxHeightDp: Int,
                       flags: Int, cutoutInsets: Rect? = null): WindowManager.LayoutParams {
        val safe = safeBounds(windows, density, cutoutInsets)
        val width = minOf((maxWidthDp * density).toInt(), safe.width()).coerceAtLeast(1)
        val height = minOf((maxHeightDp * density).toInt(), safe.height()).coerceAtLeast(1)
        return WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            x = safe.left + (safe.width() - width) / 2
            y = safe.top + (safe.height() - height) / 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
    }
}

package com.bettergi.pocket.pathing.party

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettergi.pocket.R
import com.bettergi.pocket.overlay.OverlayWindowController
import com.bettergi.pocket.pathing.PathingProgress
import com.bettergi.pocket.settings.TriggerSettingsRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 WindowManager 验证主面板、三态状态条、正文穿透和一次停止回调，不执行游戏触控。 */
@RunWith(AndroidJUnit4::class)
class OverlayStatusInstrumentedTest {
    @Test fun showsProgressAndStopsFromStatusBar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("appops set ${c.packageName} SYSTEM_ALERT_WINDOW allow").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        val prefs = c.getSharedPreferences("overlay_window", Context.MODE_PRIVATE)
        val backup = prefs.all.toMap()
        var controller: OverlayWindowController? = null
        var stopped = 0
        fun field(name: String): Any? = OverlayWindowController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller)
        fun root(): View = field("rootView") as View
        fun snapshot(name: String) {
            SystemClock.sleep(150)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(c.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            prefs.edit().putInt("status_mode", 0).putInt("x", 16).putInt("y", 16).commit()
            instrumentation.runOnMainSync {
                controller = OverlayWindowController(c, TriggerSettingsRepository(c))
                controller!!.onPathingStop = { stopped++ }
                controller!!.onPathingStart = { controller!!.setPathingStatus("还没标定：小道具"); false }
                controller!!.show(); controller!!.showPathing("已选 9 条 · 海灵芝", true)
                val bubble = root().findViewById<View>(R.id.overlay_bubble)
                val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
                val up = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 10f, 10f, 0)
                bubble.dispatchTouchEvent(down); bubble.dispatchTouchEvent(up); down.recycle(); up.recycle()
            }
            SystemClock.sleep(450); instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, root().findViewById<View>(R.id.overlay_panel).visibility)
                root().findViewById<View>(R.id.overlay_pathing_start).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("启动拒绝应保留面板", View.VISIBLE, root().findViewById<View>(R.id.overlay_panel).visibility)
                assertEquals("还没标定：小道具", root().findViewById<TextView>(R.id.overlay_pathing_status).text.toString())
            }
            snapshot("overlay-ui-panel.png")
            instrumentation.runOnMainSync {
                controller!!.setRouteRunning(true)
                controller!!.updateProgress(PathingProgress(3, 9, "01-海灵芝-绀田村左上角-5个", 2, 0, 0, System.currentTimeMillis() - 135000))
                controller!!.log("开始路线 01-海灵芝-绀田村左上角-5个")
                controller!!.log("未识别到小地图，重试 1/3", OverlayWindowController.LogLevel.Warning)
            }
            SystemClock.sleep(350); instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val handle = field("logHandleView") as View
                val body = field("logBodyView") as View
                assertEquals("从隐藏自动进入精简模式", View.GONE, body.visibility)
                assertTrue(handle.findViewById<TextView>(R.id.overlay_log_title).text.toString().startsWith("3/9"))
                val handleParams = field("logHandleParams") as WindowManager.LayoutParams
                assertTrue(controller!!.prepareClickPassthrough(handleParams.x + handle.width / 2, handleParams.y + handle.height / 2))
                assertTrue(handleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                controller!!.restoreClickPassthrough()
                assertEquals(0, handleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                val bodyParams = field("logBodyParams") as WindowManager.LayoutParams
                assertTrue("正文应始终允许游戏触控穿透", bodyParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(handle.findViewById<View>(R.id.overlay_log_stop).performClick())
                assertEquals(1, stopped)
                handle.findViewById<View>(R.id.overlay_log_expand).performClick()
                assertEquals(View.VISIBLE, body.visibility)
            }
            instrumentation.waitForIdleSync(); snapshot("overlay-ui-status.png")
        } finally {
            instrumentation.runOnMainSync { controller?.hide() }
            prefs.edit().clear().apply {
                backup.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
                    is Long -> putLong(key, value); is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }; commit()
            }
        }
    }
}

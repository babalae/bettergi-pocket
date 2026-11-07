package com.bettergi.pocket.pathing.party

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettergi.pocket.pathing.ui.PathingWindow
import com.bettergi.pocket.pathing.settings.PathingRouteStore
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.pathing.settings.PathingSettingsRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实悬浮设置窗口验证多选、跨搜索保留、排序、即时保存与撤销，不向游戏派发操作。 */
@RunWith(AndroidJUnit4::class)
class AvatarMultiSelectInstrumentedTest {
    @Test fun searchesSelectsReordersAndPersistsMultipleCandidates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Gradle 重装被测 APK 可能重置 AppOps；与项目 deploy 脚本相同，为开发测试开启悬浮窗。
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        assertTrue("需要悬浮窗权限", Settings.canDrawOverlays(context))
        val prefs = context.getSharedPreferences(PathingSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val backup = prefs.all.toMap()
        val otherPrefs = listOf("pathing_routes", "pathing_window_ui").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val otherBackup = otherPrefs.map { it.all.toMap() }
        val repository = PathingSettingsRepository(context)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var window: PathingWindow? = null

        fun find(id: String): AccessibilityNodeInfo {
            repeat(30) {
                instrumentation.waitForIdleSync()
                for (surface in automation.windows) {
                    val root = surface.root ?: continue
                    root.findAccessibilityNodeInfosByViewId("com.bettergi.pocket:id/$id").firstOrNull()?.let { return it }
                }
                SystemClock.sleep(100)
            }
            error("找不到控件：$id")
        }
        fun click(id: String) { assertTrue(id, find(id).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
        fun search(text: String) {
            assertTrue(find("avatar_select_search").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
            instrumentation.waitForIdleSync()
        }
        fun clickOption(name: String) {
            repeat(40) {
                instrumentation.waitForIdleSync()
                for (surface in automation.windows) {
                    val root = surface.root ?: continue
                    var node = root.findAccessibilityNodeInfosByViewId("com.bettergi.pocket:id/avatar_option_name")
                        .firstOrNull { it.text?.toString() == name } ?: continue
                    while (!node.isClickable) node = node.parent ?: error("角色选项没有可点击的父容器")
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return
                }
                SystemClock.sleep(50)
            }
            error("搜索结果未显示角色：$name")
        }
        try {
            // 验证旧单选设置迁移；测试结束恢复用户的全部原设置。
            prefs.edit().remove("mainAvatars").remove("guardianShortAvatars").remove("guardianLongAvatars")
                .putString("mainAvatar", "迪希雅").putString("guardianAvatar", "自动").commit()
            assertEquals(listOf("迪希雅"), repository.get().mainAvatars)
            assertEquals(PathingSettings.DEFAULT_SHORT_GUARDIANS, repository.get().guardianShortAvatars)
            assertEquals(PathingSettings.DEFAULT_LONG_GUARDIANS, repository.get().guardianLongAvatars)
            instrumentation.runOnMainSync {
                window = PathingWindow(context, PathingRouteStore(context), repository, {}, {}, { false }, {})
                window!!.show()
            }
            click("pathing_tab_settings")
            click("pathing_setting_main_avatar")
            // 搜索触摸才取输入焦点，完成后归还；角色候选仍在原窗口里。
            instrumentation.runOnMainSync {
                val root = PathingWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(window) as android.view.View
                val paramsField = PathingWindow::class.java.getDeclaredField("params").apply { isAccessible = true }
                var params = paramsField.get(window) as android.view.WindowManager.LayoutParams
                assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                val edit = root.findViewById<android.widget.EditText>(com.bettergi.pocket.R.id.avatar_select_search)
                val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 8f, 8f, 0)
                val up = android.view.MotionEvent.obtain(0, 40, android.view.MotionEvent.ACTION_UP, 8f, 8f, 0)
                edit.dispatchTouchEvent(down); edit.dispatchTouchEvent(up); down.recycle(); up.recycle()
                params = paramsField.get(window) as android.view.WindowManager.LayoutParams
                assertEquals(0, params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                edit.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                params = paramsField.get(window) as android.view.WindowManager.LayoutParams
                assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            }
            search("钟离")
            clickOption("钟离")
            search("七七")
            clickOption("七七")
            // 已选名单跨搜索保留，通过无障碍排序动作提高七七优先级。
            fun findPriority(node: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
                if (node.contentDescription?.toString()?.startsWith("优先级 3，$name") == true) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { findPriority(it, name)?.let { result -> return result } }
                return null
            }
            var selectedRow: AccessibilityNodeInfo? = null
            repeat(30) {
                if (selectedRow == null) for (surface in automation.windows) surface.root?.let { root ->
                    findPriority(root, "七七")?.let { selectedRow = it }
                }
                if (selectedRow == null) SystemClock.sleep(100)
            }
            assertNotNull("应能找到七七的优先级行", selectedRow)
            assertTrue(selectedRow!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            search("")
            assertTrue(find("avatar_select_count").text.toString().contains("已选 3 人"))
            SystemClock.sleep(400)
            val screenshot = automation.takeScreenshot()
            File(context.getExternalFilesDir(null), "overlay-ui-avatar.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            assertEquals(listOf("迪希雅", "七七", "钟离"), repository.get().mainAvatars)
            assertEquals(repository.get().mainAvatars, PathingSettingsRepository(context).get().mainAvatars)
            // 清空立即保存，撤销在同一页面内恢复，返回不再需要确认。
            click("avatar_select_clear")
            assertTrue(repository.get().mainAvatars.isEmpty())
            fun clickDescription(description: String) {
                fun findNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.contentDescription?.toString() == description || node.text?.toString() == description) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { findNode(it)?.let { result -> return result } }
                    return null
                }
                repeat(30) {
                    for (surface in automation.windows) surface.root?.let { root -> findNode(root)?.let { node ->
                        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return
                    } }
                    SystemClock.sleep(100)
                }
                error("找不到操作：$description")
            }
            clickDescription("撤销")
            assertEquals(listOf("迪希雅", "七七", "钟离"), repository.get().mainAvatars)
            click("avatar_select_defaults")
            assertEquals(PathingSettings.DEFAULT_WALKERS, repository.get().mainAvatars)
            clickDescription("返回设置")
            assertNotNull(find("pathing_setting_main_avatar"))
            click("pathing_setting_guardian_avatar")
            search("钟离"); clickOption("钟离")
            assertTrue("短E选择应即时从长E组移出", "钟离" in repository.get().guardianShortAvatars)
            assertFalse("钟离" in repository.get().guardianLongAvatars)
        } finally {
            instrumentation.runOnMainSync { window?.release() }
            val editor = prefs.edit().clear()
            backup.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
            otherPrefs.zip(otherBackup).forEach { (saved, values) ->
                saved.edit().clear().apply {
                    values.forEach { (key, value) -> when (value) {
                        is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
                        is Long -> putLong(key, value); is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    } }; commit()
                }
            }
        }
    }
}

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
import com.bettergi.pocket.pathing.PathingRouteWindow
import com.bettergi.pocket.pathing.settings.PathingRouteStore
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.pathing.settings.PathingSettingsRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实悬浮设置窗口验证多选、跨搜索保留、排序、确认与取消，不向游戏派发操作。 */
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
        val repository = PathingSettingsRepository(context)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var window: PathingRouteWindow? = null

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
        fun clickFirstOption() {
            var node = find("avatar_option_name")
            while (!node.isClickable) node = node.parent ?: error("角色选项没有可点击的父容器")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            // 验证旧单选设置迁移；测试结束恢复用户的全部原设置。
            prefs.edit().remove("mainAvatars").remove("guardianShortAvatars").remove("guardianLongAvatars")
                .putString("mainAvatar", "迪希雅").putString("guardianAvatar", "自动").commit()
            assertEquals(listOf("迪希雅"), repository.get().mainAvatars)
            assertEquals(PathingSettings.DEFAULT_SHORT_GUARDIANS, repository.get().guardianShortAvatars)
            assertEquals(PathingSettings.DEFAULT_LONG_GUARDIANS, repository.get().guardianLongAvatars)
            instrumentation.runOnMainSync {
                window = PathingRouteWindow(context, PathingRouteStore(context), repository, {}, {}, { false }, {})
                window!!.show()
            }
            click("pathing_tab_settings")
            click("pathing_setting_main_avatar")
            search("钟离")
            clickFirstOption()
            search("七七")
            clickFirstOption()
            // 已选名单跨搜索保留，向上调整七七优先级。
            click("avatar_option_up")
            search("")
            assertTrue(find("avatar_select_count").text.toString().contains("已选 3 人"))
            val screenshot = automation.takeScreenshot()
            File(context.getExternalFilesDir(null), "avatar-multiselect.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            click("avatar_select_save")
            assertEquals(listOf("迪希雅", "七七", "钟离"), repository.get().mainAvatars)
            assertEquals(repository.get().mainAvatars, PathingSettingsRepository(context).get().mainAvatars)
            click("pathing_setting_main_avatar")
            click("avatar_select_clear")
            click("avatar_select_cancel")
            assertEquals(listOf("迪希雅", "七七", "钟离"), repository.get().mainAvatars)
            click("pathing_setting_main_avatar")
            click("avatar_select_defaults")
            click("avatar_select_save")
            assertEquals(PathingSettings.DEFAULT_WALKERS, repository.get().mainAvatars)
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
        }
    }
}

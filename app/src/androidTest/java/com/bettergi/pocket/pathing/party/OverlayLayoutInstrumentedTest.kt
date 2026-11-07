package com.bettergi.pocket.pathing.party

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.PathingPreflight
import com.bettergi.pocket.pathing.settings.*
import com.bettergi.pocket.pathing.ui.*
import com.bettergi.pocket.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 用 Android 原生测量和 Canvas 渲染尺寸矩阵，无需更改真机分辨率，也不向游戏注入操作。 */
@RunWith(AndroidJUnit4::class)
class OverlayLayoutInstrumentedTest {
    @Test fun rendersShortLandscapeAndPortraitAtBothFontScales() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val preferences = listOf("pathing_routes", PathingSettingsRepository.PREFS_NAME)
            .map { base.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val backups = preferences.map { it.all.toMap() }
        val catalog = base.assets.open(RouteCatalog.INDEX_ASSET).bufferedReader(Charsets.UTF_8).use { RouteCatalog.parse(it.readText()) }
        val avatars = AvatarCatalog.load(base.assets)
        val output = File(base.getExternalFilesDir(null), "overlay-ui").apply { mkdirs() }
        val sizes = listOf(640 to 320, 800 to 360, 873 to 393, 1113 to 697, 360 to 800)
        try {
            for ((width, height) in sizes) for (scale in listOf(1f, 1.3f)) {
                val config = Configuration(base.resources.configuration).apply { densityDpi = 160; fontScale = scale }
                val c = OverlayUi.context(base.createConfigurationContext(config))
                val w = minOf(760, width - 16); val h = minOf(560, height - 16)
                val store = PathingRouteStore(base)
                val repo = PathingSettingsRepository(base)
                repo.update { PathingSettings(useGadgetIntervalMs = 1000, useGadgetEnabled = true) }
                val selected = catalog.node("地方特产/稻妻/绯樱绣球")?.routePaths.orEmpty() +
                    catalog.node("地方特产/稻妻/海灵芝/海灵芝@tignioj")?.routePaths.orEmpty()
                store.select(selected, catalog.selectionSummary(selected.toSet()))
                for (pageName in listOf("routes", "settings", "avatars")) {
                    lateinit var root: LinearLayout
                    lateinit var page: View
                    instrumentation.runOnMainSync {
                        root = OverlayUi.column(c).apply { setBackgroundResource(R.drawable.bg_pocket_window) }
                        val input = OverlayInput(root) {}
                        root.addView(OverlayUi.text(c, if (pageName == "avatars") "行走位候选 · 按顺序选出第一个在队角色" else "地图追踪    路线    设置", 15f, bold = true).apply {
                            minimumHeight = dp(44); setPadding(dp(12), 0, dp(12), 0)
                        })
                        page = when (pageName) {
                            "routes" -> RoutesPage(c, w >= 560, RouteBrowserState("地方特产/稻妻"), store, input,
                                { PathingPreflight() }, {}, {}, {}, {}, { "模板匹配 · 小道具 1s · 迪希雅" }).also { it.setCatalog(catalog) }
                            "settings" -> SettingsPage(c, w >= 560, repo, {}, { _, _, _ -> })
                            else -> AvatarPickerPage(c, CandidateSlot.Walker, repo, avatars, input, w >= 560, w)
                        }
                        root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
                    }
                    // 等待 ListAdapter 的差分结果，再用与真实窗口相同的尺寸测量。
                    SystemClock.sleep(200); instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, w, h)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        File(output, "$pageName-$width-$height-$scale.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        if (pageName == "routes" && w >= 560) {
                            val list = root.findViewById<RecyclerView>(R.id.pathing_browser_list)
                            val fullRows = (0 until list.childCount).count { list.getChildAt(it).bottom <= list.height }
                            assertTrue("$width×$height $scale 路线列表应至少显示 5 行，实际 $fullRows；列表高 ${list.height}，行高 ${list.getChildAt(0)?.height}", fullRows >= 5)
                        }
                        if (pageName == "avatars" && width == 800) {
                            fun countOptions(view: View): Int = if (view.id == R.id.avatar_option_name) 1 else
                                if (view is android.view.ViewGroup) (0 until view.childCount).sumOf { countOptions(view.getChildAt(it)) } else 0
                            assertTrue("800×360 应至少显示 25 个角色", countOptions(page) >= 25)
                        }
                    }
                }
            }
        } finally {
            preferences.zip(backups).forEach { (prefs, backup) -> restorePreferences(prefs, backup) }
        }
    }
    private fun restorePreferences(prefs: SharedPreferences, values: Map<String, *>) {
        prefs.edit().clear().apply {
            values.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
                is Long -> putLong(key, value); is Float -> putFloat(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } }; commit()
        }
    }
}

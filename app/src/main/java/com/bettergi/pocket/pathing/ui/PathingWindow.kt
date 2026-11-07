package com.bettergi.pocket.pathing.ui

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.icu.text.Transliterator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.PathingPreflight
import com.bettergi.pocket.pathing.party.AvatarCatalog
import com.bettergi.pocket.pathing.settings.*
import com.bettergi.pocket.ui.*
import java.util.Locale
import java.util.concurrent.Executors

/** 服务挂载的单一窗口：页面栈、输入焦点和尺寸在这里统一管理。 */
class PathingWindow(private val base: Context, private val store: PathingRouteStore,
                    private val settings: PathingSettingsRepository, private val onExecute: () -> Unit,
                    private val onToggleReadout: () -> Unit, private val isReadoutRunning: () -> Boolean,
                    private val log: (String) -> Unit, private val preflight: () -> PathingPreflight = { PathingPreflight() },
                    private val fix: (PathingPreflight) -> Unit = {}, private val isRouteRunning: () -> Boolean = { false },
                    private val onSelectionChanged: () -> Unit = {}, private val onShowRunning: () -> Unit = {}) {
    private val windows = base.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val prefs = base.getSharedPreferences("pathing_window_ui", Context.MODE_PRIVATE)
    private val state = RouteBrowserState(prefs.getString("directory", "").orEmpty())
    private var tab = prefs.getInt("tab", 0)
    private var context = OverlayUi.context(base)
    private var root: LinearLayout? = null
    private var content: FrameLayout? = null
    private var header: LinearLayout? = null
    private var input: OverlayInput? = null
    private var focusable = false
    private var params: WindowManager.LayoutParams? = null
    private var cutout: android.graphics.Rect? = null
    private var catalog: RouteCatalog? = null
    private var loading = false
    private var error: String? = null
    private var disposed = false
    private var routes: RoutesPage? = null
    private var settingsPage: SettingsPage? = null
    private var picker: AvatarPickerPage? = null
    private var slot: CandidateSlot? = null
    private var numberEditor: View? = null
    private val avatarCatalog by lazy { AvatarCatalog.load(base.assets) }
    private val checkConditions = object : Runnable {
        override fun run() { if (root == null) return; routes?.refreshPreflight(); main.postDelayed(this, 1000) }
    }
    private val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(config: Configuration) { cutout = null; if (root != null) { dismiss(); show() } }
        override fun onLowMemory() = Unit
    }
    fun show() {
        if (isRouteRunning()) { onShowRunning(); return }
        if (root != null || disposed || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context = OverlayUi.context(base)
        val view = OverlayUi.column(context).apply {
            setBackgroundResource(R.drawable.bg_pocket_window); clipToOutline = true; isFocusableInTouchMode = true
        }
        root = view; params = windowParams()
        input = OverlayInput(view) { focused -> focusable = focused; if (view.isAttachedToWindow) { params = windowParams(); windows.updateViewLayout(view, params) } }
        header = OverlayUi.row(context).apply { minimumHeight = resources.getDimensionPixelSize(R.dimen.pocket_top_bar)
            setPadding(dp(12), 0, dp(8), 0); setBackgroundResource(R.drawable.bg_pocket_header) }
        view.addView(header)
        content = FrameLayout(context); view.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val widthDp = (params!!.width / context.resources.displayMetrics.density).toInt()
        val wide = widthDp >= 560
        routes = RoutesPage(context, wide, state, store, input!!, preflight, fix, {
            val check = preflight()
            if (check.ready) { dismiss(); onExecute() } else routes?.refreshPreflight()
        }, { selectTab(1) }, onSelectionChanged, ::settingsSummary)
        settingsPage = SettingsPage(context, wide, settings, ::openPicker, ::editNumber)
        content!!.addView(routes, FrameLayout.LayoutParams(-1, -1)); content!!.addView(settingsPage, FrameLayout.LayoutParams(-1, -1))
        selectTab(tab)
        windows.addView(view, params)
        base.registerComponentCallbacks(callbacks)
        view.post {
            if (root !== view) return@post
            val updated = windowParams()
            if (updated.width != params?.width || updated.height != params?.height) { dismiss(); show() }
            else { params = updated; windows.updateViewLayout(view, updated) }
        }
        if (slot != null) openPicker(slot!!)
        if (catalog != null) routes?.setCatalog(catalog!!) else loadCatalog()
        main.post(checkConditions)
    }
    private fun windowParams(): WindowManager.LayoutParams {
        OverlayGeometry.cutoutInsets(root)?.let { cutout = it }
        return OverlayGeometry.centeredParams(windows, context.resources.displayMetrics.density,
        760, 560, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        cutout)
    }

    fun dismiss() {
        val view = root ?: return
        routes?.saveScroll(); input?.finish(); main.removeCallbacks(checkConditions)
        prefs.edit().putInt("tab", tab).putString("directory", state.directory).apply()
        root = null; content = null; header = null; routes = null; settingsPage = null; picker = null; numberEditor = null
        input = null; focusable = false; params = null
        windows.removeView(view); base.unregisterComponentCallbacks(callbacks)
    }
    fun release() { disposed = true; dismiss(); loader.shutdownNow() }
    private fun selectTab(next: Int) {
        input?.finish(); tab = next
        routes?.visibility = if (next == 0) View.VISIBLE else View.GONE
        settingsPage?.visibility = if (next == 1) View.VISIBLE else View.GONE
        settingsPage?.refresh(); buildHeader()
    }
    private fun buildHeader() {
        val bar = header ?: return; bar.removeAllViews()
        if (picker != null || numberEditor != null) {
            bar.addView(OverlayUi.iconButton(context, R.drawable.ic_ui_arrow_back, "返回设置") { back() })
            val title = OverlayUi.row(context)
            val wide = params!!.width / context.resources.displayMetrics.density >= 560
            title.addView(OverlayUi.text(context, if (numberEditor != null) "编辑数值" else slot!!.title, 15f, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(if (wide) -2 else 0, -2, if (wide) 0f else 1f))
            if (wide && picker != null) title.addView(OverlayUi.text(context, "按顺序选出第一个在队角色", 11f, R.color.pocket_text_secondary).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            if (picker != null) {
                bar.addView(OverlayUi.button(context, "恢复默认") { picker?.defaults() }.apply { id = R.id.avatar_select_defaults })
                bar.addView(OverlayUi.button(context, "清空") { picker?.clear() }.apply { id = R.id.avatar_select_clear })
            }
        } else {
            bar.addView(OverlayUi.icon(context, R.drawable.ic_ui_map), LinearLayout.LayoutParams(dp(20), dp(20)))
            bar.addView(OverlayUi.text(context, "地图追踪", 15f, bold = true).apply { setPadding(dp(12), 0, dp(16), 0) })
            val tabs = OverlayUi.segments(context, listOf("路线", "设置"), tab,
                listOf(R.id.pathing_tab_routes, R.id.pathing_tab_settings)) { selectTab(it) }
            bar.addView(tabs); bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(OverlayUi.iconButton(context, R.drawable.ic_ui_my_location, "显示坐标（会关闭窗口）") { dismiss(); onToggleReadout() }.apply {
                if (isReadoutRunning()) imageTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, R.color.pocket_primary))
            })
        }
        bar.addView(OverlayUi.iconButton(context, R.drawable.ic_ui_close, "关闭地图追踪") { slot = null; dismiss() }.apply { id = R.id.pathing_window_close })
    }
    private fun openPicker(value: CandidateSlot) {
        input?.finish(); slot = value
        picker?.let { content?.removeView(it) }
        routes?.visibility = View.GONE; settingsPage?.visibility = View.GONE
        val widthDp = (params!!.width / context.resources.displayMetrics.density).toInt()
        picker = AvatarPickerPage(context, value, settings, avatarCatalog, input!!, widthDp >= 560, widthDp)
        content?.addView(picker, FrameLayout.LayoutParams(-1, -1)); buildHeader()
    }
    private fun back() {
        input?.finish(); picker?.let { content?.removeView(it) }; picker = null; slot = null
        numberEditor?.let { content?.removeView(it) }; numberEditor = null; selectTab(1)
    }
    private fun editNumber(title: String, value: String, validate: (String) -> String?) {
        input?.finish(); settingsPage?.visibility = View.GONE; routes?.visibility = View.GONE
        val column = OverlayUi.column(context).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val field = EditText(context).apply { setText(value); textSize = 13f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE }
        column.addView(OverlayUi.text(context, title)); column.addView(field)
        val error = OverlayUi.text(context, "", 11f, R.color.pocket_error); column.addView(error)
        column.addView(OverlayUi.button(context, "保存", true) {
            val reason = validate(field.text.toString()); error.text = reason.orEmpty()
            if (reason == null) back()
        })
        numberEditor = OverlayUi.scroll(context, column); content?.addView(numberEditor, FrameLayout.LayoutParams(-1, -1))
        input?.bind(field); buildHeader(); input?.begin(field)
    }
    private fun settingsSummary(): String {
        val config = settings.get()
        return listOf(config.mapMatchingMethod.label,
            if (config.useGadgetEnabled) "小道具 ${SettingsPage.seconds(config.useGadgetIntervalMs.toLong())}s" else "小道具关",
            config.mainAvatars.firstOrNull() ?: "行走位关").joinToString(" · ")
    }
    private fun loadCatalog() {
        if (loading || disposed) return
        loading = true; error = null
        loader.execute {
            try {
                // 转写按字符缓存，数千条中文路线不重复转写相同的目录和材料名称。
                val transliterator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower()") else null
                val cache = HashMap<Char, String>()
                val metadata = base.assets.open(RouteCatalog.METADATA_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val loaded = base.assets.open(RouteCatalog.INDEX_ASSET).bufferedReader(Charsets.UTF_8).use {
                    RouteCatalog.parse(it.readText(), metadata) { name ->
                        val syllables = name.map { char -> cache.getOrPut(char) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) transliterator?.transliterate(char.toString()) ?: char.toString() else char.toString()
                        } }
                        val full = syllables.joinToString("").filter { it.isLetterOrDigit() }
                        val initials = syllables.mapNotNull { s -> s.firstOrNull { it.isLetterOrDigit() } }.joinToString("")
                        "$full $initials".lowercase(Locale.ROOT)
                    }
                }
                main.post { loading = false; if (!disposed) { catalog = loaded; routes?.setCatalog(loaded) } }
            } catch (e: Exception) {
                main.post { loading = false; if (!disposed) { error = "无法读取路线目录：${e.message ?: e.javaClass.simpleName}"
                    log(error!!); routes?.loadFailed("读取失败，请关闭窗口后重试") } }
            }
        }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}

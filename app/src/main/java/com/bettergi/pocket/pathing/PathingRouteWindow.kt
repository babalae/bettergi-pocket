package com.bettergi.pocket.pathing

import android.app.AlertDialog
import android.text.InputType
import com.bettergi.pocket.pathing.party.AvatarCatalog
import com.bettergi.pocket.pathing.party.PathingParty
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.icu.text.Transliterator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.map.MapMatchingMethod
import com.bettergi.pocket.pathing.settings.PathingRouteStore
import com.bettergi.pocket.pathing.settings.PathingSettingsRepository
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.pathing.settings.RouteCatalog
import java.util.Locale
import java.math.BigDecimal
import java.util.concurrent.Executors

/**
 * 直接由服务挂载的路线与设置窗口，不启动 Activity，浏览时不向原神索取输入焦点。
 * 搜索和小道具间隔使用系统输入法，仅编辑时临时获取焦点，输入结束后立即归还。
 */
class PathingRouteWindow(
    context: Context,
    private val store: PathingRouteStore,
    private val settings: PathingSettingsRepository,
    private val onExecute: () -> Unit,
    private val onToggleReadout: () -> Unit,
    private val isReadoutRunning: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private val context = ContextThemeWrapper(context, R.style.Theme_BetterGIPocket)
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inputMethod = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var root: View? = null
    private var catalog: RouteCatalog? = null
    private var directory = ""
    private var query = ""
    private val selection = LinkedHashSet<String>()
    private var listing = RouteCatalog.Listing(emptyList(), false)
    private val avatarCatalog by lazy { AvatarCatalog.load(context.assets) }
    private var settingDialog: AlertDialog? = null
    private val avatarDropdown by lazy { AvatarMultiSelectDropdown(this.context) }
    private var disposed = false
    private var editingField: EditText? = null
    private var imeShown = false
    private val adapter = RouteAdapter(::open, ::toggle)
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            avatarDropdown.dismiss()
            root?.let { windows.updateViewLayout(it, windowParams()) }
        }
        override fun onLowMemory() = Unit
    }

    fun show() {
        if (root != null || disposed || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_pathing_window, null)
        root = view
        selection.clear()
        selection.addAll(store.selectedPaths())
        view.findViewById<View>(R.id.pathing_window_close).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.pathing_tab_routes).setOnClickListener { showSettings(false) }
        view.findViewById<View>(R.id.pathing_tab_settings).setOnClickListener { showSettings(true) }
        view.findViewById<View>(R.id.pathing_browser_up).setOnClickListener {
            finishInput()
            directory = directory.substringBeforeLast('/', "")
            query = ""
            render(resetScroll = true)
        }
        view.findViewById<View>(R.id.pathing_browser_directory).setOnClickListener {
            finishInput()
            directory = ""
            query = ""
            render(resetScroll = true)
        }
        view.findViewById<View>(R.id.pathing_select_visible).setOnClickListener {
            finishInput()
            listing.rows.forEach { selection.addAll(it.node.routePaths) }
            saveAndRender()
        }
        view.findViewById<View>(R.id.pathing_selection_clear).setOnClickListener {
            finishInput()
            selection.clear()
            saveAndRender()
        }
        view.findViewById<View>(R.id.pathing_execute).setOnClickListener {
            if (selection.isNotEmpty() && catalog != null) {
                store.select(selection)
                dismiss()
                onExecute()
            }
        }
        val gadgetInterval = view.findViewById<EditText>(R.id.pathing_setting_gadget_interval)
        setupInput(gadgetInterval)
        gadgetInterval.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (editingField !== gadgetInterval) return
                val interval = parseGadgetInterval(s.toString())
                gadgetInterval.error = if (interval == null) "请输入大于 0 的秒数，最多 3 位小数" else null
                if (interval != null) settings.update { it.copy(useGadgetIntervalMs = interval) }
            }
        })
        view.findViewById<SwitchCompat>(R.id.pathing_setting_gadget_enabled).setOnCheckedChangeListener { _, enabled ->
            if (enabled != settings.get().useGadgetEnabled) {
                settings.update { it.copy(useGadgetEnabled = enabled) }
                finishInput()
            }
        }
        view.findViewById<View>(R.id.pathing_setting_map_matching).setOnClickListener {
            settings.toggleMapMatchingMethod()
            renderSettings()
            render()
        }
        view.findViewById<View>(R.id.pathing_setting_readout).setOnClickListener {
            dismiss()
            onToggleReadout()
        }
        view.findViewById<View>(R.id.pathing_setting_main_avatar).setOnClickListener { anchor ->
            chooseAvatars(anchor, "行走位候选", settings.get().mainAvatars, PathingSettings.DEFAULT_WALKERS) { names ->
                settings.update { it.copy(mainAvatars = names) }
            }
        }
        view.findViewById<View>(R.id.pathing_setting_guardian_avatar).setOnClickListener { anchor ->
            chooseAvatars(anchor, "生存位 · 循环短战技", settings.get().guardianShortAvatars,
                PathingSettings.DEFAULT_SHORT_GUARDIANS) { names ->
                settings.update { it.copy(guardianShortAvatars = names, guardianLongAvatars = it.guardianLongAvatars - names.toSet()) }
            }
        }
        view.findViewById<View>(R.id.pathing_setting_guardian_press).setOnClickListener { anchor ->
            chooseAvatars(anchor, "生存位 · 循环长战技", settings.get().guardianLongAvatars,
                PathingSettings.DEFAULT_LONG_GUARDIANS) { names ->
                settings.update { it.copy(guardianLongAvatars = names, guardianShortAvatars = it.guardianShortAvatars - names.toSet()) }
            }
        }
        view.findViewById<View>(R.id.pathing_setting_guardian_interval).setOnClickListener { editGuardianInterval() }
        elementSettingIds.forEach { (element, id) ->
            view.findViewById<View>(id).setOnClickListener { anchor ->
                val defaults = settings.defaultElementCandidates(element)
                chooseAvatars(anchor, "${PathingParty.elementName(element)}元素采集候选",
                    settings.get().elementAvatars[element] ?: defaults, defaults, element) { names ->
                    settings.update { it.copy(elementAvatars = it.elementAvatars + (element to names)) }
                }
            }
        }
        val search = view.findViewById<EditText>(R.id.pathing_search)
        setupInput(search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (query != text) {
                    query = text
                    render(resetScroll = true)
                }
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (editingField != null && imeShown && !visible) {
                view.post { if (!imeShown) finishInput() }
            }
            imeShown = visible
            insets
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) finishInput()
            false
        }
        view.findViewById<View>(R.id.pathing_search_clear).setOnClickListener {
            query = ""
            render(resetScroll = true)
        }
        view.findViewById<RecyclerView>(R.id.pathing_browser_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PathingRouteWindow.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) finishInput()
                }
            })
        }
        windows.addView(view, windowParams())
        context.registerComponentCallbacks(configCallbacks)
        showSettings(false)
        render()
        if (catalog == null) loadCatalog()
    }

    private fun windowParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windows.currentWindowMetrics.bounds else null
        val width = bounds?.width() ?: metrics.widthPixels
        val height = bounds?.height() ?: metrics.heightPixels
        return WindowManager.LayoutParams(
            minOf(dp(560), width - dp(32)),
            minOf(dp(600), height - dp(48)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                (if (editingField != null) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
    }

    private fun setupInput(field: EditText) {
        field.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) beginInput(field)
            false
        }
        field.setOnClickListener { beginInput(field) }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && editingField !== field) beginInput(field)
        }
        field.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && editingField === field) inputMethod.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                finishInput()
                true
            } else false
        }
    }

    private fun beginInput(field: EditText) {
        val view = root ?: return
        if (editingField !== field) {
            editingField = field
            imeShown = false
            windows.updateViewLayout(view, windowParams())
        }
        field.requestFocus()
        field.post {
            if (root === view && editingField === field) inputMethod.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun finishInput() {
        val field = editingField ?: return
        editingField = null
        imeShown = false
        val view = root ?: return
        inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
        field.clearFocus()
        view.requestFocus()
        windows.updateViewLayout(view, windowParams())
        renderSettings()
    }

    fun dismiss() {
        val view = root ?: return
        settingDialog?.dismiss()
        settingDialog = null
        avatarDropdown.dismiss()
        finishInput()
        root = null
        windows.removeView(view)
        context.unregisterComponentCallbacks(configCallbacks)
    }

    fun release() {
        disposed = true
        dismiss()
        loader.shutdownNow()
    }

    private fun loadCatalog() {
        loader.execute {
            try {
                // 转写按字符缓存，数千条中文路线不重复转写相同的目录和材料名称。
                val transliterator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower()")
                } else null
                val cache = HashMap<Char, String>()
                val metadata = context.assets.open(RouteCatalog.METADATA_ASSET).bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }
                val loaded = context.assets.open(RouteCatalog.INDEX_ASSET).bufferedReader(Charsets.UTF_8).use {
                    RouteCatalog.parse(it.readText(), metadata) { name ->
                        val syllables = name.map { char ->
                            cache.getOrPut(char) { transliterator?.transliterate(char.toString()) ?: char.toString() }
                        }
                        val full = syllables.joinToString("").filter { it.isLetterOrDigit() }
                        val initials = syllables.mapNotNull { s -> s.firstOrNull { it.isLetterOrDigit() } }.joinToString("")
                        "$full $initials".lowercase(Locale.ROOT)
                    }
                }
                main.post {
                    if (disposed) return@post
                    catalog = loaded
                    val valid = loaded.resolve(selection).map { it.relativePath }
                    selection.clear()
                    selection.addAll(valid)
                    store.select(selection)
                    render(resetScroll = true)
                }
            } catch (e: Exception) {
                main.post {
                    if (disposed) return@post
                    log("无法读取路线目录：${e.message ?: e.javaClass.simpleName}")
                    root?.findViewById<TextView>(R.id.pathing_browser_empty)?.text = "读取失败，请关闭窗口后重试"
                }
            }
        }
    }

    private fun open(row: RouteCatalog.Row) {
        finishInput()
        if (row.node.file) {
            toggle(row)
        } else {
            directory = row.node.relativePath
            query = ""
            render(resetScroll = true)
        }
    }

    private fun toggle(row: RouteCatalog.Row) {
        finishInput()
        val paths = row.node.routePaths
        if (selection.containsAll(paths)) selection.removeAll(paths.toSet()) else selection.addAll(paths)
        saveAndRender()
    }

    private fun saveAndRender() {
        store.select(selection)
        render()
    }

    private fun render(resetScroll: Boolean = false) {
        val view = root ?: return
        val catalog = catalog
        listing = catalog?.browse(directory, query) ?: RouteCatalog.Listing(emptyList(), false)
        adapter.submit(listing.rows, selection, settings.get().mapMatchingMethod)
        view.findViewById<TextView>(R.id.pathing_browser_directory).text = directory.ifEmpty { "全部路线" }
        view.findViewById<View>(R.id.pathing_browser_up).apply {
            isEnabled = directory.isNotEmpty()
            alpha = if (isEnabled) 1f else 0.35f
        }
        view.findViewById<EditText>(R.id.pathing_search).apply {
            // 不重写输入法的组合文本，保留中文候选、光标与选区。
            if (text.toString() != query) {
                setText(query)
                setSelection(text.length)
            }
        }
        view.findViewById<TextView>(R.id.pathing_browser_hint).text = if (listing.truncated) {
            "仅显示前 ${RouteCatalog.SEARCH_LIMIT} 项，请缩小范围；选择仅作用于显示项"
        } else "点击目录进入，勾选目录可执行全部子目录路线"
        view.findViewById<TextView>(R.id.pathing_browser_empty).apply {
            visibility = if (listing.rows.isEmpty()) View.VISIBLE else View.GONE
            text = when {
                catalog == null -> "正在读取路线…"
                catalog.roots.isEmpty() -> "应用里还没有路线"
                else -> "没有匹配的路线，请清空筛选或返回上一级"
            }
        }
        view.findViewById<TextView>(R.id.pathing_selection_count).text = "已选 ${selection.size} 条路线"
        view.findViewById<TextView>(R.id.pathing_execute).apply {
            isEnabled = catalog != null && selection.isNotEmpty()
            alpha = if (isEnabled) 1f else 0.4f
            text = "执行 ${selection.size} 条"
        }
        if (resetScroll) view.findViewById<RecyclerView>(R.id.pathing_browser_list).scrollToPosition(0)
    }

    private fun showSettings(show: Boolean) {
        val view = root ?: return
        finishInput()
        view.findViewById<View>(R.id.pathing_routes_content).visibility = if (show) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.pathing_settings_content).visibility = if (show) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.pathing_tab_routes).alpha = if (show) 0.45f else 1f
        view.findViewById<View>(R.id.pathing_tab_settings).alpha = if (show) 1f else 0.45f
        renderSettings()
    }

    private fun renderSettings() {
        val config = settings.get()
        fun set(id: Int, text: String) { root?.findViewById<TextView>(id)?.text = text }
        set(R.id.pathing_setting_map_matching, "默认地图匹配方式：${config.mapMatchingMethod.label}")
        fun summary(title: String, names: List<String>): String {
            val detail = if (names.isEmpty()) "未选择 · 已关闭" else names.take(3).joinToString(" · ") +
                (if (names.size > 3) " 等 ${names.size} 人" else " · ${names.size} 人")
            return "$title  ▾\n$detail"
        }
        set(R.id.pathing_setting_main_avatar, summary("行走位", config.mainAvatars))
        set(R.id.pathing_setting_guardian_avatar, summary("生存位 · 循环短战技", config.guardianShortAvatars))
        set(R.id.pathing_setting_guardian_press, summary("生存位 · 循环长战技", config.guardianLongAvatars))
        set(R.id.pathing_setting_guardian_interval, "续盾间隔：${if (config.guardianIntervalSeconds == 0.0) "按角色战技冷却" else "${config.guardianIntervalSeconds} 秒"}")
        elementSettingIds.forEach { (element, id) ->
            set(id, summary("${PathingParty.elementName(element)}元素采集", config.elementAvatars[element] ?: settings.defaultElementCandidates(element)))
        }
        root?.findViewById<SwitchCompat>(R.id.pathing_setting_gadget_enabled)?.isChecked = config.useGadgetEnabled
        root?.findViewById<EditText>(R.id.pathing_setting_gadget_interval)?.apply {
            if (editingField !== this) {
                val seconds = BigDecimal.valueOf(config.useGadgetIntervalMs.toLong(), 3).stripTrailingZeros().toPlainString()
                if (text.toString() != seconds) setText(seconds)
                error = null
            }
        }
        root?.findViewById<TextView>(R.id.pathing_setting_readout)?.text =
            if (isReadoutRunning()) "停止坐标显示" else "显示坐标"
    }

    private fun parseGadgetInterval(text: String): Int? = try {
        BigDecimal(text).movePointRight(3).intValueExact().takeIf { it > 0 }
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private val elementSettingIds = mapOf(
        "Hydro" to R.id.pathing_setting_element_hydro,
        "Electro" to R.id.pathing_setting_element_electro,
        "Anemo" to R.id.pathing_setting_element_anemo,
        "Pyro" to R.id.pathing_setting_element_pyro,
    )

    private fun showSettingDialog(dialog: AlertDialog) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        finishInput()
        avatarDropdown.dismiss()
        settingDialog?.dismiss()
        settingDialog = dialog
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun chooseAvatars(anchor: View, title: String, selected: List<String>, defaults: List<String>,
                              element: String? = null, save: (List<String>) -> Unit) {
        finishInput()
        settingDialog?.dismiss()
        val options = avatarCatalog.avatars.filter { avatar ->
            avatar.nameEn != "Unknown" && (element == null ||
                avatar.element == element && (avatar.normalAttack || avatar.elementalSkill))
        }
        avatarDropdown.show(anchor, title, options, selected, defaults) { names ->
            save(names)
            renderSettings()
        }
    }

    private fun editGuardianInterval() {
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(settings.get().guardianIntervalSeconds.toString())
            selectAll()
        }
        val dialog = AlertDialog.Builder(context).setTitle("续盾间隔（秒，0 使用角色冷却）")
            .setView(field).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        showSettingDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val seconds = field.text.toString().toDoubleOrNull()
            if (seconds == null || !seconds.isFinite() || seconds != 0.0 && seconds !in 1.0..3600.0) {
                field.error = "请输入 0 或 1～3600 秒"
            } else {
                settings.update { it.copy(guardianIntervalSeconds = seconds) }
                renderSettings(); dialog.dismiss()
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private class RouteAdapter(
        private val onOpen: (RouteCatalog.Row) -> Unit,
        private val onToggle: (RouteCatalog.Row) -> Unit,
    ) : RecyclerView.Adapter<RouteAdapter.Holder>() {
        private var rows = emptyList<RouteCatalog.Row>()
        private var selection = emptySet<String>()
        private var defaultMethod = MapMatchingMethod.TemplateMatch

        fun submit(rows: List<RouteCatalog.Row>, selection: Set<String>, defaultMethod: MapMatchingMethod) {
            this.rows = rows
            this.selection = selection.toSet()
            this.defaultMethod = defaultMethod
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_pathing_route, parent, false),
        )
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val node = row.node
            val selected = node.routePaths.count { it in selection }
            holder.check.text = when (selected) {
                0 -> "□"
                node.routePaths.size -> "☑"
                else -> "▣"
            }
            holder.check.contentDescription = "${node.name}：已选 $selected / ${node.routePaths.size} 条，点击切换选择"
            holder.check.setOnClickListener { onToggle(row) }
            holder.open.setOnClickListener { onOpen(row) }
            holder.name.text = if (node.file) node.name else "${node.name}  ›"
            holder.detail.text = listOfNotNull(
                if (node.file) node.matchingDescription(defaultMethod) else "目录 · ${node.routePaths.size} 条路线",
                row.breadcrumb.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: TextView = view.findViewById(R.id.pathing_node_check)
            val open: View = view.findViewById(R.id.pathing_node_open)
            val name: TextView = view.findViewById(R.id.pathing_node_name)
            val detail: TextView = view.findViewById(R.id.pathing_node_detail)
        }
    }
}

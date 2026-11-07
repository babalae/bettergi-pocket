package com.bettergi.pocket.pathing.ui

import android.content.Context
import android.os.Parcelable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.PathingPreflight
import com.bettergi.pocket.pathing.settings.PathingRouteStore
import com.bettergi.pocket.pathing.settings.RouteCatalog
import com.bettergi.pocket.ui.*

class RouteBrowserState(var directory: String = "", var query: String = "", var scroll: Parcelable? = null)

/** 双栏浏览及执行队列；窄屏将队列收进下一级页面。 */
class RoutesPage(c: Context, private val wide: Boolean, private val state: RouteBrowserState,
                 private val store: PathingRouteStore, private val input: OverlayInput,
                 private val preflight: () -> PathingPreflight, private val fix: (PathingPreflight) -> Unit,
                 private val execute: () -> Unit, private val showSettings: () -> Unit,
                 private val changed: () -> Unit, private val summary: () -> String) : LinearLayout(c) {
    private var catalog: RouteCatalog? = null
    private val selection = LinkedHashSet(store.selectedPaths())
    private var listing = RouteCatalog.Listing(emptyList(), false)
    private var backup = emptySet<String>()
    private var highlighted: String? = null
    private val browser = OverlayUi.column(c)
    private val toolbar = OverlayUi.row(c)
    private val crumb = OverlayUi.row(c)
    private val search = EditText(c).apply {
        id = R.id.pathing_search; textSize = 13f; isSingleLine = true
        setPadding(dp(8), 0, dp(8), 0); setBackgroundResource(R.drawable.bg_pocket_group)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }
    private val all = OverlayUi.iconButton(c, R.drawable.ic_ui_check_box_outline_blank, "选择列表中显示的项") { toggleVisible() }
    private val up = OverlayUi.iconButton(c, R.drawable.ic_ui_arrow_back, "返回上一级") { navigate(state.directory.substringBeforeLast('/', "")) }
    private val clearSearch = OverlayUi.iconButton(c, R.drawable.ic_ui_cancel, "清除搜索") { search.setText(""); input.finish() }
    private val searchToggle = OverlayUi.iconButton(c, R.drawable.ic_ui_search, "搜索当前目录") { showSearch(true); input.begin(search) }
    private val hint = OverlayUi.text(c, "", 11f, R.color.pocket_warning)
    private val empty = OverlayUi.column(c)
    private val emptyText = OverlayUi.text(c, "正在读取路线目录", 13f)
    private val progress = ProgressBar(c)
    private val adapter = RouteAdapter(::open, ::toggle)
    private val list = RecyclerView(c).apply { id = R.id.pathing_browser_list; layoutManager = LinearLayoutManager(c); adapter = this@RoutesPage.adapter }
    private val queue = SelectionQueue(c, ::locate, { selection.removeAll(it.routePaths.toSet()); save() }, ::clearQueue, ::restoreQueue)
    private val executeButton = OverlayUi.button(c, "选择路线后执行", true) { attemptExecute() }
    private val warning = OverlayUi.row(c)
    private val warningText = OverlayUi.text(c, "", 11f, R.color.pocket_warning)
    private val settingsSummary = OverlayUi.button(c, "", click = showSettings)
    private val queuePanel = OverlayUi.column(c)
    private val compactBar = OverlayUi.row(c)
    private val compactCount = OverlayUi.button(c, "已选 0 条 · 查看") { showQueue(true) }
    private var queueOnly = false
    init {
        orientation = if (wide) HORIZONTAL else VERTICAL
        toolbar.minimumHeight = resources.getDimensionPixelSize(R.dimen.pocket_toolbar)
        toolbar.setPadding(dp(4), 0, dp(8), 0)
        toolbar.addView(all); toolbar.addView(up)
        toolbar.addView(crumb, LayoutParams(0, -2, 1f))
        toolbar.addView(search, LayoutParams(if (wide) dp(188) else 0, dp(28), if (wide) 0f else 1f))
        toolbar.addView(clearSearch); toolbar.addView(searchToggle)
        input.bind(search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (state.query != s.toString()) { state.query = s.toString(); render(true) } }
        })
        browser.addView(toolbar); browser.addView(hint.apply { setPadding(dp(12), 0, dp(8), 0) })
        val body = FrameLayout(c)
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.gravity = Gravity.CENTER
        empty.addView(progress, LayoutParams(dp(32), dp(32)))
        empty.addView(OverlayUi.icon(c, R.drawable.ic_ui_search), LayoutParams(dp(24), dp(24)))
        empty.addView(emptyText)
        empty.addView(OverlayUi.button(c, "清除搜索") { search.setText("") }.apply { tag = "clear" })
        body.addView(empty, FrameLayout.LayoutParams(-1, -1)); browser.addView(body, LayoutParams(-1, 0, 1f))
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, next: Int) { if (next == RecyclerView.SCROLL_STATE_DRAGGING) input.finish() }
        })
        queuePanel.addView(queue, LayoutParams(-1, 0, 1f))
        queuePanel.addView(settingsSummary, LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
        OverlayUi.leadingIcon(settingsSummary, R.drawable.ic_ui_tune)
        warning.apply {
            minimumHeight = dp(36); setPadding(dp(8), dp(4), dp(4), dp(4)); setBackgroundResource(R.drawable.bg_pocket_warning)
            addView(OverlayUi.icon(c, R.drawable.ic_ui_warning, R.color.pocket_warning), LayoutParams(dp(16), dp(16)))
            addView(warningText, LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            addView(OverlayUi.button(c, "去开启") { fix(preflight()) }.apply { tag = "fix" })
        }
        queuePanel.addView(warning, LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(4)) })
        if (wide) {
            addView(browser, LayoutParams(0, -1, 1f)); addView(View(c).apply { setBackgroundResource(R.color.pocket_divider) }, LayoutParams(dp(1), -1))
            addView(queuePanel, LayoutParams(dp(240), -1))
            queuePanel.addView(executeButton, LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(12)) })
        } else {
            addView(browser, LayoutParams(-1, 0, 1f)); addView(queuePanel, LayoutParams(-1, 0, 1f)); queuePanel.visibility = GONE
            compactBar.minimumHeight = dp(48)
            compactBar.addView(compactCount, LayoutParams(0, -2, 1f)); compactBar.addView(executeButton, LayoutParams(0, -2, 1f))
            addView(compactBar)
        }
        search.setText(state.query); showSearch(wide || state.query.isNotEmpty()); render()
        state.scroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
    }
    fun setCatalog(loaded: RouteCatalog) {
        catalog = loaded
        val valid = loaded.resolve(selection).map { it.relativePath }; selection.clear(); selection.addAll(valid)
        if (state.directory.isNotEmpty() && loaded.node(state.directory) == null) state.directory = ""
        save()
    }
    fun loadFailed(message: String) { progress.visibility = GONE; emptyText.text = message }
    fun saveScroll() { state.scroll = list.layoutManager?.onSaveInstanceState() }
    private fun showSearch(show: Boolean) {
        search.visibility = if (show) VISIBLE else GONE
        crumb.visibility = if (!wide && show) GONE else VISIBLE
        searchToggle.visibility = if (wide || show) GONE else VISIBLE
    }
    private fun navigate(path: String) {
        input.finish(); state.directory = path; state.query = ""; search.setText(""); showSearch(wide); render(true)
    }
    private fun open(row: RouteCatalog.Row) { if (row.node.file) toggle(row) else navigate(row.node.relativePath) }
    private fun toggle(row: RouteCatalog.Row) {
        input.finish(); val paths = row.node.routePaths
        if (selection.containsAll(paths)) selection.removeAll(paths.toSet()) else selection.addAll(paths)
        save()
    }
    private fun toggleVisible() {
        input.finish(); val paths = listing.rows.flatMap { it.node.routePaths }
        if (selection.containsAll(paths)) selection.removeAll(paths.toSet()) else selection.addAll(paths)
        save()
    }
    private fun save() { store.select(selection, catalog?.selectionSummary(selection)); changed(); render() }
    private fun clearQueue() { input.finish(); backup = selection.toSet(); val count = selection.size; selection.clear(); save(); queue.showUndo(count) }
    private fun restoreQueue() { selection.clear(); selection.addAll(backup); backup = emptySet(); queue.hideUndo(); save() }
    private fun locate(node: RouteCatalog.Node) {
        val target = if (node.file) node.relativePath.substringBeforeLast('/', "") else node.relativePath
        highlighted = node.relativePath; navigate(target); if (!wide) showQueue(false)
        if (node.file) list.scrollToPosition(listing.rows.indexOfFirst { it.node.relativePath == node.relativePath }.coerceAtLeast(0))
    }
    private fun showQueue(show: Boolean) {
        input.finish(); queueOnly = show; browser.visibility = if (show) GONE else VISIBLE; queuePanel.visibility = if (show) VISIBLE else GONE
        compactCount.text = if (show) "返回路线列表" else "已选 ${selection.size} 条 · 查看"
        compactCount.setOnClickListener { showQueue(!queueOnly) }
    }
    fun refreshPreflight() {
        val check = preflight(); warning.visibility = if (check.ready) GONE else VISIBLE
        warningText.text = check.reason
        warning.findViewWithTag<View>("fix").visibility = if (check.action == null) GONE else VISIBLE
        executeButton.isEnabled = selection.isNotEmpty() && catalog != null && check.ready
        executeButton.setTextColor(androidx.core.content.ContextCompat.getColor(context,
            if (executeButton.isEnabled) R.color.pocket_on_primary else R.color.pocket_text_disabled))
        executeButton.background.alpha = if (executeButton.isEnabled) 255 else 31
        settingsSummary.text = summary()
        TooltipCompat.setTooltipText(executeButton, check.reason ?: "按执行队列顺序开始")
        if (!wide && !queueOnly && !check.ready) compactCount.text = "查看队列 · 检查条件"
    }
    private fun attemptExecute() { input.finish(); refreshPreflight(); if (executeButton.isEnabled) execute() }
    private fun render(reset: Boolean = false) {
        listing = catalog?.browse(state.directory, state.query) ?: RouteCatalog.Listing(emptyList(), false)
        adapter.submitList(listing.rows.map { RouteItem(it, it.node.routePaths.count(selection::contains), it.node.relativePath == highlighted) })
        val visiblePaths = listing.rows.flatMap { it.node.routePaths }; val selected = visiblePaths.count(selection::contains)
        all.setImageResource(when { selected == 0 -> R.drawable.ic_ui_check_box_outline_blank
            selected == visiblePaths.size -> R.drawable.ic_ui_check_box else -> R.drawable.ic_ui_indeterminate_check_box })
        TooltipCompat.setTooltipText(all, "选择列表中显示的 ${listing.rows.size} 项")
        all.contentDescription = "选择列表中显示的 ${listing.rows.size} 项，已选 $selected 条路线"
        up.visibility = if (state.directory.isEmpty()) GONE else VISIBLE
        crumb.removeAllViews()
        val paths = state.directory.split('/').filter(String::isNotEmpty)
        val parts = listOf("全部路线" to "") + paths.mapIndexed { i, name -> name.substringBefore('@') to paths.take(i + 1).joinToString("/") }
        if (parts.size > if (wide) 2 else 3) crumb.addView(OverlayUi.text(context, "…", 12f, R.color.pocket_primary).apply {
            minimumWidth = dp(20); minimumHeight = dp(32); setOnClickListener { navigate("") }
            contentDescription = "返回全部路线"; TooltipCompat.setTooltipText(this, contentDescription)
        })
        parts.takeLast(if (wide) 2 else 3).forEachIndexed { i, (label, path) ->
            if (i > 0) crumb.addView(OverlayUi.icon(context, R.drawable.ic_ui_chevron_right), LayoutParams(dp(12), dp(16)))
            crumb.addView(OverlayUi.text(context, label, 12f, R.color.pocket_primary).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.START; setPadding(dp(4), 0, dp(4), 0)
                minimumHeight = dp(32); setOnClickListener { navigate(path) }; TooltipCompat.setTooltipText(this, "打开 $label")
            }, LayoutParams(0, -2, 1f))
        }
        search.hint = if (state.directory.isEmpty()) "搜索路线，支持拼音首字母" else "在「${paths.lastOrNull()?.substringBefore('@')}」中搜索"
        clearSearch.visibility = if (state.query.isEmpty()) GONE else VISIBLE
        hint.visibility = if (listing.truncated) VISIBLE else GONE
        hint.text = "只显示前 ${RouteCatalog.SEARCH_LIMIT} 项，请输入更多关键词"
        empty.visibility = if (listing.rows.isEmpty()) VISIBLE else GONE
        progress.visibility = if (catalog == null) VISIBLE else GONE
        empty.getChildAt(1).visibility = if (catalog == null) GONE else VISIBLE
        emptyText.text = when { catalog == null -> "正在读取路线目录"; state.query.isNotEmpty() -> "没有匹配「${state.query}」的路线"; else -> "此目录没有路线" }
        empty.findViewWithTag<View>("clear").visibility = if (state.query.isEmpty()) GONE else VISIBLE
        queue.submit(catalog?.queueEntries(selection).orEmpty(), selection.size)
        if (!queueOnly) compactCount.text = "已选 ${selection.size} 条 · 查看"
        executeButton.text = if (selection.isEmpty()) "选择路线后执行" else "执行 ${selection.size} 条"
        refreshPreflight(); if (reset) list.scrollToPosition(0)
    }
    private data class RouteItem(val row: RouteCatalog.Row, val count: Int, val highlighted: Boolean)
    private class RouteAdapter(val open: (RouteCatalog.Row) -> Unit, val toggle: (RouteCatalog.Row) -> Unit) :
        ListAdapter<RouteItem, RouteAdapter.Holder>(object : DiffUtil.ItemCallback<RouteItem>() {
            override fun areItemsTheSame(a: RouteItem, b: RouteItem) = a.row.node.relativePath == b.row.node.relativePath
            override fun areContentsTheSame(a: RouteItem, b: RouteItem) = a == b
        }) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val c = parent.context; val root = OverlayUi.column(c)
            val row = OverlayUi.row(c).apply { minimumHeight = dp(44); setPadding(dp(4), 0, dp(8), 0) }
            val check = OverlayUi.iconButton(c, R.drawable.ic_ui_check_box_outline_blank, "选择路线") {}
            val icon = OverlayUi.icon(c, R.drawable.ic_ui_folder, R.color.pocket_primary)
            val texts = OverlayUi.column(c); val name = OverlayUi.text(c); val detail = OverlayUi.text(c, "", 11f, R.color.pocket_text_secondary)
            texts.addView(name); texts.addView(detail); row.addView(check); row.addView(icon, LayoutParams(row.dp(20), row.dp(20)).apply { marginStart = row.dp(4); marginEnd = row.dp(12) })
            row.addView(texts, LayoutParams(0, -2, 1f)); val count = OverlayUi.text(c, "", 12f, R.color.pocket_primary, true)
            row.addView(count); val chevron = OverlayUi.icon(c, R.drawable.ic_ui_chevron_right); row.addView(chevron, LayoutParams(row.dp(20), row.dp(20)))
            root.addView(row); root.foreground = androidx.core.content.ContextCompat.getDrawable(c, R.drawable.fg_pocket_route_divider)
            root.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return Holder(root, row, check, icon, name, detail, count, chevron)
        }
        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = getItem(position); val row = item.row; val node = row.node
            h.check.setImageResource(when(item.count) { 0 -> R.drawable.ic_ui_check_box_outline_blank; node.routePaths.size -> R.drawable.ic_ui_check_box; else -> R.drawable.ic_ui_indeterminate_check_box })
            h.check.contentDescription = "${node.displayName}，已选 ${item.count}/${node.routePaths.size}，点击切换选择"
            TooltipCompat.setTooltipText(h.check, h.check.contentDescription)
            h.icon.setImageResource(if (node.file) R.drawable.ic_ui_description else R.drawable.ic_ui_folder)
            h.name.text = node.displayName; h.name.maxLines = 1; h.name.ellipsize = TextUtils.TruncateAt.MIDDLE
            val badge = if (!node.matchMetadataReadable) "匹配方式读取失败" else node.mapMatchMethod?.takeIf(String::isNotBlank)?.let { if (it.contains("sift", true)) "SIFT" else "模板" }
            val detail = listOfNotNull(node.author.takeIf(String::isNotEmpty)?.let { "@$it" }, badge, row.breadcrumb.takeIf(String::isNotEmpty)).joinToString(" · ")
            h.detail.text = detail; h.detail.maxLines = 1; h.detail.ellipsize = TextUtils.TruncateAt.START
            h.detail.visibility = if (detail.isEmpty()) GONE else VISIBLE
            h.row.minimumHeight = h.row.dp(if (row.breadcrumb.isEmpty()) 44 else 52)
            h.count.text = if (node.file) "" else if (item.count in 1 until node.routePaths.size) "${item.count}/${node.routePaths.size}" else node.routePaths.size.toString()
            h.chevron.visibility = if (node.file) GONE else VISIBLE
            h.itemView.setBackgroundColor(if (item.highlighted || item.count == node.routePaths.size) 0x12C9A36A else android.graphics.Color.TRANSPARENT)
            h.check.setOnClickListener { toggle(row) }; h.row.setOnClickListener { open(row) }
            TooltipCompat.setTooltipText(h.row, node.name + if (!node.matchMetadataReadable) "：匹配方式元数据读取失败" else "")
        }
        class Holder(root: View, val row: LinearLayout, val check: ImageButton, val icon: ImageView,
                     val name: TextView, val detail: TextView, val count: TextView, val chevron: ImageView) : RecyclerView.ViewHolder(root)
    }
}

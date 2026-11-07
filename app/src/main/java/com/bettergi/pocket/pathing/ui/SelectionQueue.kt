package com.bettergi.pocket.pathing.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.settings.RouteCatalog
import com.bettergi.pocket.ui.*

/** 压缩队列与浏览器共用目录顺序。清空可撤销，单组可以移除或定位。 */
class SelectionQueue(c: Context, private val open: (RouteCatalog.Node) -> Unit,
                     private val remove: (RouteCatalog.Node) -> Unit, private val clear: () -> Unit,
                     private val restore: () -> Unit) : LinearLayout(c) {
    private val title = OverlayUi.text(c, "执行队列", 13f, bold = true)
    private val clearButton = OverlayUi.iconButton(c, R.drawable.ic_ui_delete_sweep, "清空执行队列", clear)
    private val empty = OverlayUi.text(c, "勾选左侧路线加入队列", 11f, R.color.pocket_text_secondary)
    private val undo = OverlayUi.button(c, "", click = restore)
    private val handler = Handler(Looper.getMainLooper())
    private val hideUndo = Runnable { undo.visibility = GONE }
    private val adapter = QueueAdapter(open, remove)
    init {
        orientation = VERTICAL
        addView(OverlayUi.row(c).apply {
            minimumHeight = dp(40); setPadding(dp(12), 0, dp(8), 0)
            addView(title, LayoutParams(0, -2, 1f)); addView(clearButton)
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, "按目录顺序执行，完整目录合并显示")
        })
        addView(undo.apply { visibility = GONE; OverlayUi.leadingIcon(this, R.drawable.ic_ui_undo) }, LayoutParams(-1, -2))
        addView(empty.apply { gravity = android.view.Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12)) })
        addView(RecyclerView(c).apply { layoutManager = LinearLayoutManager(c); adapter = this@SelectionQueue.adapter }, LayoutParams(-1, 0, 1f))
    }
    fun submit(entries: List<RouteCatalog.Node>, count: Int) {
        title.text = "执行队列  $count 条"; adapter.submitList(entries)
        clearButton.isEnabled = count > 0; clearButton.alpha = if (count > 0) 1f else .38f
        empty.visibility = if (count == 0) VISIBLE else GONE
    }
    fun showUndo(count: Int) {
        handler.removeCallbacks(hideUndo); undo.text = "已清空 $count 条 · 撤销"
        undo.visibility = VISIBLE; handler.postDelayed(hideUndo, 5000)
    }
    fun hideUndo() { handler.removeCallbacks(hideUndo); undo.visibility = GONE }
    override fun onDetachedFromWindow() { handler.removeCallbacks(hideUndo); super.onDetachedFromWindow() }

    private class QueueAdapter(val open: (RouteCatalog.Node) -> Unit, val remove: (RouteCatalog.Node) -> Unit) :
        ListAdapter<RouteCatalog.Node, QueueAdapter.Holder>(object : DiffUtil.ItemCallback<RouteCatalog.Node>() {
            override fun areItemsTheSame(a: RouteCatalog.Node, b: RouteCatalog.Node) = a.relativePath == b.relativePath
            override fun areContentsTheSame(a: RouteCatalog.Node, b: RouteCatalog.Node) = a == b
        }) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val c = parent.context
            val row = OverlayRow(c, "", R.drawable.ic_ui_folder, dense = true)
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return Holder(row)
        }
        override fun onBindViewHolder(h: Holder, position: Int) {
            val node = getItem(position); val row = h.row
            val detail = node.author.takeIf { it.isNotEmpty() }?.let { "@$it" }
                ?: node.relativePath.substringBeforeLast('/', "").substringAfterLast('/').substringBefore('@')
            val label = android.text.SpannableStringBuilder(node.displayName)
            if (detail.isNotEmpty() && !node.file) {
                val start = label.length; label.append(" ").append(detail)
                label.setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(row.context, R.color.pocket_text_secondary)),
                    start, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(android.text.style.RelativeSizeSpan(11f / 13f), start, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            (row.getChildAt(0) as android.widget.ImageView).setImageResource(if (node.file) R.drawable.ic_ui_description else R.drawable.ic_ui_folder)
            row.titleView.apply { text = label; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
            row.trailing.removeAllViews()
            row.trailing.addView(OverlayUi.text(row.context, node.routePaths.size.toString(), 12f, R.color.pocket_primary, true))
            row.trailing.addView(OverlayUi.iconButton(row.context, R.drawable.ic_ui_close, "移除 ${node.displayName}") { remove(node) })
            row.setOnClickListener { open(node) }
            androidx.appcompat.widget.TooltipCompat.setTooltipText(row, node.relativePath)
        }
        class Holder(val row: OverlayRow) : RecyclerView.ViewHolder(row)
    }
}

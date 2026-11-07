package com.bettergi.pocket.pathing.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.*
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.party.AvatarCatalog
import com.bettergi.pocket.pathing.party.PathingParty
import com.bettergi.pocket.pathing.party.PartyAvatar
import com.bettergi.pocket.pathing.settings.*
import com.bettergi.pocket.ui.*
import java.util.Locale

/** 同窗角色候选。网格顺序固定，已选列表支持拖动及无障碍排序，每次编辑立即保存。 */
class AvatarPickerPage(c: Context, private val slot: CandidateSlot, private val repo: PathingSettingsRepository,
                       private val catalog: AvatarCatalog, private val input: OverlayInput,
                       wide: Boolean, widthDp: Int) : LinearLayout(c) {
    private val selection = OrderedAvatarSelection(slot.names(repo.get(), repo))
    private val selectedTitle = OverlayUi.text(c, "", 12f, R.color.pocket_text_secondary, true).apply { id = R.id.avatar_select_count }
    private val notice = OverlayUi.text(c, "", 11f, R.color.pocket_warning)
    private val undo = OverlayUi.button(c, "撤销") { undo() }
    private var backup: PathingSettings? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideUndo = Runnable { backup = null; notice.visibility = GONE; undo.visibility = GONE }
    private var query = ""
    private var filter: String? = slot.element
    private val order = listOf("Pyro", "Cryo", "Geo", "Hydro", "Anemo", "Electro", "Dendro")
    private val options = catalog.avatars.filter { it.nameEn != "Unknown" &&
        (slot.element == null || it.element == slot.element && (it.normalAttack || it.elementalSkill)) }
        .sortedBy { order.indexOf(it.element).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    private val selectedAdapter = SelectedAdapter()
    private val gridAdapter = GridAdapter()
    private val selected = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c); adapter = selectedAdapter }
    private val grid = RecyclerView(c).apply {
        layoutManager = GridLayoutManager(c, if (wide) ((widthDp - if (widthDp >= 700) 248 else 200) - 24).div(88).coerceAtLeast(3) else (widthDp - 24).div(88).coerceAtLeast(2))
        adapter = gridAdapter
        itemAnimator = null
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) { if (state == RecyclerView.SCROLL_STATE_DRAGGING) input.finish() }
        })
    }
    private val empty = OverlayUi.text(c, "没有匹配的角色", 13f).apply { gravity = android.view.Gravity.CENTER }
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.adapterPosition; val b = to.adapterPosition
            if (a !in selection.values.indices || b !in selection.values.indices) return false
            selection.move(selection.values[a], b - a); save(); return true
        }
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
    })
    init {
        orientation = if (wide) HORIZONTAL else VERTICAL
        val chosen = OverlayUi.column(c)
        chosen.addView(OverlayUi.row(c).apply {
            minimumHeight = dp(32); setPadding(dp(12), 0, dp(8), 0)
            addView(selectedTitle, LayoutParams(0, -2, 1f)); addView(OverlayUi.text(c, "拖动排序", 11f, R.color.pocket_text_secondary))
        })
        chosen.addView(selected, LayoutParams(-1, 0, 1f)); touchHelper.attachToRecyclerView(selected)
        val message = OverlayUi.row(c).apply {
            addView(notice, LayoutParams(0, -2, 1f)); addView(undo); setPadding(dp(8), 0, dp(8), 0)
        }
        chosen.addView(message); notice.visibility = GONE; undo.visibility = GONE
        OverlayUi.leadingIcon(undo, R.drawable.ic_ui_undo)
        val available = OverlayUi.column(c).apply { setPadding(dp(8), 0, dp(8), 0) }
        val search = EditText(c).apply {
            id = R.id.avatar_select_search; textSize = 13f; isSingleLine = true; hint = "搜索中文名、英文名或别名"
            setPadding(dp(8), 0, dp(8), 0); setBackgroundResource(R.drawable.bg_pocket_group)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        input.bind(search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { query = s.toString().trim().lowercase(Locale.ROOT); renderGrid() }
        })
        available.addView(OverlayUi.row(c).apply {
            minimumHeight = dp(40); addView(OverlayUi.icon(c, R.drawable.ic_ui_search), LayoutParams(dp(20), dp(20)))
            addView(search, LayoutParams(0, dp(28), 1f))
            addView(OverlayUi.iconButton(c, R.drawable.ic_ui_cancel, "清除搜索") { search.setText(""); input.finish() })
            if (slot.element == null) {
                val filters = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
                val chips = OverlayUi.row(c)
                (listOf<String?>(null) + listOf("Hydro", "Pyro", "Electro", "Anemo", "Cryo", "Geo", "Dendro")).forEach { element ->
                    chips.addView(OverlayUi.button(c, if (element == null) "全部" else elementLabel(element)) {
                        input.finish(); filter = element
                        for (i in 0 until chips.childCount) chips.getChildAt(i).alpha = if (chips.getChildAt(i).tag == element) 1f else .5f
                        renderGrid()
                    }.apply { tag = element; setPadding(dp(6), 0, dp(6), 0)
                        setTextColor(elementColor(c, element)); alpha = if (element == null) 1f else .5f })
                }
                filters.addView(chips)
                // 宽屏将筛选与搜索放在同一行；窄屏可水平滚动以保留搜索宽度。
                addView(filters, LayoutParams(if (wide) dp(216) else dp(140), -2))
            }
        })
        val frame = FrameLayout(c); frame.addView(grid, FrameLayout.LayoutParams(-1, -1)); frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        available.addView(frame, LayoutParams(-1, 0, 1f))
        if (wide) {
            addView(chosen, LayoutParams(dp(if (widthDp >= 700) 248 else 200), -1))
            addView(View(c).apply { setBackgroundResource(R.color.pocket_divider) }, LayoutParams(dp(1), -1))
            addView(available, LayoutParams(0, -1, 1f))
        } else { addView(chosen, LayoutParams(-1, 0, 1f)); addView(available, LayoutParams(-1, 0, 2f)) }
        render()
    }
    private fun toggle(name: String) {
        input.finish()
        val config = repo.get()
        val other = when (slot) { CandidateSlot.ShortGuardian -> config.guardianLongAvatars; CandidateSlot.LongGuardian -> config.guardianShortAvatars; else -> emptyList() }
        val moved = name !in selection.values && name in other
        selection.toggle(name); save()
        if (moved) showNotice("已从${if (slot == CandidateSlot.ShortGuardian) "长E" else "短E"}组移出 $name", false)
    }
    private fun save() { slot.save(repo, selection.values); render() }
    private fun render() {
        selectedTitle.text = if (selection.values.isEmpty()) "未选择，此项关闭" else "已选 ${selection.values.size} 人"
        selectedAdapter.submit(selection.values); renderGrid()
    }
    private fun renderGrid() {
        val config = repo.get(); val other = when (slot) { CandidateSlot.ShortGuardian -> config.guardianLongAvatars; CandidateSlot.LongGuardian -> config.guardianShortAvatars; else -> emptyList() }
        val filtered = options.filter { avatar -> (filter == null || avatar.element == filter) &&
            (query.isEmpty() || (listOf(avatar.name, avatar.nameEn) + avatar.aliases).any { it.lowercase(Locale.ROOT).contains(query) }) }
        gridAdapter.submitList(filtered.map { GridItem(it, selection.values.indexOf(it.name) + 1,
            if (it.name in other) if (slot == CandidateSlot.ShortGuardian) "长E" else "短E" else "") })
        empty.visibility = if (filtered.isEmpty()) VISIBLE else GONE
    }
    fun clear() = replace(emptyList(), "已清空候选")
    fun defaults() = replace(slot.defaults(repo), "已恢复默认")
    private fun replace(names: List<String>, message: String) { input.finish(); backup = repo.get(); selection.replace(names); save(); showNotice(message, true) }
    private fun undo() {
        val before = backup ?: return
        // 同时恢复互斥组，撤销恢复默认时移出的角色也能回来。
        repo.update { before }; selection.replace(slot.names(before, repo)); backup = null; handler.removeCallbacks(hideUndo)
        notice.visibility = GONE; undo.visibility = GONE; render()
    }
    private fun showNotice(message: String, canUndo: Boolean) {
        handler.removeCallbacks(hideUndo); notice.text = message; notice.visibility = VISIBLE
        undo.visibility = if (canUndo) VISIBLE else GONE; handler.postDelayed(hideUndo, 5000)
    }
    override fun onDetachedFromWindow() { handler.removeCallbacks(hideUndo); super.onDetachedFromWindow() }
    private inner class SelectedAdapter : RecyclerView.Adapter<SelectedAdapter.Holder>() {
        private var names = emptyList<String>()
        fun submit(next: List<String>) {
            val old = names; names = next
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(a: Int, b: Int) = old[a] == next[b]
                override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b] && a == b
            }).dispatchUpdatesTo(this)
        }
        override fun getItemCount() = names.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val row = OverlayUi.row(context).apply { minimumHeight = dp(40); setPadding(dp(12), 0, dp(4), 0); layoutParams = RecyclerView.LayoutParams(-1, -2) }
            val number = OverlayUi.text(context, "", 12f, R.color.pocket_primary, true)
            val drag = OverlayUi.iconButton(context, R.drawable.ic_ui_drag_indicator, "长按拖动排序") {}
            val name = OverlayUi.text(context); val detail = OverlayUi.text(context, "", 11f, R.color.pocket_text_secondary)
            row.addView(number); row.addView(drag); row.addView(OverlayUi.row(context).apply {
                addView(name, LayoutParams(-2, -2)); addView(detail, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            }, LayoutParams(0, -2, 1f))
            val remove = OverlayUi.iconButton(context, R.drawable.ic_ui_close, "移除角色") {}; row.addView(remove)
            return Holder(row, number, drag, name, detail, remove)
        }
        override fun onBindViewHolder(h: Holder, index: Int) {
            val name = names[index]; val avatar = catalog.byName(name)
            h.number.text = (index + 1).toString(); h.name.text = name
            h.detail.text = avatar?.let {
                val cooldown = if (slot == CandidateSlot.LongGuardian && it.skillHoldCd > 0) it.skillHoldCd else it.skillCd
                "${elementLabel(it.element)} · E ${java.math.BigDecimal.valueOf(cooldown).stripTrailingZeros().toPlainString()}s"
            }.orEmpty()
            h.detail.setTextColor(elementColor(context, avatar?.element)); h.detail.maxLines = 1; h.detail.ellipsize = TextUtils.TruncateAt.END
            h.remove.contentDescription = "移除 $name"; h.remove.setOnClickListener { toggle(name) }
            h.drag.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) { input.finish(); touchHelper.startDrag(h) }; false }
            ViewCompat.replaceAccessibilityAction(h.itemView, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD, "提高优先级") { _, _ -> selection.move(name, -1); save(); true }
            ViewCompat.replaceAccessibilityAction(h.itemView, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD, "降低优先级") { _, _ -> selection.move(name, 1); save(); true }
            h.itemView.contentDescription = "优先级 ${index + 1}，$name，${h.detail.text}"
        }
        inner class Holder(v: View, val number: TextView, val drag: ImageButton, val name: TextView, val detail: TextView, val remove: ImageButton) : RecyclerView.ViewHolder(v)
    }
    private data class GridItem(val avatar: PartyAvatar, val priority: Int, val otherGroup: String)
    private inner class GridAdapter : androidx.recyclerview.widget.ListAdapter<GridItem, GridAdapter.Holder>(object : DiffUtil.ItemCallback<GridItem>() {
        override fun areItemsTheSame(a: GridItem, b: GridItem) = a.avatar.name == b.avatar.name
        override fun areContentsTheSame(a: GridItem, b: GridItem) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val row = OverlayUi.row(context).apply { minimumHeight = dp(32); setPadding(dp(6), dp(4), dp(6), dp(4)) }
            val dot = View(context); row.addView(dot, LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) })
            val name = OverlayUi.text(context, "", 13f).apply { id = R.id.avatar_option_name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            row.addView(name, LayoutParams(0, -2, 1f)); val number = OverlayUi.text(context, "", 11f, R.color.pocket_primary, true); row.addView(number)
            row.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            return Holder(row, dot, name, number)
        }
        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = getItem(position); h.name.text = item.avatar.name
            h.number.text = if (item.priority > 0) item.priority.toString() else item.otherGroup
            h.dot.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(elementColor(context, item.avatar.element)) }
            h.itemView.setBackgroundResource(if (item.priority > 0) R.drawable.bg_pocket_chip else R.drawable.bg_pocket_group)
            h.itemView.setOnClickListener { toggle(item.avatar.name) }
            h.itemView.contentDescription = "${item.avatar.name}，${if (item.priority > 0) "已选，优先级 ${item.priority}" else "未选"}${item.otherGroup}"
            androidx.appcompat.widget.TooltipCompat.setTooltipText(h.itemView, "${item.avatar.name} · ${item.avatar.nameEn}")
        }
        inner class Holder(v: View, val dot: View, val name: TextView, val number: TextView) : RecyclerView.ViewHolder(v)
    }
}

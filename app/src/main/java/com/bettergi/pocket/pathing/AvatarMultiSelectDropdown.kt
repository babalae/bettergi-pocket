package com.bettergi.pocket.pathing

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.party.PartyAvatar
import com.bettergi.pocket.pathing.settings.OrderedAvatarSelection
import java.util.Locale

/** 挂在设置项下方的悬浮下拉多选框。独立窗口允许搜索输入，也不必启动 Activity。 */
class AvatarMultiSelectDropdown(private val context: Context) {
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    fun show(anchor: View, title: String, options: List<PartyAvatar>, selected: List<String>,
             defaults: List<String>, save: (List<String>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        dismiss()
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_avatar_multiselect, null)
        root = view
        val draft = OrderedAvatarSelection(selected)
        val list = view.findViewById<RecyclerView>(R.id.avatar_select_list)
        val chips = view.findViewById<LinearLayout>(R.id.avatar_select_chips)
        val search = view.findViewById<EditText>(R.id.avatar_select_search)
        var query = ""
        var filtered = options
        lateinit var render: () -> Unit
        val adapter = AvatarAdapter(draft, toggle = { draft.toggle(it); render() }, move = { name, offset ->
            draft.move(name, offset); render()
        })
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        render = {
            val queryText = query.trim().lowercase(Locale.ROOT)
            filtered = options.filter { avatar ->
                queryText.isEmpty() || (listOf(avatar.name, avatar.nameEn) + avatar.aliases)
                    .any { it.lowercase(Locale.ROOT).contains(queryText) }
            }
            val chosen = draft.values
            val priority = chosen.withIndex().associate { it.value to it.index }
            // 已选置顶并按优先级排列，搜索后仍保留完整的选择草稿。
            val sorted = filtered.sortedBy { priority[it.name] ?: Int.MAX_VALUE }
            adapter.submit(sorted)
            list.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
            view.findViewById<View>(R.id.avatar_select_empty).visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.avatar_select_count).text = "已选 ${chosen.size} 人 · 匹配 ${filtered.size} 人"
            chips.removeAllViews()
            if (chosen.isEmpty()) {
                chips.addView(TextView(context).apply {
                    text = "未选择角色，此项关闭"
                    setTextColor(ContextCompat.getColor(context, R.color.overlay_text_muted))
                    textSize = 12f
                })
            } else chosen.forEachIndexed { index, name ->
                chips.addView(TextView(context).apply {
                    text = "${index + 1}  $name  ×"
                    contentDescription = "优先级 ${index + 1}，$name，点击移除"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(10), 0, dp(10), 0)
                    setTextColor(ContextCompat.getColor(context, R.color.overlay_gold))
                    setBackgroundResource(R.drawable.bg_overlay_row)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(6) }
                    setOnClickListener { draft.toggle(name); render() }
                })
            }
        }
        view.findViewById<TextView>(R.id.avatar_select_title).text = title
        view.findViewById<View>(R.id.avatar_select_clear).setOnClickListener { draft.clear(); render() }
        view.findViewById<View>(R.id.avatar_select_defaults).setOnClickListener { draft.replace(defaults); render() }
        view.findViewById<View>(R.id.avatar_select_visible).setOnClickListener { draft.addAll(filtered.map { it.name }); render() }
        view.findViewById<View>(R.id.avatar_select_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.avatar_select_save).setOnClickListener { save(draft.values); dismiss() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); render() }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(search.windowToken, 0)
                view.requestFocus()
                true
            } else false
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dismiss(); true } else false
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else false
        }
        val metrics = context.resources.displayMetrics
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windows.currentWindowMetrics.bounds
            else Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val width = maxOf(anchor.width, dp(360)).coerceAtMost(bounds.width() - dp(24))
        val height = dp(440).coerceAtMost(bounds.height() - dp(48))
        val below = location[1] + anchor.height + dp(6)
        val y = (if (below + height <= bounds.height() - dp(12)) below else location[1] - height - dp(6))
            .coerceIn(dp(12), (bounds.height() - height - dp(12)).coerceAtLeast(dp(12)))
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = location[0].coerceIn(dp(12), (bounds.width() - width - dp(12)).coerceAtLeast(dp(12)))
            this.y = y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        render()
        windows.addView(view, params)
        view.requestFocus()
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        windows.removeView(view)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private class AvatarAdapter(private val draft: OrderedAvatarSelection, private val toggle: (String) -> Unit,
                                private val move: (String, Int) -> Unit) : RecyclerView.Adapter<AvatarAdapter.Holder>() {
        private var options = emptyList<PartyAvatar>()
        fun submit(values: List<PartyAvatar>) { options = values; notifyDataSetChanged() }
        override fun getItemCount(): Int = options.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_avatar_multiselect, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val avatar = options[position]
            val index = draft.values.indexOf(avatar.name)
            holder.check.isChecked = index >= 0
            holder.name.text = if (index >= 0) "${index + 1}. ${avatar.name}" else avatar.name
            holder.detail.text = "${avatar.nameEn} · 战技 ${avatar.skillCd} 秒" +
                if (avatar.skillHoldCd > 0) " / 长按 ${avatar.skillHoldCd} 秒" else ""
            holder.itemView.setOnClickListener { toggle(avatar.name) }
            holder.itemView.contentDescription = "${avatar.name}，${if (index >= 0) "已选，优先级 ${index + 1}" else "未选"}"
            holder.up.visibility = if (index >= 0) View.VISIBLE else View.GONE
            holder.down.visibility = if (index >= 0) View.VISIBLE else View.GONE
            holder.up.isEnabled = index > 0
            holder.down.isEnabled = index >= 0 && index < draft.values.lastIndex
            holder.up.alpha = if (holder.up.isEnabled) 1f else 0.3f
            holder.down.alpha = if (holder.down.isEnabled) 1f else 0.3f
            holder.up.setOnClickListener { move(avatar.name, -1) }
            holder.down.setOnClickListener { move(avatar.name, 1) }
        }
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: CheckBox = view.findViewById(R.id.avatar_option_check)
            val name: TextView = view.findViewById(R.id.avatar_option_name)
            val detail: TextView = view.findViewById(R.id.avatar_option_detail)
            val up: View = view.findViewById(R.id.avatar_option_up)
            val down: View = view.findViewById(R.id.avatar_option_down)
        }
    }
}

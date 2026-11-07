package com.bettergi.pocket.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.bettergi.pocket.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/** 悬浮窗统一主题及紧凑控件；控件高度均为下限，以适应字体缩放。 */
object OverlayUi {
    fun context(base: Context): Context {
        val config = Configuration(base.resources.configuration).apply {
            fontScale = fontScale.coerceAtMost(1.3f)
        }
        return ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_BetterGIPocket_Overlay)
    }
    fun text(c: Context, value: String = "", size: Float = 13f, color: Int = R.color.pocket_text,
             bold: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(ContextCompat.getColor(c, color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }
    fun icon(c: Context, resource: Int, color: Int = R.color.pocket_text_secondary) = ImageView(c).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(ContextCompat.getColor(c, color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun iconButton(c: Context, resource: Int, label: String, click: () -> Unit) = ImageButton(c).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(ContextCompat.getColor(c, R.color.pocket_text_secondary))
        contentDescription = label; TooltipCompat.setTooltipText(this, label)
        setPadding(dp(6), dp(6), dp(6), dp(6)); background = ripple(c)
        minimumWidth = resources.getDimensionPixelSize(R.dimen.pocket_icon_button); minimumHeight = minimumWidth
        layoutParams = LinearLayout.LayoutParams(minimumWidth, minimumHeight)
        setOnClickListener { click() }
    }
    fun button(c: Context, label: String, primary: Boolean = false, click: () -> Unit) = text(c, label, 12f,
        if (primary) R.color.pocket_on_primary else R.color.pocket_primary, true).apply {
        gravity = Gravity.CENTER; minimumHeight = dp(if (primary) 40 else 28)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),
            ContextCompat.getDrawable(c, if (primary) R.drawable.bg_pocket_primary else R.drawable.bg_pocket_chip), null)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    fun segments(c: Context, labels: List<String>, selected: Int, ids: List<Int>? = null, changed: (Int) -> Unit): MaterialButtonToggleGroup {
        val group = MaterialButtonToggleGroup(c).apply { isSingleSelection = true; isSelectionRequired = true }
        labels.forEachIndexed { index, label ->
            group.addView(MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = ids?.get(index) ?: View.generateViewId(); text = label; textSize = 12f; minWidth = 0; minimumWidth = 0
                minHeight = resources.getDimensionPixelSize(R.dimen.pocket_control); minimumHeight = minHeight; insetTop = 0; insetBottom = 0
                setPadding(dp(10), 0, dp(10), 0); cornerRadius = dp(8)
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ContextCompat.getColor(c, R.color.pocket_primary), ContextCompat.getColor(c, R.color.pocket_text_secondary))))
                strokeColor = ColorStateList.valueOf(ContextCompat.getColor(c, R.color.pocket_outline))
                backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ContextCompat.getColor(c, R.color.pocket_primary_container), android.graphics.Color.TRANSPARENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        group.check(group.getChildAt(selected).id)
        group.addOnButtonCheckedListener { _, id, checked -> if (checked) changed(group.indexOfChild(group.findViewById(id))) }
        return group
    }
    fun ripple(c: Context) = RippleDrawable(ColorStateList.valueOf(0x22C9A36A), null,
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun divider(c: Context, start: Int = 0) = View(c).apply {
        setBackgroundResource(R.color.pocket_divider)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(start) }
    }
    fun group(c: Context, vararg rows: View) = column(c).apply {
        setBackgroundResource(R.drawable.bg_pocket_group)
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(divider(c).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = c.resources.getDimensionPixelSize(R.dimen.pocket_text_start) })
            addView(row)
        }
    }
    fun scroll(c: Context, child: View) = ScrollView(c).apply { isFillViewport = false; addView(child); clipToPadding = false }
    fun leadingIcon(view: TextView, resource: Int, color: Int = R.color.pocket_primary, size: Int = 16) {
        val drawable = ContextCompat.getDrawable(view.context, resource)!!.mutate()
        androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, ContextCompat.getColor(view.context, color))
        drawable.setBounds(0, 0, view.dp(size), view.dp(size))
        view.setCompoundDrawablesRelative(drawable, null, null, null); view.compoundDrawablePadding = view.dp(4)
    }
}

fun View.dp(value: Int): Int = when (value) {
    4 -> resources.getDimensionPixelSize(R.dimen.pocket_space_xs)
    8 -> resources.getDimensionPixelSize(R.dimen.pocket_space_s)
    12 -> resources.getDimensionPixelSize(R.dimen.pocket_space_m)
    16 -> resources.getDimensionPixelSize(R.dimen.pocket_space_l)
    else -> (value * resources.displayMetrics.density + .5f).toInt()
}

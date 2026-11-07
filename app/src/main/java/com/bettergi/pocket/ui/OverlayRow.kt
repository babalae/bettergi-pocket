package com.bettergi.pocket.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 统一设置行的标题、摘要、边距和点击区域，摘要可随字体大小自然换行。 */
class OverlayRow(context: Context, title: String, icon: Int? = null, subtitle: String = "",
                 dense: Boolean = false) : LinearLayout(context) {
    val titleView = OverlayUi.text(context, title)
    val subtitleView = OverlayUi.text(context, subtitle, 11f, com.bettergi.pocket.R.color.pocket_text_secondary)
    val trailing = OverlayUi.row(context)
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(if (subtitle.isNotEmpty()) com.bettergi.pocket.R.dimen.pocket_row_two_line
            else if (dense) com.bettergi.pocket.R.dimen.pocket_row_dense else com.bettergi.pocket.R.dimen.pocket_row)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        icon?.let { addView(OverlayUi.icon(context, it), LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }) }
        addView(OverlayUi.column(context).apply {
            addView(titleView); addView(subtitleView)
        }, LayoutParams(0, -2, 1f))
        subtitleView.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        addView(trailing, LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }
    fun subtitle(value: String) { subtitleView.text = value; subtitleView.visibility = if (value.isEmpty()) GONE else VISIBLE }
}

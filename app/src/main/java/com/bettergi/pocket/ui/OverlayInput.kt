package com.bettergi.pocket.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 仅搜索或显式数字编辑时临时取焦点，浏览与拖动立即归还游戏。 */
class OverlayInput(private val root: View, private val focusable: (Boolean) -> Unit) {
    private val ime = root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var field: EditText? = null
    private var shown = false
    init {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (shown && !visible && field != null) root.post { if (!shown) finish() }
            shown = visible; insets
        }
        root.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) finish(); false }
    }
    fun bind(edit: EditText) {
        edit.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) begin(edit); false }
        edit.setOnClickListener { begin(edit) }
        edit.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            if (focused && field === edit) ime.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
        edit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_SEARCH) { finish(); true } else false
        }
    }
    fun begin(edit: EditText) {
        if (field !== edit) { field = edit; shown = false; focusable(true) }
        edit.requestFocus()
        edit.post { if (field === edit && root.isAttachedToWindow) ime.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
    }
    fun finish() {
        val editing = field ?: return
        field = null; shown = false
        ime.hideSoftInputFromWindow(root.windowToken, 0); editing.clearFocus(); root.requestFocus(); focusable(false)
    }
}

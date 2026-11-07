package com.bettergi.pocket.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.LinearLayout
import com.bettergi.pocket.R

/** 数值步进器。长按连续调整；点击数值由同窗数字编辑器处理，不另建悬浮窗。 */
class StepperView(context: Context, private val minimum: Long, private val maximum: Long,
                  private val step: Long, initial: Long, private val format: (Long) -> String,
                  private val changed: (Long) -> Unit, edit: (StepperView) -> Unit,
                  private val editorMinimum: Long = minimum) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    var value: Long = initial.coerceIn(editorMinimum, maximum)
        private set
    private val minus = OverlayUi.iconButton(context, R.drawable.ic_ui_remove, "减少") { adjust(-step) }
    private val label = OverlayUi.text(context, "", 12f, bold = true)
    private val plus = OverlayUi.iconButton(context, R.drawable.ic_ui_add, "增加") { adjust(step) }
    private var repeated = 0
    private var delta = 0L
    private val repeat = object : Runnable {
        override fun run() { repeated++; adjust(delta * if (repeated > 12) 5 else 1); handler.postDelayed(this, 90) }
    }
    init {
        orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_pocket_group); minimumHeight = dp(28)
        addView(minus, LayoutParams(dp(28), dp(28)))
        addView(label.apply { gravity = android.view.Gravity.CENTER; minWidth = dp(56); minimumHeight = dp(28)
            setOnClickListener { if (isEnabled) edit(this@StepperView) }; contentDescription = "点击输入数值" }, LayoutParams(-2, -2))
        addView(plus, LayoutParams(dp(28), dp(28)))
        listOf(minus to -step, plus to step).forEach { (button, increment) ->
            button.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { delta = increment; repeated = 0; handler.postDelayed(repeat, 400) }
                    MotionEvent.ACTION_UP -> { handler.removeCallbacks(repeat); if (repeated > 0) return@setOnTouchListener true }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(repeat)
                }; false
            }
        }
        render()
    }
    fun setValue(next: Long) { value = next.coerceIn(editorMinimum, maximum); render() }
    private fun adjust(increment: Long) { if (!isEnabled) return; setValue((value + increment).coerceAtLeast(minimum)); changed(value) }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); if (!enabled) handler.removeCallbacks(repeat); render() }
    private fun render() {
        label.text = format(value); label.isEnabled = isEnabled
        minus.isEnabled = isEnabled && value > minimum; plus.isEnabled = isEnabled && value < maximum
        minus.alpha = if (minus.isEnabled) 1f else .3f; plus.alpha = if (plus.isEnabled) 1f else .3f
        label.alpha = if (isEnabled) 1f else .38f
    }
    override fun onDetachedFromWindow() { handler.removeCallbacks(repeat); super.onDetachedFromWindow() }
}

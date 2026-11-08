package com.bettergi.pocket.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView

/** 仅 Debug 的真实输入回归界面，接收测试触控，避免向原神注入压力测试动作。 */
class GestureProbeActivity : Activity() {
    val activePointers = linkedSetOf<Int>()
    val errors = mutableListOf<String>()
    var tapDowns = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "输入回归测试（仅 Debug）"
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 35, 45))
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val ids = (0 until event.pointerCount).map { event.getPointerId(it) }.toSet()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = event.getPointerId(event.actionIndex)
                if (event.actionMasked == MotionEvent.ACTION_DOWN && activePointers.isNotEmpty()) errors += "旧触点未释放即 DOWN"
                if (!activePointers.add(id) || ids != activePointers) errors += "DOWN 触点集合不一致"
                if (event.getX(event.actionIndex) in 850f..950f) tapDowns++
            }
            MotionEvent.ACTION_MOVE -> if (ids != activePointers) errors += "MOVE 触点集合不一致"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (ids != activePointers) errors += "UP 触点集合不一致"
                activePointers.remove(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> activePointers.clear()
        }
        return true
    }
}

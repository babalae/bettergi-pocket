package com.bettergi.pocket.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.service.TriggerForegroundService

/** ADB 测试复用正式无障碍桥接，不依赖 shell input，也不加入 Release 包。 */
class DeviceAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        InputAccessibilityService.attach(context)
        val connected = InputAccessibilityService.isConnected()
        when (intent.action) {
            ACTION_STATUS -> {
                setResult(
                    if (connected) 0 else 1,
                    "connected=$connected;genshinForeground=${InputAccessibilityService.isGenshinInForeground()}",
                    null,
                )
                return
            }
            ACTION_START_READOUT, ACTION_STOP_PATHING, ACTION_CAPTURE, ACTION_START_ROUTE -> {
                val action = when (intent.action) {
                    ACTION_START_READOUT -> TriggerForegroundService.ACTION_DEBUG_READOUT
                    ACTION_CAPTURE -> TriggerForegroundService.ACTION_DEBUG_CAPTURE
                    ACTION_START_ROUTE -> TriggerForegroundService.ACTION_DEBUG_ROUTE
                    else -> TriggerForegroundService.ACTION_DEBUG_PATHING_STOP
                }
                val service = Intent(context, TriggerForegroundService::class.java).setAction(action)
                intent.getStringExtra("path")?.let { service.putExtra(TriggerForegroundService.EXTRA_ROUTE_PATH, it) }
                val accepted = try {
                    context.startService(service)
                    true
                } catch (e: Exception) {
                    setResult(1, "startService failed: ${e.javaClass.simpleName}: ${e.message}", null)
                    false
                }
                if (accepted) {
                    setResult(0, "accepted=$action;connected=$connected", null)
                }
                return
            }
        }
        if (intent.action != ACTION_TAP) {
            setResult(1, "unsupported action", null)
            return
        }
        val x = intent.getIntExtra("x", -1)
        val y = intent.getIntExtra("y", -1)
        val duration = intent.getIntExtra("duration", 50)
        val metrics = context.resources.displayMetrics
        if (x !in 0 until metrics.widthPixels || y !in 0 until metrics.heightPixels || duration !in 1..2000) {
            setResult(1, "invalid coordinates or duration", null)
            return
        }
        val accepted = connected && InputAccessibilityService.click(x, y, duration.toLong())
        // accepted 仅代表提交成功；需用操作前后截图确认目标界面变化。
        setResult(if (accepted) 0 else 1, "gestureAccepted=$accepted;x=$x;y=$y", null)
    }

    companion object {
        private const val ACTION_STATUS = "com.bettergi.pocket.debug.STATUS"
        private const val ACTION_TAP = "com.bettergi.pocket.debug.TAP"
        private const val ACTION_START_READOUT = "com.bettergi.pocket.debug.PATHING_READOUT"
        private const val ACTION_STOP_PATHING = "com.bettergi.pocket.debug.PATHING_STOP"
        private const val ACTION_CAPTURE = "com.bettergi.pocket.debug.CAPTURE"
        private const val ACTION_START_ROUTE = "com.bettergi.pocket.debug.PATHING_ROUTE"
    }
}

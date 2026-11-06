package com.bettergi.pocket.input

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.bettergi.pocket.genshin.GenshinPackages

class InputAccessibilityService : AccessibilityService() {
    private var composer: GestureComposer? = null

    override fun onServiceConnected() {
        composer = GestureComposer(this)
        instance = this
        Log.i(TAG, "accessibility service connected")
        notifyStateChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in TRANSIENT_PACKAGES) return
        lastAppPackage = pkg
    }

    override fun onInterrupt() {
        composer?.releaseAll()
    }

    private fun clearInstance() {
        if (instance === this) {
            composer?.releaseAll()
            composer = null
            instance = null
            lastAppPackage = null
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        private const val TAG = "BetterGI.Input"
        const val DEFAULT_PROMPT = "请开启无障碍权限，才能模拟点击"
        const val CRASHED_PROMPT = "无障碍服务已异常，请先关闭再重新打开"
        const val ACTION_STATE_CHANGED = "com.bettergi.pocket.action.ACCESSIBILITY_CHANGED"
        private const val AUTHORITY_SUFFIX = ".a11y"
        private const val METHOD_STATUS = "status"
        private const val METHOD_CLICK = "click"
        private const val METHOD_PAD_HOLD = "pad.hold"
        private const val METHOD_PAD_RELEASE = "pad.release"
        private const val METHOD_BUTTON_DOWN = "button.down"
        private const val METHOD_BUTTON_UP = "button.up"
        private const val METHOD_TAP = "tap"
        private const val METHOD_SWIPE = "swipe"
        private const val METHOD_PINCH = "pinch"
        private const val KEY_START_SPAN = "start_span"
        private const val KEY_END_SPAN = "end_span"
        private const val METHOD_DRAG_BEGIN = "drag.begin"
        private const val METHOD_DRAG_STEP = "drag.step"
        private const val METHOD_DRAG_END = "drag.end"
        private const val METHOD_RELEASE_ALL = "releaseAll"
        private const val METHOD_RELEASE_ALL_AND_WAIT = "releaseAllAndWait"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_LAST_PACKAGE = "last_package"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_X2 = "x2"
        private const val KEY_Y2 = "y2"
        private const val KEY_ID = "id"
        private const val KEY_DURATION = "duration"
        private const val KEY_OK = "ok"
        private const val BIND_GRACE_MS = 2000L
        private const val BIND_POLL_MS = 250L

        @Volatile
        private var instance: InputAccessibilityService? = null

        @Volatile
        private var lastAppPackage: String? = null

        @Volatile
        private var usageCacheAt = 0L

        @Volatile
        private var usageCachePackage: String? = null

        private const val USAGE_CACHE_MS = 1000L
        private const val USAGE_LOOKBACK_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var appContext: Context? = null

        private val recoverHandler = Handler(Looper.getMainLooper())
        private var recoverCheck: Runnable? = null

        private val TRANSIENT_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        fun attach(context: Context) {
            appContext = context.applicationContext
        }

        fun isConnected(): Boolean {
            if (instance != null) return true
            return remoteStatus()?.getBoolean(KEY_CONNECTED, false) == true
        }

        /** `true`/`false` 表示原神是否在前台；无障碍未连接或尚未观察到窗口时为 `null`。 */
        fun isGenshinInForeground(): Boolean? {
            if (!isConnected()) return null
            usageForegroundPackage()?.let { pkg ->
                val playing = GenshinPackages.isGenshinPackage(pkg)
                if (!playing) Log.i("BetterGI.Pathing", "foreground package=$pkg")
                return playing
            }
            // 游戏窗口经常没有无障碍事件。没有使用情况记录时不要用过期包名把原神判成后台。
            return null
        }

        /**
         * 通过 UsageStats 取最近一个回到前台的普通应用。无障碍事件对游戏常常不触发，
         * 悬浮窗/系统弹窗又会把 lastAppPackage 冲掉，这里作为主判据。
         * 游戏窗口常常没有可读取的无障碍节点，不能直接从活跃窗口可靠取包名。
         * 未授予“使用情况访问”时返回 null，不回退到可能已过期的无障碍事件。
         */
        private fun usageForegroundPackage(): String? {
            val now = SystemClock.elapsedRealtime()
            if (now - usageCacheAt < USAGE_CACHE_MS) return usageCachePackage
            val context = instance ?: appContext ?: return null
            val manager = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
                ?: return null
            val end = System.currentTimeMillis()
            val events = manager.queryEvents(end - USAGE_LOOKBACK_MS, end) ?: return null
            val event = android.app.usage.UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                val pkg = event.packageName ?: continue
                if (pkg == context.packageName || pkg in TRANSIENT_PACKAGES) continue
                last = pkg
            }
            usageCacheAt = now
            usageCachePackage = last
            return last
        }

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val component = ComponentName(context, InputAccessibilityService::class.java)
            return AccessibilityServiceHealth.isListed(
                enabled,
                component.flattenToString(),
                component.flattenToShortString(),
            )
        }

        fun health(context: Context): AccessibilityServiceHealth.State {
            return AccessibilityServiceHealth.state(isConnected(), isEnabledInSettings(context))
        }

        fun isEnabled(context: Context): Boolean = isConnected()

        fun openSettings(context: Context) {
            val component = ComponentName(context, InputAccessibilityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    context.startActivity(
                        Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                        },
                    )
                    return
                } catch (_: Exception) {
                }
            }
            val highlight = component.flattenToString()
            val args = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, highlight) }
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_FRAGMENT_ARG_KEY, highlight)
                    putExtra(EXTRA_SHOW_FRAGMENT_ARGS, args)
                },
            )
        }

        fun ensureEnabled(context: Context, message: String = DEFAULT_PROMPT): Boolean {
            if (isConnected()) return true
            val prompt = if (isEnabledInSettings(context)) CRASHED_PROMPT else message
            Toast.makeText(context, prompt, Toast.LENGTH_LONG).show()
            openSettings(context)
            return false
        }

        fun promptIfDisconnected(context: Context, graceMs: Long = BIND_GRACE_MS) {
            val app = context.applicationContext
            cancelRecoverCheck()
            val startedAt = SystemClock.uptimeMillis()
            val runnable = object : Runnable {
                override fun run() {
                    if (isConnected()) return
                    if (SystemClock.uptimeMillis() - startedAt < graceMs) {
                        recoverHandler.postDelayed(this, BIND_POLL_MS)
                        return
                    }
                    if (isEnabledInSettings(app)) {
                        ensureEnabled(app)
                    }
                }
            }
            recoverCheck = runnable
            recoverHandler.post(runnable)
        }

        fun cancelRecoverCheck() {
            recoverCheck?.let(recoverHandler::removeCallbacks)
            recoverCheck = null
        }

        fun click(x: Int, y: Int, durationMs: Long = 50L): Boolean {
            if (instance != null) return clickLocal(x, y, durationMs)
            val extras = Bundle().apply {
                putInt(KEY_X, x)
                putInt(KEY_Y, y)
                putLong(KEY_DURATION, durationMs)
            }
            return remoteCall(METHOD_CLICK, extras)?.getBoolean(KEY_OK, false) == true
        }

        fun handleBridgeCall(method: String, extras: Bundle?): Bundle {
            return when (method) {
                METHOD_STATUS -> Bundle().apply {
                    putBoolean(KEY_CONNECTED, instance != null)
                    putString(KEY_LAST_PACKAGE, lastAppPackage)
                }
                METHOD_CLICK -> okBundle(
                    clickLocal(
                        extras.int(KEY_X),
                        extras.int(KEY_Y),
                        extras.long(KEY_DURATION, 50L),
                    ),
                )
                METHOD_PAD_HOLD -> okBundle(composer()?.padHold(extras.int(KEY_X), extras.int(KEY_Y)) == true)
                METHOD_PAD_RELEASE -> okBundle(composer()?.padRelease() == true)
                METHOD_BUTTON_DOWN -> okBundle(
                    composer()?.buttonDown(extras.string(KEY_ID), extras.int(KEY_X), extras.int(KEY_Y)) == true,
                )
                METHOD_BUTTON_UP -> okBundle(composer()?.buttonUp(extras.string(KEY_ID)) == true)
                METHOD_TAP -> okBundle(
                    composer()?.tap(extras.int(KEY_X), extras.int(KEY_Y), extras.long(KEY_DURATION, 50L)) == true,
                )
                METHOD_SWIPE -> okBundle(
                    composer()?.swipe(
                        extras.int(KEY_X),
                        extras.int(KEY_Y),
                        extras.int(KEY_X2),
                        extras.int(KEY_Y2),
                        extras.long(KEY_DURATION, 80L),
                    ) == true,
                )
                METHOD_PINCH -> okBundle(
                    composer()?.pinch(
                        extras.int(KEY_X), extras.int(KEY_Y),
                        extras.int(KEY_START_SPAN), extras.int(KEY_END_SPAN), extras.long(KEY_DURATION, 400L),
                    ) == true,
                )
                METHOD_DRAG_BEGIN -> okBundle(composer()?.dragBegin(extras.int(KEY_X), extras.int(KEY_Y)) == true)
                METHOD_DRAG_STEP -> okBundle(
                    composer()?.dragStep(extras.int(KEY_X), extras.int(KEY_Y), extras.long(KEY_DURATION, 20L)) == true,
                )
                METHOD_DRAG_END -> okBundle(composer()?.dragEnd() == true)
                METHOD_RELEASE_ALL -> okBundle(composer()?.releaseAll() == true)
                METHOD_RELEASE_ALL_AND_WAIT -> okBundle(
                    composer()?.releaseAllAndWait(extras.long(KEY_DURATION, 3000L)) == true,
                )
                else -> Bundle()
            }
        }

        fun padHold(x: Int, y: Int): Boolean = call(METHOD_PAD_HOLD, pointBundle(x, y)) { it.padHold(x, y) }

        fun padRelease(): Boolean = call(METHOD_PAD_RELEASE) { it.padRelease() }

        fun buttonDown(id: String, x: Int, y: Int): Boolean {
            return call(METHOD_BUTTON_DOWN, pointBundle(x, y).apply { putString(KEY_ID, id) }) {
                it.buttonDown(id, x, y)
            }
        }

        fun buttonUp(id: String): Boolean {
            return call(METHOD_BUTTON_UP, Bundle().apply { putString(KEY_ID, id) }) { it.buttonUp(id) }
        }

        fun tap(x: Int, y: Int, durationMs: Long): Boolean {
            return call(METHOD_TAP, pointBundle(x, y).apply { putLong(KEY_DURATION, durationMs) }) {
                it.tap(x, y, durationMs)
            }
        }

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            val extras = pointBundle(x1, y1).apply {
                putInt(KEY_X2, x2)
                putInt(KEY_Y2, y2)
                putLong(KEY_DURATION, durationMs)
            }
            return call(METHOD_SWIPE, extras) { it.swipe(x1, y1, x2, y2, durationMs) }
        }

        fun dragBegin(x: Int, y: Int): Boolean = call(METHOD_DRAG_BEGIN, pointBundle(x, y)) { it.dragBegin(x, y) }

        fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int, durationMs: Long): Boolean {
            val extras = pointBundle(centerX, centerY).apply {
                putInt(KEY_START_SPAN, startSpan)
                putInt(KEY_END_SPAN, endSpan)
                putLong(KEY_DURATION, durationMs)
            }
            return call(METHOD_PINCH, extras) { it.pinch(centerX, centerY, startSpan, endSpan, durationMs) }
        }

        fun dragStep(x: Int, y: Int, durationMs: Long): Boolean {
            return call(METHOD_DRAG_STEP, pointBundle(x, y).apply { putLong(KEY_DURATION, durationMs) }) {
                it.dragStep(x, y, durationMs)
            }
        }

        fun dragEnd(): Boolean = call(METHOD_DRAG_END) { it.dragEnd() }

        fun releaseAllTouches(): Boolean = call(METHOD_RELEASE_ALL) { it.releaseAll() }

        /** ContentProvider 的 Binder 线程等待，不能把等待转发到无障碍主线程。 */
        fun releaseAllTouchesAndWait(timeoutMs: Long): Boolean = call(
            METHOD_RELEASE_ALL_AND_WAIT, Bundle().apply { putLong(KEY_DURATION, timeoutMs) },
        ) { it.releaseAllAndWait(timeoutMs) }

        private fun clickLocal(x: Int, y: Int, durationMs: Long): Boolean {
            return composer()?.tap(x, y, durationMs) == true
        }

        private fun composer(): GestureComposer? = instance?.composer

        private fun call(
            method: String,
            extras: Bundle? = null,
            local: (GestureComposer) -> Boolean,
        ): Boolean {
            val localComposer = composer()
            if (localComposer != null) return local(localComposer)
            return remoteCall(method, extras)?.getBoolean(KEY_OK, false) == true
        }

        private fun okBundle(ok: Boolean): Bundle = Bundle().apply { putBoolean(KEY_OK, ok) }

        private fun pointBundle(x: Int, y: Int): Bundle = Bundle().apply {
            putInt(KEY_X, x)
            putInt(KEY_Y, y)
        }

        private fun Bundle?.int(key: String): Int = this?.getInt(key) ?: 0

        private fun Bundle?.long(key: String, default: Long): Long = this?.getLong(key, default) ?: default

        private fun Bundle?.string(key: String): String = this?.getString(key).orEmpty()

        private fun remoteStatus(): Bundle? = remoteCall(METHOD_STATUS)

        private fun remoteCall(method: String, extras: Bundle? = null): Bundle? {
            val ctx = appContext ?: return null
            return try {
                ctx.contentResolver.call(bridgeUri(ctx), method, null, extras)
            } catch (_: Exception) {
                null
            }
        }

        private fun bridgeUri(context: Context): Uri {
            return Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX")
        }

        private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }
}

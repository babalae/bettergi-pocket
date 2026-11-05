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

    override fun onInterrupt() = Unit

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
        private const val METHOD_DRAG_BEGIN = "drag.begin"
        private const val METHOD_DRAG_STEP = "drag.step"
        private const val METHOD_DRAG_END = "drag.end"
        private const val METHOD_RELEASE_ALL = "releaseAll"
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
            val (connected, pkg) = currentStatus()
            if (!connected) return null
            val name = pkg ?: return null
            return GenshinPackages.isGenshinPackage(name)
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
                METHOD_DRAG_BEGIN -> okBundle(composer()?.dragBegin(extras.int(KEY_X), extras.int(KEY_Y)) == true)
                METHOD_DRAG_STEP -> okBundle(
                    composer()?.dragStep(extras.int(KEY_X), extras.int(KEY_Y), extras.long(KEY_DURATION, 20L)) == true,
                )
                METHOD_DRAG_END -> okBundle(composer()?.dragEnd() == true)
                METHOD_RELEASE_ALL -> okBundle(composer()?.releaseAll() == true)
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

        fun dragStep(x: Int, y: Int, durationMs: Long): Boolean {
            return call(METHOD_DRAG_STEP, pointBundle(x, y).apply { putLong(KEY_DURATION, durationMs) }) {
                it.dragStep(x, y, durationMs)
            }
        }

        fun dragEnd(): Boolean = call(METHOD_DRAG_END) { it.dragEnd() }

        fun releaseAllTouches(): Boolean = call(METHOD_RELEASE_ALL) { it.releaseAll() }

        private fun currentStatus(): Pair<Boolean, String?> {
            if (instance != null) return true to lastAppPackage
            val status = remoteStatus() ?: return false to null
            return status.getBoolean(KEY_CONNECTED, false) to status.getString(KEY_LAST_PACKAGE)
        }

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

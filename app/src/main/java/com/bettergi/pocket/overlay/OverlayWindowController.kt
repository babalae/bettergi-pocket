package com.bettergi.pocket.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.ProgressBar
import androidx.appcompat.widget.TooltipCompat
import com.bettergi.pocket.ui.OverlayUi
import com.bettergi.pocket.ui.OverlayGeometry
import com.bettergi.pocket.pathing.PathingProgress
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.BuildConfig
import com.bettergi.pocket.analytics.BaiduAnalytics
import com.bettergi.pocket.bilibili.BilibiliSpaceOpener
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipEvents
import com.bettergi.pocket.genshin.GenshinLaunchResult
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.input.AccessibilityServiceHealth
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayWindowController(
    private val context: Context,
    private val settingsRepository: TriggerSettingsRepository,
    private val genshinLauncher: GenshinLauncher = GenshinLauncher(context),
    private val onExit: () -> Unit = {},
) : AutoSkipEvents {
    private var themedContext = OverlayUi.context(context)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastScreenW = 0
    private var lastScreenH = 0
    private var watchingScreen = false
    private var overlayDensity = 0
    private var overlayFontScale = 1f

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            relocateOverlays(force = false)
        }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val fontScale = newConfig.fontScale.coerceAtMost(1.3f)
            if (rootView != null && (overlayDensity != newConfig.densityDpi || overlayFontScale != fontScale)) {
                val wasExpanded = expanded
                params?.let { persistPosition(it) }
                hide(); show()
                if (wasExpanded) setExpanded(true)
                return
            }
            relocateOverlays(force = true)
        }

        override fun onLowMemory() = Unit
    }

    private var rootView: View? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var chatBadge: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null

    private var updatingUi = false
    private var expanded = false
    private var transforming = false
    private var pendingCollapseOnOutside = false
    private var switchEnabled: SwitchCompat? = null
    private var switchAutoSkip: SwitchCompat? = null
    private var quickSkipChip: TextView? = null
    private var switchAutoPick: SwitchCompat? = null
    private var autoLaunchChip: TextView? = null
    private var launchSubtitle: TextView? = null
    private var logToggleButton: ImageButton? = null
    private var rowAutoSkip: View? = null
    private var rowAutoPick: View? = null
    private var rowLaunch: View? = null
    private var pathingRow: View? = null
    private var pathingStatus: TextView? = null
    var onPathingStart: (() -> Boolean)? = null
    var onPathingOpen: (() -> Unit)? = null
    var onPathingStop: (() -> Unit)? = null
    private var pathingStart: ImageButton? = null
    private var routeRunning = false
    private var readoutRunning = false
    private var progress: PathingProgress? = null
    private var pathingSummary = "未选择路线"
    private var hasRouteSelection = false
    private var latestPathingStatus = ""
    private var logExpanded = true
    private var captureInterrupted = false
    enum class LogLevel { Info, Warning, Error }
    private data class LogLine(val time: String, val message: String, val level: LogLevel)

    private var logHandleView: View? = null
    private var logBodyView: View? = null
    private var logTitle: TextView? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var logHandleParams: WindowManager.LayoutParams? = null
    private var logBodyParams: WindowManager.LayoutParams? = null
    private val logLines = ArrayDeque<LogLine>(MAX_LOG_LINES)
    private var logWindowVisible = false
    private var passthroughDepth = 0
    private var talkingUntilMs: Long = 0L
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val clearTalkingRunnable = Runnable { refreshStatus() }
    private val statusTick = object : Runnable {
        override fun run() {
            if (!routeRunning && !readoutRunning) return
            refreshLogTitle(); mainHandler.postDelayed(this, 1000)
        }
    }

    private val idleFadeRunnable = Runnable { fadeBubble(IDLE_ALPHA) }
    private var a11yWarningReady = false
    private val refreshA11ySoon = Runnable { refreshStatus() }
    private val refreshA11yLater = Runnable {
        a11yWarningReady = true
        refreshStatus()
    }
    private var a11yReceiverRegistered = false
    private val a11yReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    private val settingsListener: (TriggerSettings) -> Unit = { settings ->
        updatingUi = true
        try {
            switchEnabled?.isChecked = settings.screenShareEnabled
            switchAutoSkip?.isChecked = settings.autoSkipEnabled
            quickSkipChip?.isSelected = settings.quickSkipDialogueEnabled
            switchAutoPick?.isChecked = settings.autoPickEnabled
            autoLaunchChip?.isSelected = settings.autoLaunchGenshinEnabled
            applyFeatureEnabled(settings)
            refreshLaunchHint()
            refreshStatus()
        } finally {
            updatingUi = false
        }
    }

    fun show() {
        if (rootView != null || !Settings.canDrawOverlays(context)) return
        themedContext = OverlayUi.context(context)
        overlayDensity = context.resources.configuration.densityDpi
        overlayFontScale = context.resources.configuration.fontScale.coerceAtMost(1.3f)
        val root = LayoutInflater.from(themedContext).inflate(R.layout.overlay_window, null)
        bubbleView = root.findViewById(R.id.overlay_bubble)
        panelView = root.findViewById(R.id.overlay_panel)
        statusDot = root.findViewById(R.id.overlay_status_dot)
        statusText = root.findViewById<TextView>(R.id.overlay_status_text).also { text ->
            text.setOnClickListener {
                if (InputAccessibilityService.health(themedContext) != AccessibilityServiceHealth.State.CONNECTED)
                    InputAccessibilityService.ensureEnabled(themedContext)
                else if (routeRunning || readoutRunning) expandStatusBar()
            }
        }
        chatBadge = root.findViewById(R.id.overlay_chat_badge)
        switchEnabled = root.findViewById(R.id.overlay_switch_enabled)
        switchAutoSkip = root.findViewById(R.id.overlay_switch_auto_skip)
        switchAutoPick = root.findViewById(R.id.overlay_switch_auto_pick)
        quickSkipChip = root.findViewById(R.id.overlay_quick_skip_chip)
        autoLaunchChip = root.findViewById(R.id.overlay_auto_launch_chip)
        launchSubtitle = root.findViewById(R.id.overlay_launch_subtitle)
        logToggleButton = root.findViewById(R.id.overlay_log_toggle)
        rowAutoSkip = root.findViewById(R.id.overlay_row_auto_skip)
        rowAutoPick = root.findViewById<View>(R.id.overlay_row_auto_pick).also {
            it.visibility = if (AutoPickFeature.AVAILABLE) View.VISIBLE else View.GONE
            if (!AutoPickFeature.AVAILABLE) settingsRepository.setAutoPickEnabled(false)
        }
        rowLaunch = root.findViewById(R.id.overlay_row_launch)
        pathingRow = root.findViewById(R.id.overlay_row_pathing)
        pathingStatus = root.findViewById<TextView>(R.id.overlay_pathing_status).apply { text = pathingSummary }
        pathingStart = root.findViewById(R.id.overlay_pathing_start)
        if (!BuildConfig.PATHING_ENABLED || Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            root.findViewById<View>(R.id.overlay_group_pathing).visibility = View.GONE
        val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = prefs.getInt(KEY_X, 0); y = prefs.getInt(KEY_Y, dp(120))
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setupDragAndClick(bubbleView!!, lp) { setExpanded(true) }
        setupDrag(root.findViewById(R.id.overlay_header), lp, snapOnRelease = false)
        root.findViewById<View>(R.id.overlay_collapse).setOnClickListener { setExpanded(false) }
        root.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { collapseOnOutsideTouch(); true } else false }
        logToggleButton?.setOnClickListener { setLogWindowVisible(!logWindowVisible) }
        pathingRow?.setOnClickListener {
            setExpanded(false)
            if (routeRunning) expandStatusBar() else onPathingOpen?.invoke()
        }
        pathingStart?.setOnClickListener {
            if (routeRunning || readoutRunning) onPathingStop?.invoke()
            else if (onPathingStart?.invoke() == true) setExpanded(false)
        }
        root.findViewById<View>(R.id.overlay_launch).setOnClickListener { launchGenshinFromButton() }
        root.findViewById<View>(R.id.overlay_bilibili).setOnClickListener { openBilibiliSpace() }
        root.findViewById<View>(R.id.overlay_exit).setOnClickListener { exitAssistant() }
        root.findViewById<TextView>(R.id.overlay_warning_action).setOnClickListener {
            if (InputAccessibilityService.health(themedContext) != AccessibilityServiceHealth.State.CONNECTED)
                InputAccessibilityService.ensureEnabled(themedContext)
            else { captureInterrupted = false; settingsRepository.setScreenShareEnabled(true) }
        }
        switchEnabled?.contentDescription = "共享屏幕"
        switchEnabled?.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) { captureInterrupted = false; settingsRepository.setScreenShareEnabled(checked)
                BaiduAnalytics.event("screen_share_toggle", checked.toString()) }
        }
        switchAutoSkip?.contentDescription = "自动对话"
        switchAutoSkip?.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                if (!settingsRepository.get().screenShareEnabled) { hintNeedShare(); updatingUi = true
                    switchAutoSkip?.isChecked = settingsRepository.get().autoSkipEnabled; updatingUi = false }
                else { settingsRepository.setAutoSkipEnabled(checked); BaiduAnalytics.event("auto_skip_toggle", checked.toString())
                    if (checked) InputAccessibilityService.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟点击对话选项") }
            }
        }
        rowAutoSkip?.setOnClickListener { if (!settingsRepository.get().screenShareEnabled) hintNeedShare() else switchAutoSkip?.toggle() }
        quickSkipChip?.setOnClickListener {
            val config = settingsRepository.get()
            if (!config.screenShareEnabled) hintNeedShare() else settingsRepository.setQuickSkipDialogueEnabled(!config.quickSkipDialogueEnabled)
        }
        autoLaunchChip?.setOnClickListener { settingsRepository.setAutoLaunchGenshinEnabled(!settingsRepository.get().autoLaunchGenshinEnabled) }
        switchAutoPick?.setOnCheckedChangeListener { _, checked -> if (!updatingUi) {
            settingsRepository.setAutoPickEnabled(checked); BaiduAnalytics.event("auto_pick_toggle", checked.toString())
            if (checked) InputAccessibilityService.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟点击拾取")
        } }
        OverlayUi.leadingIcon(quickSkipChip!!, R.drawable.ic_ui_fast_forward, size = 14)
        OverlayUi.leadingIcon(autoLaunchChip!!, R.drawable.ic_ui_autorenew, size = 14)
        TooltipCompat.setTooltipText(quickSkipChip!!, "快速跳过：对话中点击屏幕下方继续")
        TooltipCompat.setTooltipText(autoLaunchChip!!, "无原神时自动启动：启动助手时若未检测到原神则打开一次")
        listOf(R.id.overlay_collapse, R.id.overlay_log_toggle, R.id.overlay_exit, R.id.overlay_bilibili, R.id.overlay_pathing_start).forEach {
            root.findViewById<View>(it).let { button -> TooltipCompat.setTooltipText(button, button.contentDescription) }
        }
        rootView = root; params = lp
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (expanded && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) clampToScreen(lp)
        }
        windowManager.addView(root, lp)
        val mode = prefs.getInt(KEY_STATUS_MODE, if (prefs.getBoolean(KEY_LOG_VISIBLE, false)) 2 else 0)
        logExpanded = mode == 2; setLogWindowVisible(mode != 0, persist = false)
        settingsRepository.addListener(settingsListener); startScreenWatch(); a11yWarningReady = false; registerA11yReceiver()
        mainHandler.postDelayed(refreshA11ySoon, 400); mainHandler.postDelayed(refreshA11yLater, 2000)
        root.post { resizePanel(); rememberScreen(); clampToScreen(lp); if (!expanded) snapToEdge(lp, false); scheduleIdleFade() }
        refreshPathingAction()
    }

    private fun hintNeedShare() {
        rootView?.findViewById<TextView>(R.id.overlay_auto_skip_hint)?.apply { text = "需先开启共享屏幕"; visibility = View.VISIBLE }
        rootView?.findViewById<View>(R.id.overlay_row_enabled)?.apply {
            animate().alpha(.35f).setDuration(160).withEndAction { animate().alpha(1f).setDuration(240).start() }.start()
        }
        resizePanel()
    }

    private fun resizePanel() {
        val root = rootView ?: return
        val safe = OverlayGeometry.safeBounds(windowManager, context.resources.displayMetrics.density,
            OverlayGeometry.cutoutInsets(root))
        val panel = panelView ?: return
        val header = root.findViewById<View>(R.id.overlay_header)
        val available = (safe.height() - dp(32) - header.measuredHeight.coerceAtLeast(dp(40))).coerceAtLeast(dp(48))
        root.findViewById<ScrollView>(R.id.overlay_panel_scroll).apply {
            measure(View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val nextHeight = measuredHeight.coerceAtMost(available)
            if (layoutParams.height != nextHeight) layoutParams = layoutParams.apply { height = nextHeight }
        }
        val nextWidth = minOf(dp(256), safe.width())
        if (panel.layoutParams.width != nextWidth) panel.layoutParams = panel.layoutParams.apply { width = nextWidth }
    }

    /**
     * 无障碍手势会先打到可触摸的悬浮窗。笔画落到这些窗口上时才临时穿透，
     * 避免每次模拟点击都改 FLAG_NOT_TOUCHABLE 导致窗口闪烁。
     * 必须在创建悬浮窗的主线程上调用。多次按下要配同样次数的 [restoreClickPassthrough]。
     */
    fun prepareClickPassthrough(x: Int, y: Int): Boolean = prepareAreaPassthrough(x, y, x, y)

    fun prepareAreaPassthrough(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (!coversStroke(x1, y1, x2, y2)) return false
        passthroughDepth++
        applyTouchPassthrough(params, rootView, passthrough = true)
        applyTouchPassthrough(logHandleParams, logHandleView, passthrough = true)
        applyTouchPassthrough(logBodyParams, logBodyView, passthrough = true)
        return true
    }

    fun restoreClickPassthrough() {
        if (passthroughDepth == 0) return
        passthroughDepth--
        if (passthroughDepth > 0) return
        applyTouchPassthrough(params, rootView, passthrough = false)
        applyTouchPassthrough(logHandleParams, logHandleView, passthrough = false)
        applyTouchPassthrough(logBodyParams, logBodyView, passthrough = false)
    }

    private fun applyTouchPassthrough(
        lp: WindowManager.LayoutParams?,
        view: View?,
        passthrough: Boolean,
    ) {
        if (lp == null || view == null) return
        val hasFlag = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        val notTouchable = passthrough || view === logBodyView
        // 仅置 NOT_TOUCHABLE 仍可能被系统当作不可信遮挡；注入期间同时让窗口透明。
        val alpha = if (passthrough) 0f else 1f
        if (notTouchable == hasFlag && lp.alpha == alpha) return
        lp.flags = if (notTouchable) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        lp.alpha = alpha
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun coversStroke(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val left = minOf(x1, x2)
        val top = minOf(y1, y2)
        val right = maxOf(x1, x2)
        val bottom = maxOf(y1, y2)
        return windowIntersects(params, rootView, left, top, right, bottom) ||
            windowIntersects(logHandleParams, logHandleView, left, top, right, bottom) ||
            windowIntersects(logBodyParams, logBodyView, left, top, right, bottom)
    }

    private fun windowIntersects(
        lp: WindowManager.LayoutParams?,
        view: View?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        if (lp == null || view == null || view.visibility != View.VISIBLE) return false
        val width = if (view.width > 0) view.width else return false
        val height = if (view.height > 0) view.height else return false
        val slop = dp(8)
        // 挖孔安全区可能让实际窗口位置不同于 LayoutParams 中请求的 x/y。
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val windowLeft = location[0] - slop
        val windowTop = location[1] - slop
        val windowRight = location[0] + width + slop
        val windowBottom = location[1] + height + slop
        return left <= windowRight && right >= windowLeft && top <= windowBottom && bottom >= windowTop
    }

    fun hide() {
        BaiduAnalytics.pageEnd(BaiduAnalytics.PAGE_OVERLAY_PANEL)
        stopScreenWatch()
        unregisterA11yReceiver()
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.removeCallbacks(clearTalkingRunnable)
        mainHandler.removeCallbacks(statusTick)
        mainHandler.removeCallbacks(refreshA11ySoon)
        mainHandler.removeCallbacks(refreshA11yLater)
        snapAnimator?.cancel()
        snapAnimator = null
        bubbleView?.animate()?.cancel()
        panelView?.animate()?.cancel()
        transforming = false
        expanded = false
        pendingCollapseOnOutside = false
        talkingUntilMs = 0L
        hideLogWindow()
        val view = rootView ?: return
        settingsRepository.removeListener(settingsListener)
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
        rootView = null
        bubbleView = null
        panelView = null
        statusDot = null
        statusText = null
        chatBadge = null
        params = null
        switchEnabled = null
        switchAutoSkip = null
        quickSkipChip = null
        autoLaunchChip = null
        switchAutoPick = null
        launchSubtitle = null
        logToggleButton = null
        rowAutoSkip = null
        rowAutoPick = null
        rowLaunch = null
        pathingRow = null
        pathingStatus = null
        pathingStart = null
    }

    private fun collapseOnOutsideTouch() {
        if (!expanded) return
        if (transforming) {
            pendingCollapseOnOutside = true
            return
        }
        setExpanded(false)
    }

    private fun openBilibiliSpace() {
        BilibiliSpaceOpener(themedContext).open()
        setExpanded(false)
    }

    private fun launchGenshinFromButton() {
        when (genshinLauncher.launch()) {
            is GenshinLaunchResult.Started -> setExpanded(false)
            GenshinLaunchResult.NotInstalled -> {
                launchSubtitle?.apply { text = "未安装原神"; visibility = View.VISIBLE }
                refreshLaunchHint()
            }
            is GenshinLaunchResult.Failed -> {
                launchSubtitle?.apply { text = "无法启动原神"; visibility = View.VISIBLE }; resizePanel()
            }
        }
    }

    private fun refreshLaunchHint() {
        val installed = genshinLauncher.resolveInstalledPackage() != null
        launchSubtitle?.apply { text = if (installed) "" else "未安装原神"; visibility = if (installed) View.GONE else View.VISIBLE }
        rootView?.findViewById<View>(R.id.overlay_launch)?.apply { isEnabled = installed; alpha = if (installed) 1f else .38f }
    }

    private fun exitAssistant() {
        if (transforming) return
        settingsRepository.setScreenShareEnabled(false)
        val panel = panelView
        if (panel != null && expanded) {
            transforming = true
            panel.animate().cancel()
            panel.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(160)
                .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .withEndAction { onExit() }
                .start()
        } else {
            onExit()
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value || transforming) return
        val bubble = bubbleView ?: return
        val panel = panelView ?: return
        val root = rootView ?: return
        expanded = value
        if (value) {
            BaiduAnalytics.pageStart(BaiduAnalytics.PAGE_OVERLAY_PANEL)
        } else {
            BaiduAnalytics.pageEnd(BaiduAnalytics.PAGE_OVERLAY_PANEL)
        }
        transforming = true
        if (!value) pendingCollapseOnOutside = false
        bubble.animate().cancel()
        panel.animate().cancel()
        val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        if (value) {
            refreshLaunchHint()
            resizePanel()
            mainHandler.removeCallbacks(idleFadeRunnable)
            panel.alpha = 0f
            panel.scaleX = 0.84f
            panel.scaleY = 0.84f
            panel.visibility = View.VISIBLE
            root.post {
                params?.let { ensurePanelOnScreen(it) }
                applyPanelPivot(panel)
                bubble.animate()
                    .alpha(0f)
                    .scaleX(0.72f)
                    .scaleY(0.72f)
                    .setDuration(160)
                    .setInterpolator(ease)
                    .withEndAction {
                        bubble.visibility = View.GONE
                        bubble.scaleX = 1f
                        bubble.scaleY = 1f
                    }
                    .start()
                panel.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(ease)
                    .withEndAction {
                        transforming = false
                        if (pendingCollapseOnOutside) {
                            pendingCollapseOnOutside = false
                            setExpanded(false)
                        }
                    }
                    .start()
            }
        } else {
            applyPanelPivot(panel)
            bubble.alpha = 0f
            bubble.scaleX = 0.72f
            bubble.scaleY = 0.72f
            bubble.visibility = View.VISIBLE
            panel.animate()
                .alpha(0f)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(200)
                .setInterpolator(ease)
                .withEndAction {
                    panel.visibility = View.GONE
                    panel.alpha = 1f
                    panel.scaleX = 1f
                    panel.scaleY = 1f
                }
                .start()
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(ease)
                .withEndAction {
                    transforming = false
                    params?.let { snapToEdge(it, animate = true) }
                    scheduleIdleFade()
                }
                .start()
        }
    }

    private fun applyPanelPivot(panel: View) {
        val lp = params ?: return
        val screen = screenSize()
        val onRight = lp.x + (rootView?.width ?: 0) / 2f > screen.first / 2f
        panel.pivotX = if (onRight) panel.width.toFloat() else 0f
        panel.pivotY = 0f
    }

    private fun applyFeatureEnabled(settings: TriggerSettings) {
        val shareOn = settings.screenShareEnabled
        switchAutoSkip?.alpha = if (shareOn) 1f else .38f
        switchAutoPick?.isEnabled = shareOn
        rowAutoPick?.alpha = if (shareOn) 1f else .45f
        quickSkipChip?.apply { alpha = if (shareOn) 1f else .38f
            text = if (overlayFontScale > 1.1f) "快跳" else "快速跳过"
            setTextColor(ContextCompat.getColor(themedContext, if (settings.quickSkipDialogueEnabled) R.color.pocket_primary else R.color.pocket_text_secondary))
            setBackgroundResource(if (settings.quickSkipDialogueEnabled) R.drawable.bg_pocket_chip else R.drawable.bg_pocket_group)
        }
        autoLaunchChip?.apply {
            setTextColor(ContextCompat.getColor(themedContext, if (settings.autoLaunchGenshinEnabled) R.color.pocket_primary else R.color.pocket_text_secondary))
            setBackgroundResource(if (settings.autoLaunchGenshinEnabled) R.drawable.bg_pocket_chip else R.drawable.bg_pocket_group)
        }
        if (shareOn) rootView?.findViewById<View>(R.id.overlay_auto_skip_hint)?.visibility = View.GONE
    }

    override fun onTalkHistoryMatched() {
        mainHandler.post {
            talkingUntilMs = System.currentTimeMillis() + TALKING_HOLD_MS
            mainHandler.removeCallbacks(clearTalkingRunnable)
            mainHandler.postDelayed(clearTalkingRunnable, TALKING_HOLD_MS)
            refreshStatus()
        }
    }

    override fun onChatIconsRecognized(count: Int, topX: Int, topY: Int) {
        appendLog("识别到对话选项 ${count} 个，最高位置 ($topX, $topY)")
    }

    override fun onChatIconClicked(x: Int, y: Int) {
        appendLog("点击对话选项 ($x, $y)")
    }

    fun log(message: String, level: LogLevel = LogLevel.Info) {
        Log.i(TAG, message); appendLog(message, level)
    }

    fun setPathingRunning(running: Boolean) { mainHandler.post {
        readoutRunning = running
        if (running) { if (!logWindowVisible) { logExpanded = false; setLogWindowVisible(true) }; setExpanded(false) }
        refreshPathingAction(); refreshStatus(); updateStatusTick()
    } }

    fun setRouteRunning(running: Boolean) { mainHandler.post {
        routeRunning = running
        if (running) {
            progress = null
            if (!logWindowVisible) { logExpanded = false; setLogWindowVisible(true) }
            setExpanded(false)
        }
        refreshPathingAction(); refreshStatus(); updateStatusTick()
    } }

    private fun updateStatusTick() {
        mainHandler.removeCallbacks(statusTick)
        if (routeRunning || readoutRunning) mainHandler.postDelayed(statusTick, 1000)
    }

    fun updateProgress(value: PathingProgress) { mainHandler.post {
        progress = value
        pathingStatus?.text = if (value.finished) "${logTimeFormat.format(Date())} 完成 ${value.completed} · 跳过 ${value.skipped} · 失败 ${value.failed}" else "${value.index}/${value.total} · ${value.name}"
        rootView?.findViewById<ProgressBar>(R.id.overlay_pathing_progress)?.apply { max = value.total.coerceAtLeast(1); progress = if (value.finished) value.total else value.index - 1 }
        refreshStatus()
    } }

    private fun refreshPathingAction() {
        val running = routeRunning || readoutRunning
        pathingStart?.apply {
            setImageResource(if (running) R.drawable.ic_ui_stop else R.drawable.ic_ui_play_arrow)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(themedContext,
                if (running) R.color.pocket_error else if (hasRouteSelection) R.color.pocket_primary else R.color.pocket_text_disabled))
            contentDescription = if (running) "停止地图追踪" else if (hasRouteSelection) "执行上次选择的路线" else "选择路线"
            TooltipCompat.setTooltipText(this, contentDescription)
        }
        rootView?.findViewById<View>(R.id.overlay_pathing_progress)?.visibility = if (routeRunning) View.VISIBLE else View.GONE
        logHandleView?.findViewById<View>(R.id.overlay_log_stop)?.visibility = if (running) View.VISIBLE else View.GONE
        logHandleView?.findViewById<ImageView>(R.id.overlay_log_icon)?.apply {
            if (running) {
                setImageDrawable(null)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(themedContext, R.color.pocket_primary)) }
            } else { background = null; setImageResource(R.drawable.ic_ui_article) }
            layoutParams = layoutParams.apply { width = dp(if (running) 8 else 16); height = width }
        }
    }

    fun showPathing(routeLabel: String, hasSelection: Boolean = true) { mainHandler.post {
        pathingSummary = routeLabel
        hasRouteSelection = hasSelection
        if (!routeRunning && !readoutRunning) { pathingStatus?.text = routeLabel
            pathingStatus?.setTextColor(ContextCompat.getColor(themedContext, R.color.pocket_text_secondary)) }
        refreshPathingAction()
    } }

    fun setPathingStatus(text: String) { mainHandler.post {
        latestPathingStatus = text
        if (progress?.finished != true || routeRunning || readoutRunning) pathingStatus?.text = text
        pathingStatus?.setTextColor(ContextCompat.getColor(themedContext,
            if (!routeRunning && !readoutRunning && progress?.finished != true && text != "已停止") R.color.pocket_warning else R.color.pocket_text_secondary))
        refreshStatus()
    } }

    fun showCaptureInterrupted() { mainHandler.post { captureInterrupted = true; refreshStatus() } }

    fun expandStatusBar() {
        if (!logWindowVisible) setLogWindowVisible(true)
        logExpanded = true; applyLogMode(); refreshStatus()
    }

    private fun appendLog(message: String, level: LogLevel = LogLevel.Info) { mainHandler.post {
        // 路线文件名由批执行器统一缩短，保留其他日志中的有效上下文。
        if (logLines.size >= MAX_LOG_LINES) logLines.removeFirst()
        logLines.addLast(LogLine(logTimeFormat.format(Date()), message, level))
        renderLog(); refreshLogTitle()
    } }

    private fun renderLog() {
        val text = logText ?: return
        if (!logWindowVisible) return
        val builder = SpannableStringBuilder()
        if (logLines.isEmpty()) builder.append("等待识别…")
        for (line in logLines) {
            if (builder.isNotEmpty()) builder.append('\n')
            val start = builder.length; builder.append(line.time).append("  ")
            builder.setSpan(ForegroundColorSpan(ContextCompat.getColor(themedContext, R.color.pocket_text_secondary)), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(line.message)
            if (line.level != LogLevel.Info) builder.setSpan(ForegroundColorSpan(ContextCompat.getColor(themedContext,
                if (line.level == LogLevel.Warning) R.color.pocket_warning else R.color.pocket_error)), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.text = builder; logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setLogWindowVisible(visible: Boolean, persist: Boolean = true) {
        logWindowVisible = visible
        if (visible) showLogWindow() else hideLogWindow()
        if (persist) persistLogMode()
        refreshLogToggle()
    }

    private fun persistLogMode() { prefs.edit().putInt(KEY_STATUS_MODE, if (!logWindowVisible) 0 else if (logExpanded) 2 else 1).apply() }

    private fun applyLogMode() {
        logBodyView?.visibility = if (logExpanded) View.VISIBLE else View.GONE
        logHandleView?.findViewById<ImageButton>(R.id.overlay_log_expand)?.apply {
            setImageResource(if (logExpanded) R.drawable.ic_ui_expand_less else R.drawable.ic_ui_expand_more)
            contentDescription = if (logExpanded) "收起日志正文" else "展开日志正文"; TooltipCompat.setTooltipText(this, contentDescription)
        }
        persistLogMode(); clampLogWindows()
    }

    private fun refreshLogTitle() {
        val p = progress
        logHandleView?.findViewById<TextView>(R.id.overlay_log_elapsed)?.apply {
            visibility = if (routeRunning && p != null) View.VISIBLE else View.GONE
            if (routeRunning && p != null) {
                val seconds = ((System.currentTimeMillis() - p.startedAtMs) / 1000).coerceAtLeast(0)
                text = "%02d:%02d".format(seconds / 60, seconds % 60)
            }
        }
        logTitle?.text = when {
            routeRunning && p != null -> "${p.index}/${p.total} · ${p.name}"
            routeRunning -> latestPathingStatus.ifEmpty { "正在读取路线…" }
            readoutRunning -> latestPathingStatus
            p?.finished == true -> "完成 ${p.completed} · 跳过 ${p.skipped} · 失败 ${p.failed}"
            else -> logLines.lastOrNull()?.message ?: "等待识别…"
        }
        logTitle?.contentDescription = logTitle?.text
    }

    private fun refreshLogToggle() {
        val button = logToggleButton ?: return
        button.isSelected = logWindowVisible
        val color = ContextCompat.getColor(
            themedContext,
            if (logWindowVisible) R.color.pocket_primary else R.color.pocket_text_secondary,
        )
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color))
    }

    private fun isTalking(): Boolean = System.currentTimeMillis() < talkingUntilMs

    private fun registerA11yReceiver() {
        if (a11yReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            a11yReceiver,
            IntentFilter(InputAccessibilityService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        a11yReceiverRegistered = true
    }

    private fun unregisterA11yReceiver() {
        if (!a11yReceiverRegistered) return
        try {
            context.unregisterReceiver(a11yReceiver)
        } catch (_: Exception) {
        }
        a11yReceiverRegistered = false
    }

    private fun refreshStatus() {
        val enabled = settingsRepository.get().screenShareEnabled
        val talking = isTalking()
        val a11yHealth = InputAccessibilityService.health(themedContext)
        val a11yDisconnected = a11yWarningReady && a11yHealth == AccessibilityServiceHealth.State.DISCONNECTED
        val a11yUnavailable = a11yWarningReady && a11yHealth != AccessibilityServiceHealth.State.CONNECTED
        val colorRes = when {
            a11yDisconnected -> R.color.overlay_status_warn
            talking -> R.color.overlay_status_on
            enabled -> R.color.overlay_status_on
            else -> R.color.overlay_status_off
        }
        val color = ContextCompat.getColor(themedContext, colorRes)
        (statusDot?.background?.mutate() as? GradientDrawable)?.setColor(color)
            ?: statusDot?.background?.let { drawable ->
                DrawableCompat.setTint(DrawableCompat.wrap(drawable).mutate(), color)
            }
        statusText?.text = when {
            a11yUnavailable -> "无障碍异常"
            routeRunning -> progress?.let { "路线 ${it.index}/${it.total}" } ?: "路线执行中"
            readoutRunning -> "坐标显示中"
            talking -> "对话中"
            enabled -> "共享中"
            else -> "未共享"
        }
        statusText?.setTextColor(ContextCompat.getColor(themedContext, when {
            a11yUnavailable -> R.color.pocket_warning
            routeRunning || readoutRunning -> R.color.pocket_primary
            talking || enabled -> R.color.pocket_success
            else -> R.color.pocket_text_secondary
        }))
        rootView?.findViewById<View>(R.id.overlay_warning)?.visibility = if (a11yUnavailable || captureInterrupted) View.VISIBLE else View.GONE
        rootView?.findViewById<TextView>(R.id.overlay_warning_text)?.text = if (a11yUnavailable) "无障碍未连接" else "屏幕共享被中断"
        refreshLogTitle()
        if (expanded) resizePanel()
        val badge = chatBadge
        if (badge != null) {
            val showBadge = talking
            if (showBadge && badge.visibility != View.VISIBLE) {
                badge.alpha = 0f
                badge.visibility = View.VISIBLE
                badge.animate().alpha(1f).setDuration(160).start()
            } else if (!showBadge && badge.visibility == View.VISIBLE) {
                badge.animate().alpha(0f).setDuration(160).withEndAction {
                    badge.visibility = View.GONE
                }.start()
            }
        }
    }

    private fun showLogWindow() {
        if (logHandleView != null || logBodyView != null) return
        val handle = LayoutInflater.from(themedContext).inflate(R.layout.overlay_log_handle, null)
        val body = LayoutInflater.from(themedContext).inflate(R.layout.overlay_log_body, null)
        logTitle = handle.findViewById(R.id.overlay_log_title)
        logText = body.findViewById(R.id.overlay_log_text)
        logScroll = body.findViewById(R.id.overlay_log_scroll)
        renderLog()

        val width = logWidth()
        val (defaultX, defaultY) = defaultLogPosition()
        val minY = safeBounds().top
        val x = prefs.getInt(KEY_LOG_X, defaultX)
        val savedY = prefs.getInt(KEY_LOG_Y, defaultY)
        val y = if (savedY < minY) defaultY else savedY

        val handleParams = overlayParams(
            width = width,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
            x = x,
            y = y,
        )
        val bodyParams = overlayParams(
            width = width,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = false,
            x = x,
            y = y + dp(28),
        )

        logHandleView = handle
        logBodyView = body
        logHandleParams = handleParams
        logBodyParams = bodyParams
        setupLogDrag(handle.findViewById(R.id.overlay_log_drag), handleParams)
        handle.findViewById<View>(R.id.overlay_log_stop).setOnClickListener { onPathingStop?.invoke() }
        handle.findViewById<View>(R.id.overlay_log_expand).setOnClickListener { logExpanded = !logExpanded; applyLogMode() }
        listOf(R.id.overlay_log_stop, R.id.overlay_log_expand, R.id.overlay_log_close).forEach { id ->
            handle.findViewById<View>(id).let { TooltipCompat.setTooltipText(it, it.contentDescription) }
        }
        logScroll?.layoutParams = logScroll?.layoutParams?.apply { height = dp(if (safeBounds().height() / context.resources.displayMetrics.density < 400) 100 else 200) }
        body.visibility = if (logExpanded) View.VISIBLE else View.GONE
        refreshPathingAction(); refreshLogTitle()
        handle.findViewById<View>(R.id.overlay_log_close).setOnClickListener {
            setLogWindowVisible(false)
        }
        try {
            windowManager.addView(body, bodyParams)
            windowManager.addView(handle, handleParams)
            handle.post { applyLogMode() }
        } catch (_: Throwable) {
            hideLogWindow()
        }
    }

    private fun hideLogWindow() {
        listOf(logHandleView, logBodyView).forEach { view ->
            if (view != null) {
                try {
                    windowManager.removeView(view)
                } catch (_: Throwable) {
                }
            }
        }
        logHandleView = null
        logBodyView = null
        logHandleParams = null
        logBodyParams = null
        logTitle = null
        logText = null
        logScroll = null
    }

    private fun overlayParams(
        width: Int,
        height: Int,
        touchable: Boolean,
        x: Int,
        y: Int,
    ): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun setupLogDrag(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - touchX) > touchSlop || kotlin.math.abs(event.rawY - touchY) > touchSlop) moved = true
                    if (!moved) return@setOnTouchListener true
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    clampLogWindows()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) { dragHandle.performClick(); logExpanded = !logExpanded; applyLogMode() }
                    persistLogPosition(lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun clampLogWindows() {
        val handleLp = logHandleParams ?: return
        val handle = logHandleView ?: return
        val safe = safeBounds()
        handleLp.width = logWidth(); logBodyParams?.width = handleLp.width
        val width = handleLp.width
        val handleHeight = if (handle.height > 0) handle.height else dp(28)
        val bodyHeight = if (logExpanded) logBodyView?.height?.takeIf { it > 0 } ?: dp(120) else 0
        handleLp.x = handleLp.x.coerceIn(safe.left, (safe.right - width).coerceAtLeast(safe.left))
        handleLp.y = handleLp.y.coerceIn(safe.top, (safe.bottom - handleHeight - bodyHeight).coerceAtLeast(safe.top))
        updateLogLayouts()
    }

    private fun updateLogLayouts() {
        val handleLp = logHandleParams ?: return
        val bodyLp = logBodyParams ?: return
        val handle = logHandleView ?: return
        val body = logBodyView ?: return
        val handleHeight = if (handle.height > 0) handle.height else dp(28)
        bodyLp.x = handleLp.x
        bodyLp.y = handleLp.y + handleHeight
        try {
            windowManager.updateViewLayout(handle, handleLp)
        } catch (_: Throwable) {
        }
        try {
            windowManager.updateViewLayout(body, bodyLp)
        } catch (_: Throwable) {
        }
    }

    private fun persistLogPosition(lp: WindowManager.LayoutParams) {
        prefs.edit().putInt(KEY_LOG_X, lp.x).putInt(KEY_LOG_Y, lp.y).apply()
    }

    private fun setupDrag(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
        snapOnRelease: Boolean,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    clampToScreen(lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistPosition(lp)
                    if (snapOnRelease && !expanded) {
                        snapToEdge(lp, animate = true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDragAndClick(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
        onClick: () -> Unit,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        dragHandle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    moved = false
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    wakeBubble()
                    dragHandle.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        clampToScreen(lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragHandle.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!moved) {
                        v.performClick()
                        onClick()
                    } else {
                        persistPosition(lp)
                        snapToEdge(lp, animate = true)
                    }
                    if (!expanded) scheduleIdleFade()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragHandle.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (moved) {
                        persistPosition(lp)
                        snapToEdge(lp, animate = true)
                    }
                    if (!expanded) scheduleIdleFade()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(lp: WindowManager.LayoutParams, animate: Boolean) {
        val view = rootView ?: return
        val screen = screenSize()
        val width = if (view.width > 0) view.width else dp(48)
        val targetX = if (lp.x + width / 2 < screen.first / 2) 0 else screen.first - width
        if (!animate || lp.x == targetX) {
            lp.x = targetX
            clampToScreen(lp)
            persistPosition(lp)
            return
        }
        snapAnimator?.cancel()
        val fromX = lp.x
        snapAnimator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                lp.x = animator.animatedValue as Int
                updateLayout(lp)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistPosition(lp)
                }
            })
            start()
        }
    }

    private fun ensurePanelOnScreen(lp: WindowManager.LayoutParams) {
        clampToScreen(lp)
    }

    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val screen = screenSize()
        val safe = if (expanded) safeBounds() else android.graphics.Rect(0, 0, screen.first, screen.second)
        val width = if (view.width > 0) view.width else dp(48)
        val height = if (view.height > 0) view.height else dp(48)
        lp.x = lp.x.coerceIn(safe.left, (safe.right - width).coerceAtLeast(safe.left))
        lp.y = lp.y.coerceIn(safe.top, (safe.bottom - height).coerceAtLeast(safe.top))
        updateLayout(lp)
    }

    private fun updateLayout(lp: WindowManager.LayoutParams) {
        val view = rootView ?: return
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun persistPosition(lp: WindowManager.LayoutParams) {
        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
    }

    private fun wakeBubble() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        fadeBubble(1f)
    }

    private fun scheduleIdleFade() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        if (!expanded) {
            mainHandler.postDelayed(idleFadeRunnable, IDLE_DELAY_MS)
        }
    }

    private fun fadeBubble(alpha: Float) {
        val bubble = bubbleView ?: return
        if (expanded || bubble.visibility != View.VISIBLE) return
        bubble.animate().alpha(alpha).setDuration(220).start()
    }

    private fun startScreenWatch() {
        if (watchingScreen) return
        watchingScreen = true
        displayManager.registerDisplayListener(displayListener, mainHandler)
        context.registerComponentCallbacks(configCallbacks)
    }

    private fun stopScreenWatch() {
        if (!watchingScreen) return
        watchingScreen = false
        displayManager.unregisterDisplayListener(displayListener)
        context.unregisterComponentCallbacks(configCallbacks)
    }

    private fun rememberScreen() {
        val screen = screenSize()
        lastScreenW = screen.first
        lastScreenH = screen.second
    }

    private fun relocateOverlays(force: Boolean) {
        val root = rootView ?: return
        val screen = screenSize()
        if (!force && screen.first == lastScreenW && screen.second == lastScreenH) return
        snapAnimator?.cancel()
        root.post {
            resizePanel()
            rememberScreen()
            val lp = params ?: return@post
            clampToScreen(lp)
            if (!expanded) {
                snapToEdge(lp, animate = false)
            } else {
                persistPosition(lp)
            }
            clampLogWindows()
            logHandleParams?.let { persistLogPosition(it) }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun safeBounds() = OverlayGeometry.safeBounds(windowManager, context.resources.displayMetrics.density,
        OverlayGeometry.cutoutInsets(rootView))

    private fun logWidth(): Int = minOf(dp(320), (safeBounds().width() * .4f).toInt()).coerceAtLeast(dp(120))

    private fun defaultLogPosition(): Pair<Int, Int> {
        val safe = safeBounds()
        return (safe.centerX() - logWidth() / 2) to (safe.top + dp(40))
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        private const val TAG = "BetterGI.Pathing"
        private const val PREFS_NAME = "overlay_window"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_LOG_X = "log_x"
        private const val KEY_LOG_Y = "log_y"
        private const val KEY_LOG_VISIBLE = "log_visible"
        private const val KEY_STATUS_MODE = "status_mode"
        private const val IDLE_ALPHA = 0.62f
        private const val IDLE_DELAY_MS = 2400L
        private const val TALKING_HOLD_MS = 2000L
        private const val MAX_LOG_LINES = 80
    }
}

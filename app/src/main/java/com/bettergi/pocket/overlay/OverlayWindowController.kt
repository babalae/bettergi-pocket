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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.analytics.BaiduAnalytics
import com.bettergi.pocket.bilibili.BilibiliSpaceOpener
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipEvents
import com.bettergi.pocket.genshin.GenshinLaunchResult
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.genshin.GenshinPackages
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
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_BetterGIPocket)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastScreenW = 0
    private var lastScreenH = 0
    private var watchingScreen = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            relocateOverlays(force = false)
        }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
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
    private var switchQuickSkip: SwitchCompat? = null
    private var switchAutoPick: SwitchCompat? = null
    private var switchAutoLaunch: SwitchCompat? = null
    private var launchHint: TextView? = null
    private var launchSubtitle: TextView? = null
    private var logToggleButton: ImageButton? = null
    private var rowAutoSkip: View? = null
    private var rowQuickSkip: View? = null
    private var rowAutoPick: View? = null
    private var rowLaunch: View? = null
    private var autoSkipExtras: View? = null
    private var autoSkipChevron: ImageView? = null
    private var autoSkipMenuExpanded = false
    private var launchExtras: View? = null
    private var launchChevron: ImageView? = null
    private var launchMenuExpanded = false
    private var logHandleView: View? = null
    private var logBodyView: View? = null
    private var logTitle: TextView? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var logHandleParams: WindowManager.LayoutParams? = null
    private var logBodyParams: WindowManager.LayoutParams? = null
    private val logLines = ArrayDeque<String>(MAX_LOG_LINES)
    private var logWindowVisible = false
    private var talkingUntilMs: Long = 0L
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val clearTalkingRunnable = Runnable { refreshStatus() }

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
            switchQuickSkip?.isChecked = settings.quickSkipDialogueEnabled
            switchAutoPick?.isChecked = settings.autoPickEnabled
            switchAutoLaunch?.isChecked = settings.autoLaunchGenshinEnabled
            applyFeatureEnabled(settings)
            refreshLaunchHint()
            refreshStatus()
        } finally {
            updatingUi = false
        }
    }

    fun show() {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val root = LayoutInflater.from(themedContext).inflate(R.layout.overlay_window, null)
        val bubble = root.findViewById<View>(R.id.overlay_bubble)
        val panel = root.findViewById<View>(R.id.overlay_panel)
        val collapse = root.findViewById<ImageButton>(R.id.overlay_collapse)
        val header = root.findViewById<View>(R.id.overlay_header)
        val enabledSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_enabled)
        val autoSkipSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_skip)
        val quickSkipSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_quick_skip)
        val autoPickSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_pick)
        val autoLaunchSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_launch)
        val logToggle = root.findViewById<ImageButton>(R.id.overlay_log_toggle)

        bubbleView = bubble
        panelView = panel
        statusDot = root.findViewById(R.id.overlay_status_dot)
        statusText = root.findViewById<TextView>(R.id.overlay_status_text).also { text ->
            text.setOnClickListener {
                if (InputAccessibilityService.health(themedContext) != AccessibilityServiceHealth.State.CONNECTED) {
                    InputAccessibilityService.ensureEnabled(themedContext)
                }
            }
        }
        chatBadge = root.findViewById(R.id.overlay_chat_badge)
        switchEnabled = enabledSwitch
        switchAutoSkip = autoSkipSwitch
        switchQuickSkip = quickSkipSwitch
        switchAutoPick = autoPickSwitch
        switchAutoLaunch = autoLaunchSwitch
        launchHint = root.findViewById(R.id.overlay_auto_launch_hint)
        launchSubtitle = root.findViewById(R.id.overlay_launch_subtitle)
        logToggleButton = logToggle
        rowAutoSkip = root.findViewById(R.id.overlay_row_auto_skip)
        rowQuickSkip = root.findViewById(R.id.overlay_row_quick_skip)
        rowLaunch = root.findViewById(R.id.overlay_row_launch)
        rowAutoPick = root.findViewById<View>(R.id.overlay_row_auto_pick).also { row ->
            row.visibility = if (AutoPickFeature.AVAILABLE) View.VISIBLE else View.GONE
            if (!AutoPickFeature.AVAILABLE) {
                settingsRepository.setAutoPickEnabled(false)
            }
        }
        autoSkipExtras = root.findViewById(R.id.overlay_auto_skip_extras)
        autoSkipChevron = root.findViewById(R.id.overlay_auto_skip_chevron)
        launchExtras = root.findViewById(R.id.overlay_launch_extras)
        launchChevron = root.findViewById(R.id.overlay_launch_chevron)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, dp(120))
        }

        setupDragAndClick(bubble, layoutParams) {
            setExpanded(true)
        }
        setupDrag(header, layoutParams, snapOnRelease = false)
        collapse.setOnClickListener { setExpanded(false) }
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                collapseOnOutsideTouch()
                true
            } else {
                false
            }
        }
        logToggle.setOnClickListener { setLogWindowVisible(!logWindowVisible) }
        rowAutoSkip?.setOnClickListener { setAutoSkipMenuExpanded(!autoSkipMenuExpanded) }
        rowLaunch?.setOnClickListener { setLaunchMenuExpanded(!launchMenuExpanded) }
        root.findViewById<View>(R.id.overlay_launch).setOnClickListener { launchGenshinFromButton() }
        root.findViewById<ImageButton>(R.id.overlay_bilibili).also { button ->
            ImageViewCompat.setImageTintList(button, null)
            button.setOnClickListener { openBilibiliSpace() }
        }
        root.findViewById<View>(R.id.overlay_exit).setOnClickListener { exitAssistant() }

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setScreenShareEnabled(isChecked)
            BaiduAnalytics.event("screen_share_toggle", isChecked.toString())
        }
        autoSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoSkipEnabled(isChecked)
            BaiduAnalytics.event("auto_skip_toggle", isChecked.toString())
            if (isChecked) {
                InputAccessibilityService.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟点击对话选项")
            }
        }
        quickSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setQuickSkipDialogueEnabled(isChecked)
            BaiduAnalytics.event("quick_skip_toggle", isChecked.toString())
        }
        autoPickSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoPickEnabled(isChecked)
            BaiduAnalytics.event("auto_pick_toggle", isChecked.toString())
            if (isChecked) {
                InputAccessibilityService.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟点击拾取")
            }
        }
        autoLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoLaunchGenshinEnabled(isChecked)
            BaiduAnalytics.event("auto_launch_genshin_toggle", isChecked.toString())
        }
        rootView = root
        params = layoutParams
        windowManager.addView(root, layoutParams)
        setLogWindowVisible(prefs.getBoolean(KEY_LOG_VISIBLE, false), persist = false)
        setAutoSkipMenuExpanded(prefs.getBoolean(KEY_AUTO_SKIP_EXPANDED, false), persist = false)
        setLaunchMenuExpanded(prefs.getBoolean(KEY_LAUNCH_EXPANDED, false), persist = false)
        settingsRepository.addListener(settingsListener)
        startScreenWatch()
        a11yWarningReady = false
        registerA11yReceiver()
        mainHandler.postDelayed(refreshA11ySoon, 400L)
        mainHandler.postDelayed(refreshA11yLater, 2000L)
        root.post {
            rememberScreen()
            clampToScreen(layoutParams)
            if (!expanded) snapToEdge(layoutParams, animate = false)
            scheduleIdleFade()
        }
    }

    /**
     * 无障碍手势会先打到可触摸的悬浮窗。只有点击落在这些窗口上时才临时穿透，
     * 避免每次模拟点击都改 FLAG_NOT_TOUCHABLE 导致窗口闪烁。
     */
    fun prepareClickPassthrough(x: Int, y: Int): Boolean {
        var needed = false
        if (windowContains(params, rootView, x, y)) {
            applyTouchPassthrough(params, rootView, passthrough = true)
            needed = true
        }
        if (windowContains(logHandleParams, logHandleView, x, y)) {
            applyTouchPassthrough(logHandleParams, logHandleView, passthrough = true)
            needed = true
        }
        return needed
    }

    fun restoreClickPassthrough() {
        applyTouchPassthrough(params, rootView, passthrough = false)
        applyTouchPassthrough(logHandleParams, logHandleView, passthrough = false)
    }

    private fun applyTouchPassthrough(
        lp: WindowManager.LayoutParams?,
        view: View?,
        passthrough: Boolean,
    ) {
        if (lp == null || view == null) return
        val hasFlag = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        if (passthrough == hasFlag) return
        lp.flags = if (passthrough) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun windowContains(
        lp: WindowManager.LayoutParams?,
        view: View?,
        x: Int,
        y: Int,
    ): Boolean {
        if (lp == null || view == null) return false
        val width = if (view.width > 0) view.width else return false
        val height = if (view.height > 0) view.height else return false
        val slop = dp(8)
        return x >= lp.x - slop &&
            x < lp.x + width + slop &&
            y >= lp.y - slop &&
            y < lp.y + height + slop
    }

    fun hide() {
        BaiduAnalytics.pageEnd(BaiduAnalytics.PAGE_OVERLAY_PANEL)
        stopScreenWatch()
        unregisterA11yReceiver()
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.removeCallbacks(clearTalkingRunnable)
        mainHandler.removeCallbacks(refreshA11ySoon)
        mainHandler.removeCallbacks(refreshA11yLater)
        snapAnimator?.cancel()
        snapAnimator = null
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
        switchQuickSkip = null
        switchAutoPick = null
        switchAutoLaunch = null
        launchHint = null
        launchSubtitle = null
        logToggleButton = null
        rowAutoSkip = null
        rowQuickSkip = null
        rowAutoPick = null
        rowLaunch = null
        autoSkipExtras = null
        autoSkipChevron = null
        launchExtras = null
        launchChevron = null
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
                Toast.makeText(themedContext, "未安装原神", Toast.LENGTH_SHORT).show()
                refreshLaunchHint()
            }
            is GenshinLaunchResult.Failed -> {
                Toast.makeText(themedContext, "无法启动原神", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshLaunchHint() {
        val pkg = genshinLauncher.resolveInstalledPackage()
        if (pkg != null) {
            val name = GenshinPackages.displayName(pkg)
            launchSubtitle?.text = "打开已安装的$name"
            launchHint?.text = "启动助手时若未检测到${name}则打开一次"
        } else {
            launchSubtitle?.text = "未安装原神"
            launchHint?.text = "未安装原神"
        }
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
        val autoSkipOn = shareOn && settings.autoSkipEnabled
        switchAutoSkip?.isEnabled = shareOn
        switchAutoPick?.isEnabled = shareOn
        switchQuickSkip?.isEnabled = autoSkipOn
        rowAutoSkip?.alpha = if (shareOn) 1f else 0.45f
        rowAutoPick?.alpha = if (shareOn) 1f else 0.45f
        rowQuickSkip?.alpha = if (autoSkipOn) 1f else 0.45f
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

    private fun appendLog(message: String) {
        mainHandler.post {
            if (!logWindowVisible || logText == null) return@post
            val line = "${logTimeFormat.format(Date())} $message"
            if (logLines.size >= MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            logLines.addLast(line)
            logText?.text = logLines.joinToString("\n")
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setLogWindowVisible(visible: Boolean, persist: Boolean = true) {
        if (persist) {
            prefs.edit().putBoolean(KEY_LOG_VISIBLE, visible).apply()
        }
        logWindowVisible = visible
        if (visible) {
            showLogWindow()
        } else {
            hideLogWindow()
        }
        refreshLogToggle()
    }

    private fun refreshLogToggle() {
        val button = logToggleButton ?: return
        button.isSelected = logWindowVisible
        val color = ContextCompat.getColor(
            themedContext,
            if (logWindowVisible) R.color.overlay_log_green else R.color.overlay_text_muted,
        )
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color))
    }

    private fun setAutoSkipMenuExpanded(expanded: Boolean, persist: Boolean = true) {
        autoSkipMenuExpanded = expanded
        if (persist) {
            prefs.edit().putBoolean(KEY_AUTO_SKIP_EXPANDED, expanded).apply()
        }
        autoSkipExtras?.visibility = if (expanded) View.VISIBLE else View.GONE
        autoSkipChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(160)?.start()
    }

    private fun setLaunchMenuExpanded(expanded: Boolean, persist: Boolean = true) {
        launchMenuExpanded = expanded
        if (persist) {
            prefs.edit().putBoolean(KEY_LAUNCH_EXPANDED, expanded).apply()
        }
        launchExtras?.visibility = if (expanded) View.VISIBLE else View.GONE
        launchChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(160)?.start()
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
        val a11yDisconnected = a11yWarningReady &&
            InputAccessibilityService.health(themedContext) == AccessibilityServiceHealth.State.DISCONNECTED
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
            a11yDisconnected -> "无障碍异常"
            talking -> "正在对话中"
            enabled -> "已启动"
            else -> "未启动"
        }
        statusText?.setTextColor(
            when {
                a11yDisconnected -> ContextCompat.getColor(themedContext, R.color.overlay_status_warn)
                talking || enabled -> ContextCompat.getColor(themedContext, R.color.overlay_status_on)
                else -> ContextCompat.getColor(themedContext, R.color.overlay_text_muted)
            },
        )
        logTitle?.text = if (talking) "正在对话中" else "识别日志"
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

        val width = dp(LOG_WIDTH_DP)
        val (defaultX, defaultY) = defaultLogPosition()
        val minY = statusBarHeight()
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
        handle.findViewById<View>(R.id.overlay_log_close).setOnClickListener {
            setLogWindowVisible(false)
        }
        try {
            windowManager.addView(body, bodyParams)
            windowManager.addView(handle, handleParams)
            handle.post { clampLogWindows() }
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
        logLines.clear()
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

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    clampLogWindows()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        val screen = screenSize()
        val width = if (handle.width > 0) handle.width else dp(LOG_WIDTH_DP)
        val handleHeight = if (handle.height > 0) handle.height else dp(28)
        val bodyHeight = logBodyView?.height?.takeIf { it > 0 } ?: dp(120)
        val minY = statusBarHeight()
        handleLp.x = handleLp.x.coerceIn(0, (screen.first - width).coerceAtLeast(0))
        handleLp.y = handleLp.y.coerceIn(
            minY,
            (screen.second - handleHeight - bodyHeight).coerceAtLeast(minY),
        )
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
        val width = if (view.width > 0) view.width else dp(48)
        val height = if (view.height > 0) view.height else dp(48)
        lp.x = lp.x.coerceIn(0, (screen.first - width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screen.second - height).coerceAtLeast(0))
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

    private fun defaultLogPosition(): Pair<Int, Int> {
        val screen = screenSize()
        val height = dp(LOG_DEFAULT_HEIGHT_DP)
        val margin = dp(12)
        val x = margin
        val y = (screen.second - height - dp(48)).coerceAtLeast(statusBarHeight())
        return x to y
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) {
            return context.resources.getDimensionPixelSize(id)
        }
        return dp(28)
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        private const val PREFS_NAME = "overlay_window"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_LOG_X = "log_x"
        private const val KEY_LOG_Y = "log_y"
        private const val KEY_LOG_VISIBLE = "log_visible"
        private const val KEY_AUTO_SKIP_EXPANDED = "auto_skip_expanded"
        private const val KEY_LAUNCH_EXPANDED = "launch_expanded"
        private const val LOG_WIDTH_DP = 260
        private const val LOG_DEFAULT_HEIGHT_DP = 148
        private const val IDLE_ALPHA = 0.62f
        private const val IDLE_DELAY_MS = 2400L
        private const val TALKING_HOLD_MS = 2000L
        private const val MAX_LOG_LINES = 16
    }
}

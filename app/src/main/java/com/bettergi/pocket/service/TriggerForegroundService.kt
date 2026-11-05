package com.bettergi.pocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import com.bettergi.pocket.MainActivity
import com.bettergi.pocket.R
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipFeature
import com.bettergi.pocket.genshin.GenshinLaunchMonitor
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.input.AccessibilityAutomationController
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.overlay.OverlayWindowController
import com.bettergi.pocket.pathing.PathingController
import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.pathing.executor.ScreenCaptureFrameSource
import com.bettergi.pocket.pathing.map.MapManager
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository
import com.bettergi.pocket.trigger.TriggerEngine

class TriggerForegroundService : Service() {
    private lateinit var settingsRepository: TriggerSettingsRepository
    private lateinit var captureController: ScreenCaptureController
    private lateinit var overlayController: OverlayWindowController
    private lateinit var genshinLauncher: GenshinLauncher
    private lateinit var genshinLaunchMonitor: GenshinLaunchMonitor
    private lateinit var engine: TriggerEngine
    private lateinit var pathingController: PathingController

    @Volatile
    private var requestingCapturePermission = false

    @Volatile
    private var shutDown = false

    private val settingsListener: (TriggerSettings) -> Unit = { settings ->
        if (settings.screenShareEnabled) {
            if (!captureController.isRunning()) {
                requestCapturePermission()
            }
            engine.start()
        } else {
            stopScreenShare()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = TriggerSettingsRepository(applicationContext)
        captureController = ScreenCaptureController(applicationContext) {
            if (settingsRepository.get().screenShareEnabled) {
                settingsRepository.setScreenShareEnabled(false)
                Toast.makeText(
                    applicationContext,
                    "屏幕共享已停止，可能被其他录制应用占用",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        genshinLauncher = GenshinLauncher(applicationContext)
        overlayController = OverlayWindowController(
            applicationContext,
            settingsRepository,
            genshinLauncher = genshinLauncher,
            onExit = {
                val stop = Intent(this, TriggerForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                startService(stop)
            },
        )
        genshinLaunchMonitor = GenshinLaunchMonitor(
            settingsRepository = settingsRepository,
            launcher = genshinLauncher,
            isGenshinInForeground = { InputAccessibilityService.isGenshinInForeground() },
            canAutoLaunch = { !requestingCapturePermission && !shutDown },
        )
        val recognitionAssets = RecognitionAssets(applicationContext.assets)
        engine = TriggerEngine(
            settingsRepository = settingsRepository,
            captureController = captureController,
            features = listOf(
                AutoPickFeature(),
                AutoSkipFeature(recognitionAssets, overlayController),
            ),
            actionController = AccessibilityAutomationController(overlayController),
        )
        pathingController = PathingController(
            frames = ScreenCaptureFrameSource(captureController),
            loadLayout = {
                val assets = MapManager.assets ?: error("地图资源未初始化")
                PathingLayout.load(assets)
            },
            genshinInForeground = { InputAccessibilityService.isGenshinInForeground() },
            log = { message -> overlayController.log(message) },
            onReadoutChanged = { running -> overlayController.setPathingRunning(running) },
            onStatus = { text -> overlayController.setPathingStatus(text) },
        )
        overlayController.onPathingToggle = { pathingController.toggleReadout() }
        settingsRepository.addListener(settingsListener)
        genshinLaunchMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground(sharing = captureController.isRunning())
                overlayController.show()
                InputAccessibilityService.promptIfDisconnected(applicationContext)
            }
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
            ACTION_CAPTURE_RESULT -> {
                requestingCapturePermission = false
                if (!settingsRepository.get().screenShareEnabled) {
                    captureController.stop()
                    startInForeground(sharing = false)
                    return START_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                if (resultData != null) {
                    startInForeground(sharing = true)
                    captureController.start(resultCode, resultData)
                    engine.start()
                    InputAccessibilityService.ensureEnabled(applicationContext)
                } else {
                    settingsRepository.setScreenShareEnabled(false)
                }
            }
            ACTION_CAPTURE_DENIED -> {
                requestingCapturePermission = false
                settingsRepository.setScreenShareEnabled(false)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        settingsRepository.removeListener(settingsListener)
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        if (shutDown) return
        shutDown = true
        InputAccessibilityService.cancelRecoverCheck()
        genshinLaunchMonitor.stop()
        if (::pathingController.isInitialized) {
            pathingController.stop()
        }
        engine.release()
        captureController.stop()
        overlayController.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopScreenShare() {
        requestingCapturePermission = false
        if (::pathingController.isInitialized) {
            pathingController.stop()
        }
        engine.stop()
        captureController.stop()
        if (!shutDown) {
            startInForeground(sharing = false)
        }
    }

    private fun requestCapturePermission() {
        if (requestingCapturePermission) return
        requestingCapturePermission = true
        val intent = Intent(this, CapturePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun startInForeground(sharing: Boolean) {
        createNotificationChannelIfNeeded()
        val notification = buildNotification(sharing)
        if (Build.VERSION.SDK_INT >= 29) {
            val serviceType =
                if (sharing) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(sharing: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (sharing) "正在共享屏幕" else "点悬浮球可开启共享屏幕"
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("更好的原神")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("更好的原神")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BetterGIPocket",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.bettergi.pocket.action.START"
        const val ACTION_STOP = "com.bettergi.pocket.action.STOP"
        const val ACTION_CAPTURE_RESULT = "com.bettergi.pocket.action.CAPTURE_RESULT"
        const val ACTION_CAPTURE_DENIED = "com.bettergi.pocket.action.CAPTURE_DENIED"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "bettergi_pocket_trigger"
        private const val NOTIFICATION_ID = 1001
    }
}

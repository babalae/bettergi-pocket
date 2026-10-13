package com.bettergi.pocket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bettergi.pocket.analytics.BaiduAnalytics
import com.bettergi.pocket.service.TriggerForegroundService

class MainActivity : AppCompatActivity() {
    private var permissionUiShown = false
    private var launchReady = false

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchReady = true
        BaiduAnalytics.start()
        continueLaunch()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !launchReady) return
        if (Settings.canDrawOverlays(this)) {
            launchOverlayAndExit()
        } else if (!permissionUiShown) {
            showOverlayPermissionUi()
        }
    }

    private fun continueLaunch() {
        if (Settings.canDrawOverlays(this)) {
            launchOverlayAndExit()
            return
        }
        showOverlayPermissionUi()
    }

    private fun showOverlayPermissionUi() {
        if (permissionUiShown) return
        permissionUiShown = true
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_request_overlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun launchOverlayAndExit() {
        if (isFinishing) return
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = TriggerForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        finish()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

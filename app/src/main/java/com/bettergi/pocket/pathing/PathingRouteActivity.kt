package com.bettergi.pocket.pathing

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bettergi.pocket.pathing.settings.PathingRouteStore
import com.bettergi.pocket.service.TriggerForegroundService

/**
 * 悬浮窗不能直接弹出系统文件选择器，用这个透明 Activity 做跳板。
 */
class PathingRouteActivity : AppCompatActivity() {
    private val openAny = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            persist(uri)
            if (intent.getBooleanExtra(EXTRA_FOLDER, false)) {
                PathingRouteStore(this).saveTree(uri, "文件夹")
            } else {
                PathingRouteStore(this).saveFile(uri, displayName(uri) ?: "路线")
            }
            notifyService()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = intent.getBooleanExtra(EXTRA_FOLDER, false)
        val picker = if (folder) {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        openAny.launch(picker)
    }

    private fun persist(uri: android.net.Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun notifyService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, TriggerForegroundService::class.java).apply {
                action = TriggerForegroundService.ACTION_PATHING_ROUTE
            },
        )
    }

    private fun displayName(uri: android.net.Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
    }
}

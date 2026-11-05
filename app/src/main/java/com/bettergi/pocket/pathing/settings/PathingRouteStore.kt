package com.bettergi.pocket.pathing.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 记住用户选中的路线文件或文件夹，并在开始时读出 JSON。
 */
class PathingRouteStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun label(): String = prefs.getString(KEY_LABEL, null) ?: "未选择路线"

    fun saveFile(uri: Uri, displayName: String) {
        persist(uri)
        prefs.edit()
            .putString(KEY_URI, uri.toString())
            .putBoolean(KEY_TREE, false)
            .putString(KEY_LABEL, displayName)
            .apply()
    }

    fun saveTree(uri: Uri, displayName: String) {
        persist(uri)
        prefs.edit()
            .putString(KEY_URI, uri.toString())
            .putBoolean(KEY_TREE, true)
            .putString(KEY_LABEL, displayName)
            .apply()
    }

    fun readRoutes(): List<Pair<String, String>> {
        val uri = prefs.getString(KEY_URI, null)?.let(Uri::parse) ?: return emptyList()
        return if (prefs.getBoolean(KEY_TREE, false)) readTree(uri) else {
            val name = label()
            val text = readText(uri) ?: return emptyList()
            listOf(name to text)
        }
    }

    private fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

    private fun readTree(tree: Uri): List<Pair<String, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val found = ArrayList<Pair<String, Uri>>()
        appContext.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (!name.endsWith(".json", ignoreCase = true)) continue
                val id = cursor.getString(idIndex) ?: continue
                found += name to DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        return found.sortedBy { it.first }.mapNotNull { (name, uri) ->
            readText(uri)?.let { name to it }
        }
    }

    private fun readText(uri: Uri): String? {
        return appContext.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        }
    }

    companion object {
        private const val PREFS_NAME = "pathing_routes"
        private const val KEY_URI = "uri"
        private const val KEY_TREE = "tree"
        private const val KEY_LABEL = "label"
    }
}

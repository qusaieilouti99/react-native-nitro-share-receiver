// file: android/src/main/java/com/margelo/nitro/nitrosharereceiver/ShareSingleton.kt
package com.margelo.nitro.nitrosharereceiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private data class Listener(val id: String, val callback: (event: ShareEvent) -> Unit)

object ShareSingleton {
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var cachedItems: ShareEventData? = null
    private var isAppActive = false

    fun onAppActive() {
        isAppActive = true
        cachedItems?.let { data ->
            if (data.items.isNotEmpty()) {
                val event = ShareEvent(ShareEventType.INITIAL_SHARED_ITEMS, data)
                listeners.forEach { it.callback(event) }
                cachedItems = null
            }
        }
    }

    fun onAppInactive() {
        isAppActive = false
    }

    fun addListener(callback: (event: ShareEvent) -> Unit): () -> Unit {
        val id = UUID.randomUUID().toString()
        listeners.add(Listener(id, callback))

        cachedItems?.let { data ->
            if (data.items.isNotEmpty()) {
                val event = ShareEvent(ShareEventType.INITIAL_SHARED_ITEMS, data)
                callback(event)
                cachedItems = null
            }
        }

        return {
            listeners.removeAll { it.id == id }
        }
    }

    fun handleIntent(intent: Intent, context: Context) {
        val sharedItems = mutableListOf<SharedItem>()
        when (intent.action) {
            Intent.ACTION_SEND -> processSingleItem(intent, context)?.let { sharedItems.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> sharedItems.addAll(processMultipleItems(intent, context))
        }

        if (sharedItems.isNotEmpty()) {
            val eventData = ShareEventData(items = sharedItems.toTypedArray(), totalCount = sharedItems.size.toDouble())
            if (isAppActive && listeners.isNotEmpty()) {
                val event = ShareEvent(ShareEventType.SHARED_ITEMS, eventData)
                listeners.forEach { it.callback(event) }
            } else {
                cachedItems = eventData
            }
        }
    }

    private fun processSingleItem(intent: Intent, context: Context): SharedItem? {
        val type = intent.type ?: return null
        val sourceApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) intent.getParcelableExtra(Intent.EXTRA_REFERRER)?.host else null

        if (intent.hasExtra(Intent.EXTRA_TEXT)) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                SharedItem(
                    type = if (type.startsWith("text/")) ShareItemType.TEXT else ShareItemType.URL,
                    content = it,
                    title = intent.getStringExtra(Intent.EXTRA_SUBJECT),
                    description = null,
                    url = it,
                    filePath = null,
                    mimeType = type,
                    fileSize = null,
                    timestamp = System.currentTimeMillis().toDouble(),
                    sourceApp = sourceApp
                )
            }
        }

        return intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { processUri(it, type, context, sourceApp) }
    }

    private fun processMultipleItems(intent: Intent, context: Context): List<SharedItem> {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return emptyList()
        val type = intent.type ?: "*/*"
        val sourceApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) intent.getParcelableExtra(Intent.EXTRA_REFERRER)?.host else null
        return uris.mapNotNull { processUri(it, type, context, sourceApp) }
    }

    private fun processUri(uri: Uri, mimeType: String, context: Context, sourceApp: String?): SharedItem? {
        val contentResolver = context.contentResolver
        var displayName: String? = null
        var size: Long? = null

        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it != -1 }?.let { i -> displayName = it.getString(i) }
                it.getColumnIndex(OpenableColumns.SIZE).takeIf { it != -1 && !it.isNull(it) }?.let { i -> size = it.getLong(i) }
            }
        }

        val tempFile = File(context.cacheDir, displayName ?: UUID.randomUUID().toString())
        try {
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        val itemType = when {
            mimeType.startsWith("image/") -> ShareItemType.IMAGE
            mimeType.startsWith("video/") -> ShareItemType.VIDEO
            mimeType.startsWith("audio/") -> ShareItemType.AUDIO
            else -> ShareItemType.FILE
        }

        return SharedItem(
            type = itemType,
            content = null,
            title = displayName,
            description = null,
            url = null,
            filePath = tempFile.absolutePath,
            mimeType = mimeType,
            fileSize = size?.toDouble(),
            timestamp = System.currentTimeMillis().toDouble(),
            sourceApp = sourceApp
        )
    }
}

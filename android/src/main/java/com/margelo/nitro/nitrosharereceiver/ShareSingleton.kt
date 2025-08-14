package com.margelo.nitro.nitrosharereceiver

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private data class Listener(val id: String, val callback: (event: ShareEvent) -> Unit)

/**
 * A singleton class responsible for processing incoming share intents, extracting rich metadata,
 * and dispatching the data to JavaScript listeners. It robustly handles cases where the
 * app is launched from a share ("cold start") or when a share is received while the app
 * is already running ("warm start").
 */
object ShareSingleton {
    private const val TAG = "ShareSingleton"
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var cachedEventData: ShareEventData? = null
    private var hasSentInitialItems = false

    /**
     * Registers a callback from JavaScript to listen for share events.
     * This also triggers an immediate dispatch of any cached items.
     */
    fun addListener(callback: (event: ShareEvent) -> Unit): () -> Unit {
        val id = UUID.randomUUID().toString()
        listeners.add(Listener(id, callback))
        Log.d(TAG, "Listener added. Total listeners: ${listeners.size}")

        // If there are cached items from a cold start, dispatch them now.
        cachedEventData?.let { data ->
            Log.d(TAG, "Found cached items. Dispatching to new listener.")
            val eventType = if (!hasSentInitialItems) ShareEventType.INITIAL_SHARED_ITEMS else ShareEventType.SHARED_ITEMS
            val event = ShareEvent(eventType, data)
            callback(event)
            cachedEventData = null // Clear cache after dispatching
            if (!hasSentInitialItems) hasSentInitialItems = true
        }

        return {
            listeners.removeAll { it.id == id }
            Log.d(TAG, "Listener removed. Total listeners: ${listeners.size}")
        }
    }

    /**
     * The main entry point for processing a share Intent. This is called by MainActivity.
     * It launches a background coroutine to handle all file I/O and metadata extraction.
     */
    fun handleIntent(intent: Intent, context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            val sharedItems = mutableListOf<SharedItem>()
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    Log.d(TAG, "Processing ACTION_SEND intent.")
                    processItem(intent, context)?.let { sharedItems.add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    Log.d(TAG, "Processing ACTION_SEND_MULTIPLE intent.")
                    sharedItems.addAll(processMultipleItems(intent, context))
                }
            }

            if (sharedItems.isNotEmpty()) {
                Log.d(TAG, "Successfully processed ${sharedItems.size} items.")
                val eventData = ShareEventData(items = sharedItems.toTypedArray(), totalCount = sharedItems.size.toDouble())
                dispatchOrCache(eventData)
            } else {
                Log.w(TAG, "Could not process any items from the intent.")
            }
        }
    }

    private fun dispatchOrCache(data: ShareEventData) {
        // If listeners are already present (app is active), dispatch immediately.
        if (listeners.isNotEmpty()) {
            val eventType = if (!hasSentInitialItems) ShareEventType.INITIAL_SHARED_ITEMS else ShareEventType.SHARED_ITEMS
            val event = ShareEvent(eventType, data)
            Log.d(TAG, "Listeners are active. Dispatching '${eventType.name}' event.")
            listeners.forEach { it.callback(event) }
            if (!hasSentInitialItems) hasSentInitialItems = true
        } else {
            // Otherwise, cache the data until a listener is added.
            Log.d(TAG, "No active listeners. Caching items.")
            cachedEventData = data
        }
    }

    private fun processItem(intent: Intent, context: Context): SharedItem? {
        val type = intent.type ?: return null
        val sourceApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)?.host else null

        if (intent.hasExtra(Intent.EXTRA_TEXT) && !intent.hasExtra(Intent.EXTRA_STREAM)) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                SharedItem(
                    type = if (type.startsWith("text/")) ShareItemType.TEXT else ShareItemType.URL,
                    content = it, title = intent.getStringExtra(Intent.EXTRA_SUBJECT), description = null, url = it,
                    filePath = null, mimeType = type, fileSize = null, timestamp = System.currentTimeMillis().toDouble(),
                    sourceApp = sourceApp, thumbnailPath = null, duration = null, width = null, height = null
                )
            }
        }

        return intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
            processUri(it, context, sourceApp)
        }
    }

    private fun processMultipleItems(intent: Intent, context: Context): List<SharedItem> {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return emptyList()
        val sourceApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)?.host else null
        return uris.mapNotNull { processUri(it, context, sourceApp) }
    }

    private fun processUri(uri: Uri, context: Context, sourceApp: String?): SharedItem? {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        var displayName: String? = null
        var size: Long? = null

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it != -1 }?.let { i -> displayName = cursor.getString(i) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it != -1 && !cursor.isNull(it) }?.let { i -> size = cursor.getLong(i) }
            }
        }

        val tempFile = File(context.cacheDir, displayName ?: UUID.randomUUID().toString())
        try {
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file from URI.", e); return null
        }

        val itemType = when {
            mimeType.startsWith("image/") -> ShareItemType.IMAGE
            mimeType.startsWith("video/") -> ShareItemType.VIDEO
            mimeType.startsWith("audio/") -> ShareItemType.AUDIO
            else -> ShareItemType.FILE
        }

        var duration: Double? = null
        var width: Double? = null
        var height: Double? = null
        var thumbnailPath: String? = null

        if (itemType == ShareItemType.VIDEO || itemType == ShareItemType.IMAGE) {
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, Uri.fromFile(tempFile))
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration = it / 1000.0 }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { width = it.toDouble() }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let { height = it.toDouble() }

                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) retriever.getFrameAtIndex(0) else retriever.getFrameAtTime(1000)
                    frame?.let { bmp ->
                        val thumbFile = File(context.cacheDir, "thumb_${tempFile.name}.jpg")
                        FileOutputStream(thumbFile).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                        thumbnailPath = thumbFile.absolutePath
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract media metadata.", e)
            }
        }

        return SharedItem(
            type = itemType, content = null, title = displayName, description = null, url = null,
            filePath = tempFile.absolutePath, mimeType = mimeType, fileSize = size?.toDouble(),
            timestamp = System.currentTimeMillis().toDouble(), sourceApp = sourceApp,
            thumbnailPath = thumbnailPath, duration = duration, width = width, height = height
        )
    }
}

package com.margelo.nitro.nitrosharereceiver

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private data class Listener(val id: String, val callback: (event: ShareEvent) -> Unit)

/**
 * Singleton responsible for processing share intents, extracting metadata,
 * caching initial items, and dispatching ShareEvent objects to registered listeners.
 *
 * Key guarantees:
 * - All callbacks are invoked on Dispatchers.Main (UI thread).
 * - Cached event state is guarded by cacheLock.
 * - Provides explicit cleanup helpers: removeListenerById, clearListeners, shutdown.
 */
object ShareSingleton {
    private const val TAG = "ShareSingleton"

    private val listeners = CopyOnWriteArrayList<Listener>()

    // Single cached event (cold start). Guarded by cacheLock.
    @Volatile
    private var cachedEventData: ShareEventData? = null
    @Volatile
    private var hasSentInitialItems = false
    private val cacheLock = Any()

    // Background scope for file I/O and metadata extraction
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Main scope for dispatching callbacks to JS / UI thread
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Register a JS callback. Returns a cleanup lambda that removes this listener.
     *
     * Important: This returns a Kotlin lambda that removes the listener by id.
     * The caller (Nitro bridge) may keep that lambda on Kotlin side or JS side;
     * be careful with returning it to JS in contexts that caused issues previously.
     */
    fun addListener(callback: (event: ShareEvent) -> Unit): () -> Unit {
        val id = UUID.randomUUID().toString()

        val safeCallback: (event: ShareEvent) -> Unit = { event ->
            try {
                callback(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error in listener callback: ${e.message}", e)
            }
        }

        listeners.add(Listener(id, safeCallback))
        Log.d(TAG, "Listener added. Total listeners: ${listeners.size}")

        // If there's cached data from a cold start, dispatch it to the new listener on Main.
        synchronized(cacheLock) {
            cachedEventData?.let { data ->
                val eventType = if (!hasSentInitialItems) ShareEventType.INITIAL_SHARED_ITEMS else ShareEventType.SHARED_ITEMS
                val event = ShareEvent(eventType, data)
                mainScope.launch {
                    try {
                        safeCallback(event)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error dispatching cached event: ${e.message}", e)
                    }
                }
                cachedEventData = null
                if (!hasSentInitialItems) hasSentInitialItems = true
            }
        }

        return {
            removeListenerById(id)
        }
    }

    private fun removeListenerById(id: String) {
        val removed = listeners.removeAll { it.id == id }
        if (removed) {
            Log.d(TAG, "Listener removed (id=$id). Total listeners: ${listeners.size}")
        }
    }

    fun clearListeners() {
        listeners.clear()
        Log.d(TAG, "All listeners cleared.")
    }

    /**
     * Cancel background processing and clear listeners/cache.
     * Call this from your app lifecycle (e.g., when module is being unloaded) if needed.
     */
    fun shutdown() {
        try {
            processingScope.cancel("ShareSingleton shutdown")
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling processingScope: ${e.message}")
        }
        try {
            mainScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling mainScope: ${e.message}")
        }
        clearListeners()
        synchronized(cacheLock) {
            cachedEventData = null
            hasSentInitialItems = false
        }
        Log.d(TAG, "ShareSingleton shutdown complete.")
    }

    /**
     * Main entry point for processing a share Intent. Called by MainActivity.
     * All heavy work runs on the IO dispatcher; final dispatch happens on Main.
     */
    fun handleIntent(intent: Intent, context: Context) {
        processingScope.launch {
            try {
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
                    else -> {
                        Log.w(TAG, "Unsupported intent action: ${intent.action}")
                        return@launch
                    }
                }

                if (sharedItems.isNotEmpty()) {
                    Log.d(TAG, "Successfully processed ${sharedItems.size} items.")
                    val eventData = ShareEventData(
                        items = sharedItems.toTypedArray(),
                        totalCount = sharedItems.size.toDouble()
                    )
                    dispatchOrCache(eventData)
                } else {
                    Log.w(TAG, "Could not process any items from the intent.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling intent: ${e.message}", e)
            }
        }
    }

    private suspend fun dispatchOrCache(data: ShareEventData) = withContext(Dispatchers.Main) {
        try {
            synchronized(cacheLock) {
                if (listeners.isNotEmpty()) {
                    val eventType = if (!hasSentInitialItems) ShareEventType.INITIAL_SHARED_ITEMS else ShareEventType.SHARED_ITEMS
                    val event = ShareEvent(eventType, data)
                    Log.d(TAG, "Listeners are active. Dispatching '${eventType.name}' event.")

                    // Copy listeners to avoid concurrent modifications while invoking.
                    val currentListeners = listeners.toList()
                    currentListeners.forEach { listener ->
                        try {
                            listener.callback(event)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error calling listener ${listener.id}: ${e.message}", e)
                        }
                    }

                    if (!hasSentInitialItems) hasSentInitialItems = true
                } else {
                    Log.d(TAG, "No active listeners. Caching items.")
                    cachedEventData = data
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching or caching data: ${e.message}", e)
        }
    }

    // --- Below: processing helpers (unchanged logic, safe I/O on IO dispatcher) ---

    private suspend fun processItem(intent: Intent, context: Context): SharedItem? = withContext(Dispatchers.IO) {
        try {
            val type = intent.type
            if (type.isNullOrBlank()) {
                Log.w(TAG, "Intent type is null or blank")
                return@withContext null
            }

            val sourceApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)?.host
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract source app: ${e.message}")
                    null
                }
            } else null

            // Handle text/URL content
            if (intent.hasExtra(Intent.EXTRA_TEXT) && !intent.hasExtra(Intent.EXTRA_STREAM)) {
                return@withContext intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    SharedItem(
                        type = if (type.startsWith("text/")) ShareItemType.TEXT else ShareItemType.URL,
                        content = text,
                        title = intent.getStringExtra(Intent.EXTRA_SUBJECT),
                        description = null,
                        url = text,
                        filePath = null,
                        mimeType = type,
                        fileSize = null,
                        timestamp = System.currentTimeMillis().toDouble(),
                        sourceApp = sourceApp,
                        thumbnailPath = null,
                        duration = null,
                        width = null,
                        height = null
                    )
                }
            }

            // Handle file/stream content
            return@withContext intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                processUri(uri, context, sourceApp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing single item: ${e.message}", e)
            null
        }
    }

    private suspend fun processMultipleItems(intent: Intent, context: Context): List<SharedItem> = withContext(Dispatchers.IO) {
        try {
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return@withContext emptyList()

            val sourceApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)?.host
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract source app: ${e.message}")
                    null
                }
            } else null

            return@withContext uris.mapNotNull { uri ->
                try {
                    processUri(uri, context, sourceApp)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing URI $uri: ${e.message}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing multiple items: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun processUri(uri: Uri, context: Context, sourceApp: String?): SharedItem? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            var displayName: String? = null
            var size: Long? = null

            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx != -1) displayName = cursor.getString(nameIdx)

                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                            size = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query content resolver for URI $uri: ${e.message}")
            }

            val tempFile = File(context.cacheDir, displayName ?: "shared_${UUID.randomUUID()}")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy file from URI $uri: ${e.message}", e)
                return@withContext null
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
                extractMediaMetadata(tempFile, context)?.let { metadata ->
                    duration = metadata.duration
                    width = metadata.width
                    height = metadata.height
                    thumbnailPath = metadata.thumbnailPath
                }
            }

            return@withContext SharedItem(
                type = itemType,
                content = null,
                title = displayName,
                description = null,
                url = null,
                filePath = tempFile.absolutePath,
                mimeType = mimeType,
                fileSize = size?.toDouble(),
                timestamp = System.currentTimeMillis().toDouble(),
                sourceApp = sourceApp,
                thumbnailPath = thumbnailPath,
                duration = duration,
                width = width,
                height = height
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing URI $uri: ${e.message}", e)
            null
        }
    }

    private data class MediaMetadata(
        val duration: Double?,
        val width: Double?,
        val height: Double?,
        val thumbnailPath: String?
    )

    private suspend fun extractMediaMetadata(file: File, context: Context): MediaMetadata? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            val duration = try {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { it / 1000.0 }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract duration: ${e.message}")
                null
            }

            val width = try {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.toDouble()
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract width: ${e.message}")
                null
            }

            val height = try {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.toDouble()
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract height: ${e.message}")
                null
            }

            val thumbnailPath = try {
                val frame: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    retriever.getFrameAtIndex(0)
                } else {
                    retriever.getFrameAtTime(1000)
                }

                frame?.let { bitmap ->
                    val thumbFile = File(context.cacheDir, "thumb_${file.name}.jpg")
                    FileOutputStream(thumbFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    thumbFile.absolutePath
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract thumbnail: ${e.message}")
                null
            }

            return@withContext MediaMetadata(duration, width, height, thumbnailPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract media metadata from ${file.absolutePath}: ${e.message}", e)
            null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever: ${e.message}")
            }
        }
    }
}

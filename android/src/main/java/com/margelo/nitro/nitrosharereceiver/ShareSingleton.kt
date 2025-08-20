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

    // Use SupervisorJob to prevent child failures from canceling the scope
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Registers a callback from JavaScript to listen for share events.
     * This also triggers an immediate dispatch of any cached items.
     */
    fun addListener(callback: (event: ShareEvent) -> Unit): () -> Unit {
        val id = UUID.randomUUID().toString()

        // Wrap callback to handle exceptions safely
        val safeCallback: (event: ShareEvent) -> Unit = { event ->
            try {
                callback(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error in listener callback: ${e.message}", e)
            }
        }

        listeners.add(Listener(id, safeCallback))
        Log.d(TAG, "Listener added. Total listeners: ${listeners.size}")

        // If there are cached items from a cold start, dispatch them now.
        cachedEventData?.let { data ->
            Log.d(TAG, "Found cached items. Dispatching to new listener.")
            try {
                val eventType = if (!hasSentInitialItems) ShareEventType.INITIAL_SHARED_ITEMS else ShareEventType.SHARED_ITEMS
                val event = ShareEvent(eventType, data)
                safeCallback(event)
                cachedEventData = null // Clear cache after dispatching
                if (!hasSentInitialItems) hasSentInitialItems = true
            } catch (e: Exception) {
                Log.e(TAG, "Error dispatching cached event: ${e.message}", e)
            }
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
        // Use the processing scope instead of GlobalScope
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
            // If listeners are already present (app is active), dispatch immediately.
            if (listeners.isNotEmpty()) {
                val eventType = if (!hasSentInitialItems) ShareEventType.INITIAL_SHARED_ITEMS else ShareEventType.SHARED_ITEMS
                val event = ShareEvent(eventType, data)
                Log.d(TAG, "Listeners are active. Dispatching '${eventType.name}' event.")

                // Create a copy of listeners to avoid concurrent modification
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
                // Otherwise, cache the data until a listener is added.
                Log.d(TAG, "No active listeners. Caching items.")
                cachedEventData = data
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching or caching data: ${e.message}", e)
        }
    }

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
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?: return@withContext emptyList()

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

            // Safely query content resolver
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it != -1 }?.let { i ->
                            displayName = cursor.getString(i)
                        }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it != -1 && !cursor.isNull(it) }?.let { i ->
                            size = cursor.getLong(i)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query content resolver for URI $uri: ${e.message}")
            }

            // Create temp file safely
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

            // Extract metadata safely for media files
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
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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

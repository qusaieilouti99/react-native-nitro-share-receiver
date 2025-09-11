package com.margelo.nitro.nitrosharereceiver

import android.util.Log
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class NitroShareReceiver : HybridNitroShareReceiverSpec() {
    companion object {
        private const val TAG = "NitroShareReceiver"
    }

    // Keep the cleanup returned by ShareSingleton.addListener inside Kotlin
    // so we do not return it to JS (avoids the destructor/JNI GC issue).
    private var cleanup: (() -> Unit)? = null

    override fun addShareListener(callback: (event: ShareEvent) -> Unit): Unit {
        try {
            Log.d(TAG, "Adding share listener (Kotlin side)")
            // Remove previous if any (defensive)
            cleanup?.invoke()
            cleanup = ShareSingleton.addListener { event ->
                try {
                    callback(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing JS callback: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding share listener: ${e.message}", e)
            cleanup = null
        }
    }

    override fun removeListener(): Unit {
        try {
            Log.d(TAG, "Removing share listener (Kotlin side)")
            cleanup?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Error removing share listener: ${e.message}", e)
        } finally {
            cleanup = null
        }
    }
}

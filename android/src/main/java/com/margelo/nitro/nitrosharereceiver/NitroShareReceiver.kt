package com.margelo.nitro.nitrosharereceiver

import android.util.Log
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class NitroShareReceiver : HybridNitroShareReceiverSpec() {
    companion object {
        private const val TAG = "NitroShareReceiver"
    }

    override fun addShareListener(callback: (event: ShareEvent) -> Unit): () -> Unit {
        return try {
            Log.d(TAG, "Adding share listener")
            ShareSingleton.addListener(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding share listener: ${e.message ?: "Unknown error"}")
            // Return a no-op cleanup function with explicit type
            return { }
        }
    }
}

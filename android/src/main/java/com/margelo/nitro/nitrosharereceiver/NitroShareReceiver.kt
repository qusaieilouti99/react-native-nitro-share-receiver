// file: android/src/main/java/com/margelo/nitro/nitrosharereceiver/NitroShareReceiver.kt
package com.margelo.nitro.nitrosharereceiver

import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.bridge.ReactApplicationContext

@DoNotStrip
class NitroShareReceiver(private val context: ReactApplicationContext) : HybridNitroShareReceiverSpec() {
    override fun addShareListener(callback: (event: ShareEvent) -> Unit): () -> Unit {
        // Delegate the call to the singleton
        return ShareSingleton.addListener(callback)
    }
}

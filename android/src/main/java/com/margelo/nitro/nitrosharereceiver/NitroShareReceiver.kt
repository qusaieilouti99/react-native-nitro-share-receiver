package com.margelo.nitro.nitrosharereceiver

import com.facebook.proguard.annotations.DoNotStrip


@DoNotStrip
class NitroShareReceiver : HybridNitroShareReceiverSpec() {
    override fun addShareListener(callback: (event: ShareEvent) -> Unit): () -> Unit {
        // Delegate the call to the singleton
        return ShareSingleton.addListener(callback)
    }
}

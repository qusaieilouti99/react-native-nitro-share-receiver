import Foundation
import NitroModules

class NitroShareReceiver: HybridNitroShareReceiverSpec {

  override init() {
    super.init()
    // Intentionally not calling `ShareSingleton.shared` here.
    // It will be created lazily when the JS layer adds a listener.
  }

  /// Exposes the `addListener` functionality to the JavaScript layer.
  func addShareListener(callback: @escaping (ShareEvent) -> Void) throws -> () -> Void {
    // This creates the ShareSingleton instance on-demand the first time this is called.
    return ShareSingleton.shared.addListener(callback: callback)
  }
}

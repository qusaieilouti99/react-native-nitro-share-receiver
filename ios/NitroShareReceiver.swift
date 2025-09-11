import Foundation
import NitroModules

class NitroShareReceiver: HybridNitroShareReceiverSpec {

  private var cleanup: (() -> Void)?

  override init() {
    super.init()
  }

  // Spec requires: func addShareListener(callback: @escaping (_ event: ShareEvent) -> Void) throws -> Void
  func addShareListener(callback: @escaping (ShareEvent) -> Void) throws -> Void {
    // Remove previous listener if set
    cleanup?()
    cleanup = nil

    // Wrap callback to ensure it runs on the main thread and is protected
    let safeCallback: (ShareEvent) -> Void = { event in
      DispatchQueue.main.async {
        callback(event)
      }
    }

    // Register with platform singleton and keep the cleanup closure locally
    // Assumes ShareSingleton.shared.addListener(callback:) returns (() -> Void)
    // (adapt if your ShareSingleton API differs)
    cleanup = ShareSingleton.shared.addListener(callback: safeCallback)
  }

  // Spec requires: func removeListener() throws -> Void
  func removeListener() throws -> Void {
    cleanup?()
    cleanup = nil
  }
}

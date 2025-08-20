import Foundation
import UIKit
import UniformTypeIdentifiers

/// A private struct to hold listener information.
private struct Listener {
  let id: String
  let callback: (ShareEvent) -> Void
}

/// A singleton class responsible for detecting and dispatching shared items to JavaScript listeners.
class ShareSingleton {
  /// The shared singleton instance.
  static let shared = ShareSingleton()

  private var listeners: [Listener] = []
  private let appGroupId: String?
  private let userDefaultsKey = "NitroSharedItems"
  private let queue = DispatchQueue(label: "com.nitro.shareSingleton.queue")

  /// State to ensure `INITIAL_SHARED_ITEMS` is only sent once per app lifecycle.
  private var hasSentInitialItems = false

  /// Track if Darwin observer is registered to prevent double registration
  private var isDarwinObserverRegistered = false

  // Darwin notification name
  private let darwinNotificationName = "com.yourapp.nitro.share.received"

  private init() {
    if let infoDict = Bundle.main.infoDictionary, let groupId = infoDict["appGroupId"] as? String {
      self.appGroupId = groupId
    } else {
      self.appGroupId = nil
    }

    // Register to be notified when the app becomes active.
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appDidBecomeActive),
      name: UIApplication.didBecomeActiveNotification,
      object: nil
    )

    // Register for Darwin notification
    registerForDarwinNotification()
  }

  /// Register for Darwin notification with proper memory management
  private func registerForDarwinNotification() {
    guard !isDarwinObserverRegistered else { return }

    let notificationCenter = CFNotificationCenterGetDarwinNotifyCenter()

    // Use a global callback function to avoid memory management issues
    CFNotificationCenterAddObserver(
      notificationCenter,
      nil, // No observer object needed
      { (center, observer, name, object, userInfo) in
        // Call the singleton method directly
        ShareSingleton.shared.handleDarwinNotification()
      },
      darwinNotificationName as CFString,
      nil,
      .deliverImmediately
    )

    isDarwinObserverRegistered = true
  }

  /// Handle Darwin notification
  private func handleDarwinNotification() {
    queue.async {
      self.checkForSharedData()
    }
  }

  /// Registers a callback from JavaScript to listen for share events.
  func addListener(callback: @escaping (ShareEvent) -> Void) -> () -> Void {
    let id = UUID().uuidString
    let newListener = Listener(id: id, callback: callback)

    // Thread-safe listener addition
    queue.async {
      DispatchQueue.main.sync {
        self.listeners.append(newListener)
      }

      // Check for data immediately upon listener registration
      self.checkForSharedData()
    }

    // Return cleanup function
    return { [weak self] in
      DispatchQueue.main.async {
        self?.listeners.removeAll { $0.id == id }
      }
    }
  }

  @objc private func appDidBecomeActive() {
    queue.async {
      self.checkForSharedData()
    }
  }

  /// Atomically reads, deletes, and processes shared data from UserDefaults.
  private func checkForSharedData() {
    guard let appGroupId = self.appGroupId else {
      print("⚠️ ShareSingleton: No app group ID configured")
      return
    }

    guard let userDefaults = UserDefaults(suiteName: appGroupId) else {
      print("⚠️ ShareSingleton: Cannot access UserDefaults with suite name: \(appGroupId)")
      return
    }

    // Synchronize to ensure we have the latest data
    userDefaults.synchronize()

    // Read and immediately remove data atomically
    guard let sharedData = userDefaults.array(forKey: self.userDefaultsKey) as? [[String: Any]],
          !sharedData.isEmpty else {
      return
    }

    // Remove data first to prevent duplicate processing
    userDefaults.removeObject(forKey: self.userDefaultsKey)
    userDefaults.synchronize()

    // Process the shared items
    let sharedItems = sharedData.compactMap { self.createSharedItem(from: $0) }
    guard !sharedItems.isEmpty else {
      print("⚠️ ShareSingleton: No valid shared items found")
      return
    }

    // Determine event type
    let eventType: ShareEventType = self.hasSentInitialItems ? .sharedItems : .initialSharedItems
    if !self.hasSentInitialItems {
      self.hasSentInitialItems = true
    }

    // Create event
    let eventData = ShareEventData(items: sharedItems, totalCount: Double(sharedItems.count))
    let event = ShareEvent(event: eventType, data: eventData)

    // Dispatch to listeners on main thread
    DispatchQueue.main.async {
      let currentListeners = self.listeners // Capture current listeners
      currentListeners.forEach { listener in
        do {
          listener.callback(event)
        } catch {
          print("❌ ShareSingleton: Error calling listener \(listener.id): \(error)")
        }
      }
    }
  }

  private func createSharedItem(from dict: [String: Any]) -> SharedItem? {
    guard let typeString = dict["type"] as? String,
          let type = ShareItemType(fromString: typeString) else {
      print("⚠️ ShareSingleton: Invalid or missing type in shared item: \(dict)")
      return nil
    }

    return SharedItem(
      type: type,
      content: dict["content"] as? String,
      title: dict["title"] as? String,
      description: dict["description"] as? String,
      url: dict["url"] as? String,
      filePath: dict["filePath"] as? String,
      mimeType: dict["mimeType"] as? String,
      fileSize: dict["fileSize"] as? Double,
      timestamp: dict["timestamp"] as? Double ?? Date().timeIntervalSince1970 * 1000,
      sourceApp: dict["sourceApp"] as? String,
      thumbnailPath: dict["thumbnailPath"] as? String,
      duration: dict["duration"] as? Double,
      width: dict["width"] as? Double,
      height: dict["height"] as? Double
    )
  }

  // No deinit needed - singleton lives for app lifetime
  // Darwin notifications will be cleaned up automatically when app terminates
}

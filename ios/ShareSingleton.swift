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

  // ADDITION: Darwin notification name
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

    // ADDITION: Register for Darwin notification
    registerForDarwinNotification()
  }

  /// ADDITION: Register for Darwin notification
  private func registerForDarwinNotification() {
    let notificationCenter = CFNotificationCenterGetDarwinNotifyCenter()
    let observer = Unmanaged.passUnretained(self).toOpaque()

    CFNotificationCenterAddObserver(
      notificationCenter,
      observer,
      { (center, observer, name, object, userInfo) in
        guard let observer = observer else { return }
        let myself = Unmanaged<ShareSingleton>.fromOpaque(observer).takeUnretainedValue()
        myself.handleDarwinNotification()
      },
      darwinNotificationName as CFString,
      nil,
      .deliverImmediately
    )
  }

  /// ADDITION: Handle Darwin notification
  private func handleDarwinNotification() {
    queue.async { self.checkForSharedData() }
  }

  /// Registers a callback from JavaScript to listen for share events.
  func addListener(callback: @escaping (ShareEvent) -> Void) -> () -> Void {
    let id = UUID().uuidString
    let newListener = Listener(id: id, callback: callback)

    DispatchQueue.main.async { self.listeners.append(newListener) }

    // Check for data immediately upon listener registration.
    queue.async { self.checkForSharedData() }

    return { [weak self] in
      DispatchQueue.main.async { self?.listeners.removeAll { $0.id == id } }
    }
  }

  @objc private func appDidBecomeActive() {
    queue.async { self.checkForSharedData() }
  }

  /// Atomically reads, deletes, and processes shared data from UserDefaults.
  private func checkForSharedData() {
    guard let appGroupId = self.appGroupId, let userDefaults = UserDefaults(suiteName: appGroupId) else { return }

    userDefaults.synchronize()

    guard let sharedData = userDefaults.array(forKey: self.userDefaultsKey) as? [[String: Any]], !sharedData.isEmpty else {
      return
    }

    userDefaults.removeObject(forKey: self.userDefaultsKey)
    userDefaults.synchronize()

    let sharedItems = sharedData.compactMap { self.createSharedItem(from: $0) }
    guard !sharedItems.isEmpty else { return }

    let eventType: ShareEventType = self.hasSentInitialItems ? .sharedItems : .initialSharedItems
    if !self.hasSentInitialItems { self.hasSentInitialItems = true }

    let eventData = ShareEventData(items: sharedItems, totalCount: Double(sharedItems.count))
    let event = ShareEvent(event: eventType, data: eventData)

    DispatchQueue.main.async {
      self.listeners.forEach { $0.callback(event) }
    }
  }

  private func createSharedItem(from dict: [String: Any]) -> SharedItem? {
    guard let typeString = dict["type"] as? String,
          let type = ShareItemType(fromString: typeString) else { return nil }

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

    deinit {
        let notificationCenter = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterRemoveObserver(
            notificationCenter,
            observer,
            CFNotificationName(darwinNotificationName as CFString),
            nil
        )
    }
}

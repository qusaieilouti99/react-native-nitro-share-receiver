import type { HybridObject } from 'react-native-nitro-modules';

/**
 * Defines the type of content being shared.
 */
export type ShareItemType =
  | 'text'
  | 'url'
  | 'image'
  | 'video'
  | 'audio'
  | 'file'
  | 'mixed';

/**
 * Defines the type of share event being emitted.
 * - `INITIAL_SHARED_ITEMS`: The first event sent after a listener is attached,
 *   containing any items shared while the app was closed.
 * - `SHARED_ITEMS`: Subsequent events for items shared while the app is already open.
 */
export type ShareEventType = 'INITIAL_SHARED_ITEMS' | 'SHARED_ITEMS';

/**
 * Represents a single shared item with its associated data and metadata.
 */
export interface SharedItem {
  /** The general type of the shared item. */
  type: ShareItemType;
  /** The content for 'text' or 'url' type shares. */
  content?: string;
  /** An optional title, often the original filename. */
  title?: string;
  /** An optional text description provided with the share. */
  description?: string;
  /** The URL for 'url' type shares. */
  url?: string;
  /** The absolute local file path to the shared file in the app's shared container. */
  filePath?: string;
  /** The standard MIME type of the file (e.g., 'image/jpeg', 'video/mp4'). */
  mimeType?: string;
  /** The size of the file in bytes. */
  fileSize?: number;
  /** The Unix timestamp (in milliseconds) of when the share was processed. */
  timestamp: number;
  /** The bundle identifier of the app that initiated the share (iOS only, if available). */
  sourceApp?: string;
  /** The local file path to a generated thumbnail for images and videos. */
  thumbnailPath?: string;
  /** The duration in seconds for video or audio files. */
  duration?: number;
  /** The width in pixels for images or videos. */
  width?: number;
  /** The height in pixels for images or videos. */
  height?: number;
}

/**
 * The data payload for a share event.
 */
export interface ShareEventData {
  /** An array of shared items. */
  items: SharedItem[];
  /** The total number of items in the 'items' array. */
  totalCount: number;
}

/**
 * The complete event object sent to the JavaScript listener.
 */
export interface ShareEvent {
  /** The type of the event. */
  event: ShareEventType;
  /** The data associated with the event. */
  data: ShareEventData;
}

/**
 * The native hybrid object that provides the share listening functionality.
 */
export interface NitroShareReceiver
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  /**
   * Registers a callback function to be invoked when new items are shared to the app.
   * @param callback The function to call with the share event.
   * @returns A function that, when called, will unsubscribe the listener.
   */
  addShareListener(callback: (event: ShareEvent) => void): () => void;
}

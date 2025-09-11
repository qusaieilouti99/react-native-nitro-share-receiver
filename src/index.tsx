import { NitroModules } from 'react-native-nitro-modules';
import type {
  NitroShareReceiver,
  ShareEvent,
} from './NitroShareReceiver.nitro';
import React from 'react';

const NitroShareReceiverHybridObject =
  NitroModules.createHybridObject<NitroShareReceiver>('NitroShareReceiver');

/**
 * Register a listener for share events.
 * - Registers or replaces the active listener (only one active listener is supported).
 * - The listener will immediately receive any cached INITIAL_SHARED_ITEMS (if present),
 *   and then receive SHARED_ITEMS for subsequent shares.
 * - NOTE: This function returns void. To stop listening call `removeListener()`.
 *
 * @param callback Function to handle share events
 */
export function addShareListener(callback: (event: ShareEvent) => void): void {
  NitroShareReceiverHybridObject.addShareListener(callback);
}

/**
 * Unregister the current listener.
 * - Clears the previously registered listener (if any).
 * - After calling this, the listener will no longer receive share events.
 */
export function removeListener(): void {
  NitroShareReceiverHybridObject.removeListener();
}

/**
 * React hook for listening to share events.
 * - Adds the listener on mount and removes it on unmount (or deps change).
 * - Make sure `callback` is stable (e.g. wrapped with useCallback) or include it in deps.
 *
 * @param callback Function to handle share events
 * @param deps Dependencies array for the callback
 */

export function useShareListener(
  callback: (event: ShareEvent) => void,
  deps: React.DependencyList = []
): void {
  React.useEffect(() => {
    addShareListener(callback);
    return () => {
      removeListener();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/**
 * Direct access to the hybrid object for advanced usage.
 */
export const ShareReceiver = NitroShareReceiverHybridObject;

// Re-export types for convenience
export type {
  ShareEvent,
  SharedItem,
  ShareEventData,
  ShareEventType,
  ShareItemType,
} from './NitroShareReceiver.nitro';

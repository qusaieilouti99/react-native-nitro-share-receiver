import { NitroModules } from 'react-native-nitro-modules';
import type { NitroShareReceiver, ShareEvent } from './NitroShareReceiver.nitro';
import React from 'react';

const NitroShareReceiverHybridObject =
  NitroModules.createHybridObject<NitroShareReceiver>('NitroShareReceiver');

/**
 * Add a listener for share events (both initial cached items and new shares)
 * @param callback Function to handle share events
 * @returns Function to unsubscribe the listener
 */
export function addShareListener(
  callback: (event: ShareEvent) => void
): () => void {
  return NitroShareReceiverHybridObject.addShareListener(callback);
}

/**
 * React hook for listening to share events
 * @param callback Function to handle share events
 * @param deps Dependencies array for the callback
 */
export function useShareListener(
  callback: (event: ShareEvent) => void,
  deps: React.DependencyList = []
): void {
  React.useEffect(() => {
    const unsubscribe = addShareListener(callback);
    return () => {
      unsubscribe()
    };
  }, deps);
}

/**
 * Direct access to the hybrid object for advanced usage
 */
export const ShareReceiver = NitroShareReceiverHybridObject;

// Re-export types for convenience
export type { ShareEvent, SharedItem, ShareEventData, ShareEventType, ShareItemType } from './NitroShareReceiver.nitro';

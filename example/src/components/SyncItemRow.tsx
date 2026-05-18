import React, { useCallback } from 'react';
import { Pressable, Text, View } from 'react-native';
import type { SyncItem } from '@gabriel-sisjr/react-native-sync-provider';

import styles from '../styles';

export interface SyncItemRowProps {
  item: SyncItem;
  onRemove: (id: string) => void;
}

function formatRelative(timestampMs: number, nowMs: number): string {
  const diff = Math.max(0, nowMs - timestampMs);
  if (diff < 1000) return 'just now';
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

/**
 * One row in the Queue screen list. Shows the request method, URL, priority,
 * a relative creation timestamp, and a destructive "Remove" button that
 * delegates to the parent component (which calls `removeItem(id)`).
 */
function SyncItemRowBase(props: SyncItemRowProps): React.ReactElement {
  const { item, onRemove } = props;
  const handleRemove = useCallback(() => {
    onRemove(item.id);
  }, [item.id, onRemove]);

  const now = Date.now();
  return (
    <View style={styles.itemRow}>
      <View style={styles.itemRowHeader}>
        <Text style={styles.itemMethod}>{item.method}</Text>
        <Text style={styles.itemUrl} numberOfLines={1} ellipsizeMode="middle">
          {item.url}
        </Text>
      </View>
      <View style={styles.itemMetaRow}>
        <Text style={styles.itemMeta}>
          priority: {item.priority ?? 'NORMAL'} ·{' '}
          {formatRelative(item.createdAt, now)}
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Remove item ${item.id}`}
          onPress={handleRemove}
          style={styles.removeButton}
        >
          <Text style={styles.removeButtonText}>Remove</Text>
        </Pressable>
      </View>
    </View>
  );
}

export const SyncItemRow = React.memo(SyncItemRowBase);

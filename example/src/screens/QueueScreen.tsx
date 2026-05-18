import React, { useCallback, useMemo } from 'react';
import {
  ActivityIndicator,
  Alert,
  Button,
  FlatList,
  Text,
  View,
} from 'react-native';
import type { ListRenderItem } from 'react-native';
import {
  HttpMethod,
  SyncError,
  SyncPriority,
  generateId,
  useOfflineQueue,
  useSyncConfig,
  useSyncQueue,
  useSyncStatus,
} from '@gabriel-sisjr/react-native-sync-provider';
import type { SyncItem } from '@gabriel-sisjr/react-native-sync-provider';

import { ConnectionBadge, SyncItemRow } from '../components';
import styles from '../styles';

const DEFAULT_ENDPOINT = 'https://httpbin.org/post';

function buildRandomBody(): string {
  return JSON.stringify({
    ts: Date.now(),
    nonce: Math.random().toString(36).slice(2),
    requestId: generateId(),
  });
}

export function QueueScreen(): React.ReactElement {
  const queue = useSyncQueue();
  const syncStatus = useSyncStatus();
  const { config } = useSyncConfig();
  // useOfflineQueue is the offline-aware composite hook; we expose a small
  // "Waiting for connection" hint when there are pending items but the
  // device is offline.
  const offline = useOfflineQueue();

  const endpoint = config?.defaultHeaders?.endpoint ?? DEFAULT_ENDPOINT;

  const handleEnqueue = useCallback(async () => {
    try {
      await queue.enqueue({
        method: HttpMethod.POST,
        url: endpoint,
        contentType: 'application/json',
        body: buildRandomBody(),
        priority: SyncPriority.NORMAL,
        metadata: { source: 'example-app', screen: 'queue' },
      });
    } catch (err) {
      const message =
        err instanceof SyncError
          ? `${err.code}: ${err.message}`
          : err instanceof Error
            ? err.message
            : 'Unknown error.';
      Alert.alert('Enqueue failed', message);
    }
  }, [endpoint, queue]);

  const handleFlush = useCallback(async () => {
    try {
      await syncStatus.flush();
    } catch (err) {
      const message =
        err instanceof SyncError
          ? `${err.code}: ${err.message}`
          : err instanceof Error
            ? err.message
            : 'Unknown error.';
      Alert.alert('Flush failed', message);
    }
  }, [syncStatus]);

  const handleClear = useCallback(() => {
    Alert.alert(
      'Clear queue',
      'Remove every pending item from the queue? This cannot be undone.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Clear',
          style: 'destructive',
          onPress: async () => {
            try {
              await queue.clearQueue();
            } catch (err) {
              const message =
                err instanceof Error ? err.message : 'Unknown error.';
              Alert.alert('Clear failed', message);
            }
          },
        },
      ]
    );
  }, [queue]);

  const handleRemove = useCallback(
    async (id: string) => {
      try {
        await queue.removeItem(id);
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Unknown error.';
        Alert.alert('Remove failed', message);
      }
    },
    [queue]
  );

  const renderItem = useCallback<ListRenderItem<SyncItem>>(
    ({ item }) => <SyncItemRow item={item} onRemove={handleRemove} />,
    [handleRemove]
  );

  const keyExtractor = useCallback((item: SyncItem) => item.id, []);

  const emptyComponent = useMemo(
    () => (
      <Text style={styles.emptyText}>
        No pending items — tap &apos;Add random item&apos; to enqueue one.
      </Text>
    ),
    []
  );

  return (
    <View style={styles.container}>
      <FlatList
        data={queue.items}
        keyExtractor={keyExtractor}
        renderItem={renderItem}
        ListEmptyComponent={emptyComponent}
        contentContainerStyle={styles.content}
        ListHeaderComponent={
          <View>
            <View style={styles.card}>
              <View style={styles.cardHeaderRow}>
                <Text style={styles.title}>Queue</Text>
                <ConnectionBadge />
              </View>
              <Text style={styles.subtitle}>
                Endpoint: <Text style={styles.mono}>{endpoint}</Text>
              </Text>

              <View style={styles.actionRow}>
                <View style={styles.actionButton}>
                  <Button
                    title="Add random item"
                    onPress={handleEnqueue}
                    color="#2196F3"
                  />
                </View>
                <View style={styles.actionButton}>
                  <Button
                    title={syncStatus.isSyncing ? 'Syncing...' : 'Force flush'}
                    onPress={handleFlush}
                    disabled={
                      syncStatus.isSyncing || !offline.connection.isOnline
                    }
                    color="#4CAF50"
                  />
                </View>
              </View>

              <View style={styles.summaryRow}>
                <Text style={styles.label}>Queue size: {queue.size}</Text>
                {queue.isLoading ? (
                  <ActivityIndicator size="small" color="#2196F3" />
                ) : null}
              </View>

              {offline.isWaitingForConnection ? (
                <View style={styles.savedToast}>
                  <Text style={styles.savedToastText}>
                    Waiting for connection — {offline.size} item(s) queued.
                  </Text>
                </View>
              ) : null}

              {queue.error !== null ? (
                <View style={styles.errorBanner}>
                  <Text style={styles.errorBannerText}>
                    {queue.error.code}: {queue.error.message}
                  </Text>
                </View>
              ) : null}
            </View>

            <Text style={styles.sectionTitle}>
              Pending items ({queue.items.length})
            </Text>
          </View>
        }
        ListFooterComponent={
          queue.items.length > 0 ? (
            <View style={styles.actionRow}>
              <View style={styles.actionButton}>
                <Button
                  title="Clear queue"
                  color="#f44336"
                  onPress={handleClear}
                />
              </View>
            </View>
          ) : undefined
        }
      />
    </View>
  );
}

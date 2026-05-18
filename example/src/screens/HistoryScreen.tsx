import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, FlatList, Text, View } from 'react-native';
import type { ListRenderItem } from 'react-native';
import {
  SyncEventType,
  clearSyncHistory,
  getSyncHistory,
  isNativeModuleAvailable,
  useSyncEvents,
  useSyncStatus,
} from '@gabriel-sisjr/react-native-sync-provider';
import type {
  SyncEvent,
  SyncResult,
} from '@gabriel-sisjr/react-native-sync-provider';

import { HistoryRow } from '../components';
import styles from '../styles';

const HISTORY_LIMIT = 50;

function formatTimestamp(timestampMs: number): string {
  try {
    return new Date(timestampMs).toLocaleString();
  } catch {
    return String(timestampMs);
  }
}

export function HistoryScreen(): React.ReactElement {
  const { lastResult } = useSyncStatus();
  const [history, setHistory] = useState<SyncResult[]>([]);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!isNativeModuleAvailable()) return;
    try {
      const next = await getSyncHistory(HISTORY_LIMIT);
      setHistory(next);
      setErrorMessage(null);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unknown error.';
      setErrorMessage(message);
    }
  }, []);

  // Initial fetch on mount.
  useEffect(() => {
    refresh().catch(() => undefined);
  }, [refresh]);

  // Reactive refresh on any flush-completion event.
  const handleEvent = useCallback(
    (event: SyncEvent) => {
      if (
        event.type === SyncEventType.SYNC_SUCCEEDED ||
        event.type === SyncEventType.SYNC_FAILED ||
        event.type === SyncEventType.BACKGROUND_SYNC_COMPLETED
      ) {
        refresh().catch(() => undefined);
      }
    },
    [refresh]
  );

  useSyncEvents({
    types: [
      SyncEventType.SYNC_SUCCEEDED,
      SyncEventType.SYNC_FAILED,
      SyncEventType.BACKGROUND_SYNC_COMPLETED,
    ],
    onEvent: handleEvent,
  });

  const handleClear = useCallback(() => {
    Alert.alert(
      'Clear history',
      'Remove every recorded sync result? The pending queue is not affected.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Clear',
          style: 'destructive',
          onPress: async () => {
            try {
              await clearSyncHistory();
              setHistory([]);
            } catch (err) {
              const message =
                err instanceof Error ? err.message : 'Unknown error.';
              Alert.alert('Clear failed', message);
            }
          },
        },
      ]
    );
  }, []);

  const renderItem = useCallback<ListRenderItem<SyncResult>>(
    ({ item }) => <HistoryRow result={item} />,
    []
  );

  const keyExtractor = useCallback(
    (item: SyncResult, index: number) =>
      `${item.startedAt}-${item.finishedAt}-${index}`,
    []
  );

  const headerText = useMemo(() => {
    if (lastResult === null) return 'No flush has run in this session yet.';
    return `Last flush: ${formatTimestamp(lastResult.finishedAt)} · ok ${lastResult.successCount} · failed ${lastResult.failureCount}`;
  }, [lastResult]);

  return (
    <View style={styles.container}>
      <FlatList
        data={history}
        keyExtractor={keyExtractor}
        renderItem={renderItem}
        contentContainerStyle={styles.content}
        ListEmptyComponent={
          <Text style={styles.emptyText}>
            No sync history yet — items enqueued from the Queue tab will appear
            here after a sync.
          </Text>
        }
        ListHeaderComponent={
          <View>
            <View style={styles.card}>
              <Text style={styles.title}>History</Text>
              <Text style={styles.subtitle}>{headerText}</Text>
              {errorMessage !== null ? (
                <View style={styles.errorBanner}>
                  <Text style={styles.errorBannerText}>{errorMessage}</Text>
                </View>
              ) : null}
            </View>
            <Text style={styles.sectionTitle}>
              Recent results ({history.length})
            </Text>
          </View>
        }
        ListFooterComponent={
          history.length > 0 ? (
            <View style={styles.actionRow}>
              <View style={styles.actionButton}>
                <Button
                  title="Clear history"
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

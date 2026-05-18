import React, { useCallback, useMemo, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import type { SyncResult } from '@gabriel-sisjr/react-native-sync-provider';

import styles from '../styles';

export interface HistoryRowProps {
  result: SyncResult;
}

function formatTimestamp(timestampMs: number): string {
  try {
    return new Date(timestampMs).toLocaleString();
  } catch {
    return String(timestampMs);
  }
}

/**
 * One row in the History screen list. Shows the flush timestamp plus the
 * success/failure counts. Tapping the row toggles an expandable section that
 * lists each `{ itemId: errorCode }` pair from `SyncResult.errors`.
 *
 * Uses a `useState<boolean>` for expansion (no animation libraries) so the
 * example app stays dependency-light.
 */
function HistoryRowBase({ result }: HistoryRowProps): React.ReactElement {
  const [expanded, setExpanded] = useState(false);

  const toggle = useCallback(() => {
    setExpanded((prev) => !prev);
  }, []);

  const errorEntries = useMemo(
    () => Object.entries(result.errors ?? {}),
    [result.errors]
  );

  return (
    <Pressable
      onPress={toggle}
      accessibilityRole="button"
      accessibilityLabel={
        expanded ? 'Collapse sync result details' : 'Expand sync result details'
      }
      style={styles.historyRow}
    >
      <View style={styles.historyHeader}>
        <Text style={styles.historyTimestamp}>
          {formatTimestamp(result.finishedAt)}
        </Text>
        <View style={styles.historyCountsRow}>
          <Text style={styles.historyCountSuccess}>
            ok {result.successCount}
          </Text>
          <Text style={styles.historyCountFailed}>
            failed {result.failureCount}
          </Text>
        </View>
      </View>
      {expanded && (
        <View style={styles.historyDetails}>
          {errorEntries.length === 0 ? (
            <Text style={styles.historyDetailEmpty}>
              No errors recorded for this flush cycle.
            </Text>
          ) : (
            errorEntries.map(([itemId, code]) => (
              <Text key={itemId} style={styles.historyDetailLine}>
                {itemId} · {code}
              </Text>
            ))
          )}
        </View>
      )}
    </Pressable>
  );
}

export const HistoryRow = React.memo(HistoryRowBase);

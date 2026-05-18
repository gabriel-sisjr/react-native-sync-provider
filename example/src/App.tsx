import React, { useMemo, useState } from 'react';
import { Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import {
  BackoffStrategy,
  SyncProvider,
  SyncStrategy,
  isNativeModuleAvailable,
} from '@gabriel-sisjr/react-native-sync-provider';
import type { SyncOptions } from '@gabriel-sisjr/react-native-sync-provider';

import { ConfigScreen } from './screens/ConfigScreen';
import { HistoryScreen } from './screens/HistoryScreen';
import { QueueScreen } from './screens/QueueScreen';
import styles from './styles';

type ScreenKey = 'queue' | 'history' | 'config';

type TabDefinition = {
  readonly key: ScreenKey;
  readonly label: string;
};

const TABS: readonly TabDefinition[] = [
  { key: 'queue', label: 'Queue' },
  { key: 'history', label: 'History' },
  { key: 'config', label: 'Config' },
];

const INITIAL_SYNC_OPTIONS: SyncOptions = {
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 5,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 1000,
    maxDelayMs: 60000,
    jitter: true,
    retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
  },
  batchSize: 10,
  requestTimeoutMs: 30000,
  maxQueueSize: 1000,
  persistQueue: true,
  defaultHeaders: { endpoint: 'https://httpbin.org/post' },
};

function NativeUnavailableNotice(): React.ReactElement {
  return (
    <View style={styles.centeredContainer}>
      <View style={[styles.card, styles.cardConstrained]}>
        <Text style={styles.title}>Native module unavailable</Text>
        <Text style={styles.subtitle}>
          The SyncProvider HybridObject could not be resolved. Make sure you ran
          `yarn nitrogen` before building the example app and that you are
          running on a real device or simulator.
        </Text>
      </View>
    </View>
  );
}

export default function App(): React.ReactElement {
  // Memoize the initial options so the Provider does not re-apply them on
  // every render (it re-applies whenever the reference changes).
  const initialOptions = useMemo(() => INITIAL_SYNC_OPTIONS, []);
  const nativeReady = isNativeModuleAvailable();
  const [currentScreen, setCurrentScreen] = useState<ScreenKey>('queue');

  if (!nativeReady) {
    return (
      <SafeAreaProvider>
        <NativeUnavailableNotice />
      </SafeAreaProvider>
    );
  }

  const tabBar = (
    <View style={styles.tabContainer}>
      {TABS.map((tab) => {
        const isActive = currentScreen === tab.key;
        return (
          <TouchableOpacity
            key={tab.key}
            style={[styles.tab, isActive && styles.activeTab]}
            onPress={() => setCurrentScreen(tab.key)}
          >
            <Text style={[styles.tabText, isActive && styles.activeTabText]}>
              {tab.label}
            </Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );

  return (
    <SafeAreaProvider>
      <SyncProvider options={initialOptions}>
        <View style={styles.container}>
          {tabBar}
          {currentScreen === 'queue' && <QueueScreen />}
          {currentScreen === 'history' && <HistoryScreen />}
          {currentScreen === 'config' && <ConfigScreen />}
        </View>
      </SyncProvider>
    </SafeAreaProvider>
  );
}

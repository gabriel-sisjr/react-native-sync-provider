import React, { useMemo } from 'react';
import { Text, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { NavigationContainer } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
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

type TabParamList = {
  Queue: undefined;
  History: undefined;
  Config: undefined;
};

const Tab = createBottomTabNavigator<TabParamList>();

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

  if (!nativeReady) {
    return (
      <SafeAreaProvider>
        <NativeUnavailableNotice />
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider>
      <SyncProvider options={initialOptions}>
        <NavigationContainer>
          <Tab.Navigator
            screenOptions={{
              headerShown: false,
              tabBarActiveTintColor: '#2196F3',
              tabBarInactiveTintColor: '#90A4AE',
            }}
          >
            <Tab.Screen
              name="Queue"
              component={QueueScreen}
              options={{ tabBarLabel: 'Queue' }}
            />
            <Tab.Screen
              name="History"
              component={HistoryScreen}
              options={{ tabBarLabel: 'History' }}
            />
            <Tab.Screen
              name="Config"
              component={ConfigScreen}
              options={{ tabBarLabel: 'Config' }}
            />
          </Tab.Navigator>
        </NavigationContainer>
      </SyncProvider>
    </SafeAreaProvider>
  );
}

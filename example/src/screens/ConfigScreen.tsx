import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Button,
  Pressable,
  ScrollView,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  BackoffStrategy,
  SyncStrategy,
  isBackgroundSyncEnabled,
  useAutoSync,
  useSyncConfig,
} from '@gabriel-sisjr/react-native-sync-provider';
import type {
  SyncOptions,
  RetryPolicy,
} from '@gabriel-sisjr/react-native-sync-provider';

import { ConnectionBadge } from '../components';
import styles from '../styles';

const DEFAULT_ENDPOINT = 'https://httpbin.org/post';

const DEFAULT_RETRY_POLICY: RetryPolicy = {
  maxAttempts: 5,
  backoff: BackoffStrategy.EXPONENTIAL,
  baseDelayMs: 1000,
  maxDelayMs: 60000,
  jitter: true,
  retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
};

const DEFAULT_CONFIG: SyncOptions = {
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: DEFAULT_RETRY_POLICY,
  batchSize: 10,
  requestTimeoutMs: 30000,
  maxQueueSize: 1000,
  persistQueue: true,
  defaultHeaders: { endpoint: DEFAULT_ENDPOINT },
};

interface FormState {
  endpoint: string;
  batchSize: string;
  requestTimeoutMs: string;
  maxQueueSize: string;
  persistQueue: boolean;
  syncStrategy: SyncStrategy;
  retryMaxAttempts: string;
  retryBackoff: BackoffStrategy;
  retryBaseDelayMs: string;
  retryMaxDelayMs: string;
  retryJitter: boolean;
}

function configToForm(config: SyncOptions): FormState {
  const endpoint = config.defaultHeaders?.endpoint ?? DEFAULT_ENDPOINT;
  return {
    endpoint,
    batchSize: String(config.batchSize ?? 10),
    requestTimeoutMs: String(config.requestTimeoutMs ?? 30000),
    maxQueueSize: String(config.maxQueueSize ?? 1000),
    persistQueue: config.persistQueue ?? true,
    syncStrategy: config.strategy,
    retryMaxAttempts: String(config.retryPolicy.maxAttempts),
    retryBackoff: config.retryPolicy.backoff,
    retryBaseDelayMs: String(config.retryPolicy.baseDelayMs),
    retryMaxDelayMs: String(config.retryPolicy.maxDelayMs),
    retryJitter: config.retryPolicy.jitter,
  };
}

function parsePositiveInt(value: string, fallback: number): number {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 0) return fallback;
  return parsed;
}

function formToConfig(form: FormState, base: SyncOptions): SyncOptions {
  return {
    strategy: form.syncStrategy,
    retryPolicy: {
      ...base.retryPolicy,
      maxAttempts: parsePositiveInt(
        form.retryMaxAttempts,
        base.retryPolicy.maxAttempts
      ),
      backoff: form.retryBackoff,
      baseDelayMs: parsePositiveInt(
        form.retryBaseDelayMs,
        base.retryPolicy.baseDelayMs
      ),
      maxDelayMs: parsePositiveInt(
        form.retryMaxDelayMs,
        base.retryPolicy.maxDelayMs
      ),
      jitter: form.retryJitter,
    },
    batchSize: parsePositiveInt(form.batchSize, base.batchSize ?? 10),
    requestTimeoutMs: parsePositiveInt(
      form.requestTimeoutMs,
      base.requestTimeoutMs ?? 30000
    ),
    maxQueueSize: parsePositiveInt(
      form.maxQueueSize,
      base.maxQueueSize ?? 1000
    ),
    persistQueue: form.persistQueue,
    defaultHeaders: {
      ...(base.defaultHeaders ?? {}),
      endpoint: form.endpoint || DEFAULT_ENDPOINT,
    },
  };
}

interface SegmentedProps<T extends string> {
  options: readonly T[];
  value: T;
  onChange: (next: T) => void;
}

function Segmented<T extends string>({
  options,
  value,
  onChange,
}: SegmentedProps<T>): React.ReactElement {
  return (
    <View style={styles.segmentedRow}>
      {options.map((opt) => {
        const selected = opt === value;
        return (
          <Pressable
            key={opt}
            onPress={() => onChange(opt)}
            style={[styles.segment, selected ? styles.segmentSelected : null]}
            accessibilityRole="button"
            accessibilityState={{ selected }}
          >
            <Text
              style={[
                styles.segmentText,
                selected ? styles.segmentTextSelected : null,
              ]}
            >
              {opt}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const SYNC_STRATEGY_OPTIONS = [
  SyncStrategy.AUTOMATIC,
  SyncStrategy.MANUAL,
  SyncStrategy.OPPORTUNISTIC,
] as const;

const BACKOFF_OPTIONS = [
  BackoffStrategy.LINEAR,
  BackoffStrategy.EXPONENTIAL,
  BackoffStrategy.FIBONACCI,
] as const;

export function ConfigScreen(): React.ReactElement {
  const { config, isLoading, error, setConfig } = useSyncConfig();
  const [form, setForm] = useState<FormState>(() =>
    configToForm(DEFAULT_CONFIG)
  );
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [autoSyncEnabled, setAutoSyncEnabled] = useState(false);
  const [backgroundEnabled, setBackgroundEnabled] = useState(false);

  // useAutoSync orchestrates periodic flushes + flush-on-reconnect. Driven
  // by the autoSyncEnabled local toggle so the user can opt in.
  useAutoSync({
    enabled: autoSyncEnabled,
    flushOnReconnect: true,
    flushOnMetered: false,
    flushOnAppForeground: true,
    intervalMs: 60_000,
  });

  // Mirror the live native config into the form when it changes.
  useEffect(() => {
    if (config !== null) setForm(configToForm(config));
  }, [config]);

  // Probe background-sync registration state once on mount so the Switch
  // reflects the OS-level state rather than just the local Provider option.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const enabled = await isBackgroundSyncEnabled();
        if (!cancelled) setBackgroundEnabled(enabled);
      } catch {
        // ignore
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const updateField = useCallback(
    <K extends keyof FormState>(key: K, value: FormState[K]) => {
      setForm((prev) => ({ ...prev, [key]: value }));
    },
    []
  );

  const handleSave = useCallback(async () => {
    const base = config ?? DEFAULT_CONFIG;
    try {
      await setConfig(formToConfig(form, base));
      setSavedAt(Date.now());
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unknown error.';
      Alert.alert('Save failed', message);
    }
  }, [config, form, setConfig]);

  const handleBackgroundToggle = useCallback(async (next: boolean) => {
    setBackgroundEnabled(next);
    try {
      if (next) {
        const { enableBackgroundSync } =
          await import('@gabriel-sisjr/react-native-sync-provider');
        await enableBackgroundSync({
          minimumIntervalMs: 15 * 60 * 1000,
          requiresUnmeteredNetwork: true,
        });
      } else {
        const { disableBackgroundSync } =
          await import('@gabriel-sisjr/react-native-sync-provider');
        await disableBackgroundSync();
      }
    } catch (err) {
      setBackgroundEnabled(!next);
      const message = err instanceof Error ? err.message : 'Unknown error.';
      Alert.alert('Background sync toggle failed', message);
    }
  }, []);

  const savedRecently = useMemo(() => {
    if (savedAt === null) return false;
    return Date.now() - savedAt < 4000;
  }, [savedAt]);

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.card}>
          <Text style={styles.title}>Sync configuration</Text>
          <Text style={styles.subtitle}>
            Updates take effect on the next flush cycle.
          </Text>

          {isLoading ? (
            <ActivityIndicator
              size="small"
              color="#2196F3"
              style={styles.spinnerSpacing}
            />
          ) : null}

          {/* Endpoint */}
          <View style={styles.formField}>
            <Text style={styles.label}>Endpoint URL</Text>
            <TextInput
              style={styles.textInput}
              value={form.endpoint}
              onChangeText={(value) => updateField('endpoint', value)}
              autoCapitalize="none"
              autoCorrect={false}
              placeholder={DEFAULT_ENDPOINT}
              keyboardType="url"
            />
            <Text style={styles.helperText}>
              Persisted in defaultHeaders.endpoint and used by the Queue tab.
            </Text>
          </View>

          {/* Strategy */}
          <View style={styles.formField}>
            <Text style={styles.label}>Sync strategy</Text>
            <Segmented
              options={SYNC_STRATEGY_OPTIONS}
              value={form.syncStrategy}
              onChange={(next) => updateField('syncStrategy', next)}
            />
          </View>

          {/* Batch size + timeouts */}
          <View style={styles.formField}>
            <Text style={styles.label}>Batch size</Text>
            <TextInput
              style={styles.textInput}
              value={form.batchSize}
              onChangeText={(value) => updateField('batchSize', value)}
              keyboardType="number-pad"
            />
          </View>

          <View style={styles.formField}>
            <Text style={styles.label}>Request timeout (ms)</Text>
            <TextInput
              style={styles.textInput}
              value={form.requestTimeoutMs}
              onChangeText={(value) => updateField('requestTimeoutMs', value)}
              keyboardType="number-pad"
            />
          </View>

          <View style={styles.formField}>
            <Text style={styles.label}>Max queue size (0 = unlimited)</Text>
            <TextInput
              style={styles.textInput}
              value={form.maxQueueSize}
              onChangeText={(value) => updateField('maxQueueSize', value)}
              keyboardType="number-pad"
            />
          </View>

          <View style={[styles.formField, styles.switchRow]}>
            <Text style={styles.label}>Persist queue to disk</Text>
            <Switch
              value={form.persistQueue}
              onValueChange={(value) => updateField('persistQueue', value)}
            />
          </View>
        </View>

        {/* Retry policy */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>Retry policy</Text>

          <View style={styles.formField}>
            <Text style={styles.label}>Max attempts</Text>
            <TextInput
              style={styles.textInput}
              value={form.retryMaxAttempts}
              onChangeText={(value) => updateField('retryMaxAttempts', value)}
              keyboardType="number-pad"
            />
          </View>

          <View style={styles.formField}>
            <Text style={styles.label}>Backoff strategy</Text>
            <Segmented
              options={BACKOFF_OPTIONS}
              value={form.retryBackoff}
              onChange={(next) => updateField('retryBackoff', next)}
            />
          </View>

          <View style={styles.formField}>
            <Text style={styles.label}>Base delay (ms)</Text>
            <TextInput
              style={styles.textInput}
              value={form.retryBaseDelayMs}
              onChangeText={(value) => updateField('retryBaseDelayMs', value)}
              keyboardType="number-pad"
            />
          </View>

          <View style={styles.formField}>
            <Text style={styles.label}>Max delay (ms)</Text>
            <TextInput
              style={styles.textInput}
              value={form.retryMaxDelayMs}
              onChangeText={(value) => updateField('retryMaxDelayMs', value)}
              keyboardType="number-pad"
            />
          </View>

          <View style={[styles.formField, styles.switchRow]}>
            <Text style={styles.label}>Apply jitter</Text>
            <Switch
              value={form.retryJitter}
              onValueChange={(value) => updateField('retryJitter', value)}
            />
          </View>
        </View>

        {/* Background sync + auto sync */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>Background sync</Text>

          <View style={[styles.formField, styles.switchRow]}>
            <Text style={styles.label}>OS-level background sync</Text>
            <Switch
              value={backgroundEnabled}
              onValueChange={handleBackgroundToggle}
            />
          </View>
          <Text style={styles.helperText}>
            iOS uses BGTaskScheduler; Android uses WorkManager. Minimum interval
            is clamped to 15 minutes by the OS.
          </Text>

          <View style={[styles.formField, styles.switchRow]}>
            <Text style={styles.label}>useAutoSync orchestrator</Text>
            <Switch
              value={autoSyncEnabled}
              onValueChange={setAutoSyncEnabled}
            />
          </View>
          <Text style={styles.helperText}>
            Polls every 60s and flushes on reconnect or foreground entry while
            this screen is mounted.
          </Text>
        </View>

        {/* Save + status */}
        <View style={styles.card}>
          <Button
            title="Save configuration"
            onPress={handleSave}
            color="#2196F3"
          />
          {savedRecently ? (
            <View style={styles.savedToast}>
              <Text style={styles.savedToastText}>Configuration saved.</Text>
            </View>
          ) : null}
          {error !== null ? (
            <View style={styles.errorBanner}>
              <Text style={styles.errorBannerText}>
                {error.code}: {error.message}
              </Text>
            </View>
          ) : null}
          <View style={styles.badgeSpacing}>
            <ConnectionBadge />
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

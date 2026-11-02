import { renderHook } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';
import React from 'react';

import * as nativeEntry from '../index';
import * as webEntry from '../index.web';
import { BackoffStrategy, SyncStrategy } from '../types/enums';
import type { SyncOptions } from '../types/sync';

const OPTIONS: SyncOptions = {
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 3,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 100,
    maxDelayMs: 5_000,
    jitter: false,
    retryOnStatusCodes: [500],
  },
};

type AnyFunction = (...args: unknown[]) => unknown;

const web = webEntry as unknown as Record<string, unknown>;
const native = nativeEntry as unknown as Record<string, unknown>;

/** Web exports that do real work or sit in cleanup paths, so must not throw. */
const NOT_THROWING = new Set([
  'SyncError',
  'SyncProvider',
  'addSyncEventListener',
  'cancelDrain',
  'generateId',
  'isNativeModuleAvailable',
  'removeSyncEventListener',
]);

const isHook = (name: string) => /^use[A-Z]/.test(name);

/**
 * Derived from the exports, so a new facade function is covered the moment it
 * is added, and a silent no-op twin fails here rather than in production.
 */
const FACADE_FUNCTIONS = Object.keys(webEntry).filter(
  (name) =>
    typeof web[name] === 'function' && !isHook(name) && !NOT_THROWING.has(name)
);

describe('web facade', () => {
  it('covers the whole facade', () => {
    expect(FACADE_FUNCTIONS).toEqual(
      expect.arrayContaining(['enqueue', 'flush', 'drainUntilEmpty'])
    );
    expect(FACADE_FUNCTIONS).toHaveLength(20);
  });

  it.each(FACADE_FUNCTIONS)('%s rejects as unavailable', async (name) => {
    await expect((web[name] as AnyFunction)()).rejects.toMatchObject({
      name: 'SyncError',
      code: webEntry.SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
      message: `react-native-sync-provider.${name} is not available on web.`,
    });
  });

  it('drainUntilEmpty rejects with options too', async () => {
    await expect(
      webEntry.drainUntilEmpty({ maxIterations: 1, timeoutMs: 1 })
    ).rejects.toMatchObject({
      code: webEntry.SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
    });
  });

  it('cancelDrain is a no-op, because it sits in cleanup paths', () => {
    expect(webEntry.cancelDrain()).toBeUndefined();
  });

  it('listener helpers are no-ops', async () => {
    await expect(webEntry.addSyncEventListener(() => undefined)).resolves.toBe(
      ''
    );
    await expect(
      webEntry.removeSyncEventListener('subscription')
    ).resolves.toBeUndefined();
  });

  it('reports the native module as unavailable', () => {
    expect(webEntry.isNativeModuleAvailable()).toBe(false);
  });

  it('still generates ids', () => {
    expect(webEntry.generateId()).toEqual(expect.any(String));
  });
});

describe('web hooks', () => {
  const RESULT_HOOKS = [
    'useConnection',
    'useOfflineQueue',
    'useSyncConfig',
    'useSyncContext',
    'useSyncQueue',
    'useSyncSnapshot',
    'useSyncStatus',
  ];

  it.each(RESULT_HOOKS)('%s returns the same keys as native', (name) => {
    const keysOf = (hook: unknown) =>
      Object.keys(
        renderHook(() => (hook as () => object)()).result.current
      ).sort();

    expect(keysOf(web[name])).toEqual(keysOf(native[name]));
  });

  it('useSyncSnapshot is inert', () => {
    const { result } = renderHook(() => webEntry.useSyncSnapshot());

    expect(result.current).toMatchObject({
      size: 0,
      items: [],
      isSyncing: false,
      isPaused: false,
      isWaitingForConnection: false,
      error: null,
    });
  });

  it('useSyncEvents and useAutoSync render without doing anything', () => {
    expect(() =>
      renderHook(() => {
        webEntry.useSyncEvents({ onEvent: () => undefined });
        webEntry.useAutoSync({ enabled: true, intervalMs: 1_000 });
      })
    ).not.toThrow();
  });

  it.each([
    ['useSyncQueue', 'enqueue'],
    ['useSyncQueue', 'clearQueue'],
    ['useSyncStatus', 'flush'],
    ['useOfflineQueue', 'flush'],
    ['useSyncConfig', 'setConfig'],
  ])('%s().%s rejects as unavailable', async (hook, action) => {
    const { result } = renderHook(() =>
      (web[hook] as () => Record<string, AnyFunction>)()
    );

    await expect(result.current[action]?.()).rejects.toMatchObject({
      code: webEntry.SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
    });
  });
});

describe('web context', () => {
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <webEntry.SyncProvider options={OPTIONS}>{children}</webEntry.SyncProvider>
  );

  it('useSyncContext reports no provider outside <SyncProvider>', () => {
    const { result } = renderHook(() => webEntry.useSyncContext());

    expect(result.current).toEqual({ config: undefined, hasProvider: false });
  });

  it('useSyncContext reports the provider inside <SyncProvider>', () => {
    const { result } = renderHook(() => webEntry.useSyncContext(), {
      wrapper,
    });

    expect(result.current).toEqual({ config: undefined, hasProvider: true });
  });

  it('keeps the context value stable across re-renders', () => {
    const { result, rerender } = renderHook(() => webEntry.useSyncContext(), {
      wrapper,
    });
    const first = result.current;

    rerender({});

    expect(result.current).toBe(first);
  });

  it('makes no native call', () => {
    const mock = (
      globalThis as unknown as { __syncMock: { configureSync: jest.Mock } }
    ).__syncMock;

    renderHook(() => webEntry.useSyncContext(), { wrapper });

    expect(mock.configureSync).not.toHaveBeenCalled();
  });
});

describe('web bundle', () => {
  it('does not load Nitro', () => {
    let nitroLoaded = false;

    jest.isolateModules(() => {
      jest.doMock('react-native-nitro-modules', () => {
        nitroLoaded = true;
        return {};
      });
      require('../index.web');
    });
    jest.dontMock('react-native-nitro-modules');

    expect(nitroLoaded).toBe(false);
  });
});

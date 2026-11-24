/**
 * Minimal Jest setup for @gabriel-sisjr/react-native-sync-provider.
 *
 * Mocks the React Native surface used by the JS facade and hooks (AppState,
 * Platform) and the Nitro bridge. The Nitro bridge mock exposes a per-process
 * fake `SyncProvider` HybridObject through `globalThis.__syncMock`, so each
 * test can stub specific method behaviors without redeclaring the mock.
 */

import type { ConnectionState } from '../types/connection';
import type { SyncOptions } from '../types/sync';

const mockAppStateListeners: Array<(state: string) => void> = [];

jest.mock('react-native', () => ({
  Platform: {
    OS: 'ios',
    Version: 17,
    select: jest.fn((obj: Record<string, unknown>) =>
      obj.ios !== undefined ? obj.ios : obj.default
    ),
  },
  AppState: {
    currentState: 'active',
    addEventListener: jest.fn(
      (event: string, handler: (state: string) => void) => {
        if (event === 'change') {
          mockAppStateListeners.push(handler);
        }
        return {
          remove: jest.fn(() => {
            const idx = mockAppStateListeners.indexOf(handler);
            if (idx >= 0) mockAppStateListeners.splice(idx, 1);
          }),
        };
      }
    ),
  },
  NativeModules: {},
  NativeEventEmitter: jest.fn().mockImplementation(() => ({
    addListener: jest.fn(() => ({ remove: jest.fn() })),
    removeAllListeners: jest.fn(),
  })),
}));

(
  global as unknown as { simulateAppStateChange: (s: string) => void }
).simulateAppStateChange = (next: string) => {
  for (const listener of mockAppStateListeners) listener(next);
};

(
  global as unknown as { getAppStateListenerCount: () => number }
).getAppStateListenerCount = () => mockAppStateListeners.length;

type SyncEventChannel = 'sync-event';

/** The callback kinds a test can drive through `__invokeCallback`. */
type MockCallbackKind =
  | 'retryDecider'
  | 'headerResolver'
  | 'beforeDispatch'
  | 'afterDispatch';

/** The native-facing shape of every installed callback, as in the spec. */
type MockNativeCallback = (requestId: string, context: unknown) => void;

interface MockHybrid {
  enqueue: jest.Mock;
  enqueueBatch: jest.Mock;
  removeItem: jest.Mock;
  clearQueue: jest.Mock;
  getQueueSize: jest.Mock;
  getPendingItems: jest.Mock;
  listDeadLetter: jest.Mock;
  replayDeadLetter: jest.Mock;
  discardDeadLetter: jest.Mock;
  flush: jest.Mock;
  pauseSync: jest.Mock;
  resumeSync: jest.Mock;
  isSyncing: jest.Mock;
  configureSync: jest.Mock;
  getSyncConfig: jest.Mock;
  getLastSyncResult: jest.Mock;
  getSyncHistory: jest.Mock;
  clearSyncHistory: jest.Mock;
  getConnectionStatus: jest.Mock;
  enableBackgroundSync: jest.Mock;
  disableBackgroundSync: jest.Mock;
  isBackgroundSyncEnabled: jest.Mock;
  addListener: jest.Mock;
  removeListener: jest.Mock;
  setRetryDecider: jest.Mock;
  clearRetryDecider: jest.Mock;
  isRetryDeciderInstalled: jest.Mock;
  setHeaderResolver: jest.Mock;
  clearHeaderResolver: jest.Mock;
  setBeforeDispatch: jest.Mock;
  clearBeforeDispatch: jest.Mock;
  setAfterDispatch: jest.Mock;
  clearAfterDispatch: jest.Mock;
  answerCallback: jest.Mock;
  /** Test-only — emit a sync event to every active listener on the channel. */
  __emit: (event: unknown) => void;
  /** Test-only — drop every registered listener. */
  __resetListeners: () => void;
  /**
   * Test-only — call the callback installed for `kind` as native does, with a
   * fresh request id, and resolve with the first answer `answerCallback` gets
   * for that id. Rejects when nothing of that kind is installed. Never await
   * it when no answer will come: the promise then stays pending.
   */
  __invokeCallback: (
    kind: MockCallbackKind,
    context: unknown
  ) => Promise<unknown>;
  /** Test-only — the native-facing callback installed for `kind`, if any. */
  __installedCallback: (
    kind: MockCallbackKind
  ) => MockNativeCallback | undefined;
}

function createMockHybrid(): MockHybrid {
  const listeners = new Map<string, (event: unknown) => void>();
  let nextSubscriptionId = 1;

  // Like native: one install per kind (a new `set…` replaces it), and one
  // pending request per id, registered before the callback is called.
  const installed = new Map<MockCallbackKind, MockNativeCallback>();
  const pendingAnswers = new Map<string, (answer: unknown) => void>();
  let nextRequestId = 1;
  const install = (kind: MockCallbackKind) =>
    jest.fn(async (callback: MockNativeCallback) => {
      installed.set(kind, callback);
    });
  const uninstall = (kind: MockCallbackKind) =>
    jest.fn(async () => {
      installed.delete(kind);
    });

  const sentinelLastResult = {
    startedAt: 0,
    finishedAt: 0,
    successCount: 0,
    failureCount: 0,
    succeededIds: [],
    failedIds: [],
    errors: {},
  };

  // Typed, so the mock cannot drift from the public type. Like native, it
  // leaves out the options that were never set: `getSyncConfig()` returns
  // `minNetworkQuality`, the health-check fields and `callbackTimeoutMs` only
  // when set.
  const defaultConfig: SyncOptions = {
    strategy: 'AUTOMATIC',
    retryPolicy: {
      maxAttempts: 3,
      backoff: 'EXPONENTIAL',
      baseDelayMs: 100,
      maxDelayMs: 5_000,
      jitter: false,
      retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
    },
  };

  // An unmetered Wi-Fi link with no `healthCheckUrl`: link signals only.
  const defaultConnection: ConnectionState = {
    status: 'CONNECTED',
    type: 'WIFI',
    networkQuality: 'GOOD',
    isInternetReachable: true,
    isExpensive: false,
  };

  const hybrid: MockHybrid = {
    enqueue: jest.fn(async () => 'mock-ulid-1'),
    enqueueBatch: jest.fn(async (items: unknown[]) =>
      items.map((_, i) => `mock-ulid-${i + 1}`)
    ),
    removeItem: jest.fn(async () => true),
    clearQueue: jest.fn(async () => undefined),
    getQueueSize: jest.fn(async () => 0),
    getPendingItems: jest.fn(async () => []),
    listDeadLetter: jest.fn(async () => []),
    replayDeadLetter: jest.fn(async () => undefined),
    discardDeadLetter: jest.fn(async () => undefined),
    flush: jest.fn(async () => ({
      startedAt: Date.now(),
      finishedAt: Date.now(),
      successCount: 0,
      failureCount: 0,
      succeededIds: [],
      failedIds: [],
      errors: {},
    })),
    pauseSync: jest.fn(async () => undefined),
    resumeSync: jest.fn(async () => undefined),
    isSyncing: jest.fn(async () => false),
    configureSync: jest.fn(async () => undefined),
    getSyncConfig: jest.fn(async () => defaultConfig),
    getLastSyncResult: jest.fn(async () => sentinelLastResult),
    getSyncHistory: jest.fn(async () => []),
    clearSyncHistory: jest.fn(async () => undefined),
    getConnectionStatus: jest.fn(async () => ({ ...defaultConnection })),
    enableBackgroundSync: jest.fn(async () => undefined),
    disableBackgroundSync: jest.fn(async () => undefined),
    isBackgroundSyncEnabled: jest.fn(async () => false),
    addListener: jest.fn(
      async (channel: SyncEventChannel, cb: (event: unknown) => void) => {
        const id = `mock-sub-${nextSubscriptionId++}-${channel}`;
        listeners.set(id, cb);
        return id;
      }
    ),
    removeListener: jest.fn(async (_channel: SyncEventChannel, id: string) => {
      listeners.delete(id);
    }),
    setRetryDecider: install('retryDecider'),
    clearRetryDecider: uninstall('retryDecider'),
    isRetryDeciderInstalled: jest.fn(async () => installed.has('retryDecider')),
    setHeaderResolver: install('headerResolver'),
    clearHeaderResolver: uninstall('headerResolver'),
    setBeforeDispatch: install('beforeDispatch'),
    clearBeforeDispatch: uninstall('beforeDispatch'),
    setAfterDispatch: install('afterDispatch'),
    clearAfterDispatch: uninstall('afterDispatch'),
    // Synchronous, like the spec: complete the pending request and return. An
    // unknown, late or second answer is a silent no-op.
    answerCallback: jest.fn((requestId: string, answer: unknown) => {
      const resolve = pendingAnswers.get(requestId);
      pendingAnswers.delete(requestId);
      resolve?.(answer);
    }),
    __emit: (event: unknown) => {
      for (const cb of listeners.values()) cb(event);
    },
    __resetListeners: () => {
      listeners.clear();
      nextSubscriptionId = 1;
    },
    __invokeCallback: (kind: MockCallbackKind, context: unknown) => {
      const callback = installed.get(kind);
      if (callback === undefined) {
        return Promise.reject(new Error(`No ${kind} is installed.`));
      }
      const requestId = `${kind}:${nextRequestId++}`;
      return new Promise((resolve) => {
        pendingAnswers.set(requestId, resolve);
        callback(requestId, context);
      });
    },
    __installedCallback: (kind: MockCallbackKind) => installed.get(kind),
  };

  return hybrid;
}

let mockCurrentHybrid: MockHybrid | null = createMockHybrid();
let mockHybridAvailable = true;

(global as unknown as { __syncMock: MockHybrid | null }).__syncMock =
  mockCurrentHybrid;
(
  global as unknown as { __setSyncHybridAvailable: (v: boolean) => void }
).__setSyncHybridAvailable = (v: boolean) => {
  mockHybridAvailable = v;
  mockCurrentHybrid = v ? createMockHybrid() : null;
  (global as unknown as { __syncMock: MockHybrid | null }).__syncMock =
    mockCurrentHybrid;
};

jest.mock('react-native-nitro-modules', () => ({
  __esModule: true,
  NitroModules: {
    createHybridObject: jest.fn(() => {
      if (!mockHybridAvailable) {
        throw new Error('Nitro module unavailable in this test');
      }
      return mockCurrentHybrid;
    }),
  },
}));

beforeEach(() => {
  // Reset hybrid availability + mock state, but PRESERVE the singleton
  // reference so the facade's cached `createHybridObject` keeps pointing
  // at the same mock instance across tests. Just reset the listener
  // registry and rotate fresh jest.fn() implementations into the stable
  // hybrid object.
  mockHybridAvailable = true;
  if (mockCurrentHybrid) {
    const fresh = createMockHybrid();
    Object.assign(mockCurrentHybrid, fresh);
  }
  (global as unknown as { __syncMock: MockHybrid | null }).__syncMock =
    mockCurrentHybrid;
  mockAppStateListeners.length = 0;
});

afterEach(() => {
  jest.clearAllMocks();
});

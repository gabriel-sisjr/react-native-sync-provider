/**
 * Minimal Jest setup for @gabriel-sisjr/react-native-sync-provider.
 *
 * Mocks the React Native surface used by the JS facade and hooks (AppState,
 * Platform) and the Nitro bridge. The Nitro bridge mock exposes a per-process
 * fake `SyncProvider` HybridObject through `globalThis.__syncMock`, so each
 * test can stub specific method behaviors without redeclaring the mock.
 */

export {};

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

interface MockHybrid {
  enqueue: jest.Mock;
  enqueueBatch: jest.Mock;
  removeItem: jest.Mock;
  clearQueue: jest.Mock;
  getQueueSize: jest.Mock;
  getPendingItems: jest.Mock;
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
  /** Test-only — emit a sync event to every active listener on the channel. */
  __emit: (event: unknown) => void;
  /** Test-only — drop every registered listener. */
  __resetListeners: () => void;
}

function createMockHybrid(): MockHybrid {
  const listeners = new Map<string, (event: unknown) => void>();
  let nextSubscriptionId = 1;

  const sentinelLastResult = {
    startedAt: 0,
    finishedAt: 0,
    successCount: 0,
    failureCount: 0,
    succeededIds: [],
    failedIds: [],
    errors: {},
  };

  const defaultConfig = {
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

  const hybrid: MockHybrid = {
    enqueue: jest.fn(async () => 'mock-ulid-1'),
    enqueueBatch: jest.fn(async (items: unknown[]) =>
      items.map((_, i) => `mock-ulid-${i + 1}`)
    ),
    removeItem: jest.fn(async () => true),
    clearQueue: jest.fn(async () => undefined),
    getQueueSize: jest.fn(async () => 0),
    getPendingItems: jest.fn(async () => []),
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
    getConnectionStatus: jest.fn(async () => ({
      status: 'CONNECTED',
      type: 'WIFI',
      isInternetReachable: true,
      isExpensive: false,
    })),
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
    __emit: (event: unknown) => {
      for (const cb of listeners.values()) cb(event);
    },
    __resetListeners: () => {
      listeners.clear();
      nextSubscriptionId = 1;
    },
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

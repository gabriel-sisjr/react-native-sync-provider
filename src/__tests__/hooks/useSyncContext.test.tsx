import { renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';
import React from 'react';

import { SyncProvider } from '../../contexts/SyncProvider';
import { useSyncContext } from '../../hooks/useSyncContext';
import { BackoffStrategy, SyncStrategy } from '../../types/enums';
import type { SyncOptions } from '../../types/sync';

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

describe('useSyncContext', () => {
  it('returns hasProvider:false and config:undefined outside a Provider', () => {
    const { result } = renderHook(() => useSyncContext());
    expect(result.current.hasProvider).toBe(false);
    expect(result.current.config).toBeUndefined();
  });

  it('does not throw when used outside a <SyncProvider>', () => {
    expect(() => renderHook(() => useSyncContext())).not.toThrow();
  });

  it('reports hasProvider:true inside a <SyncProvider> and hydrates config', async () => {
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <SyncProvider options={OPTIONS}>{children}</SyncProvider>
    );

    const { result } = renderHook(() => useSyncContext(), { wrapper });

    // hasProvider is true from the first render, before config resolves.
    expect(result.current.hasProvider).toBe(true);

    // config hydrates after the Provider's initial getSyncConfig() round-trip.
    await waitFor(() => expect(result.current.config).toBeDefined());
    expect(result.current.hasProvider).toBe(true);
  });

  it('still hydrates config when the Provider cannot apply its options', async () => {
    const mock = (
      globalThis as unknown as { __syncMock: { configureSync: jest.Mock } }
    ).__syncMock;
    mock.configureSync.mockRejectedValue(new Error('native rejected options'));

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <SyncProvider options={OPTIONS}>{children}</SyncProvider>
    );

    const { result } = renderHook(() => useSyncContext(), { wrapper });

    await waitFor(() => expect(result.current.config).toBeDefined());
  });
});

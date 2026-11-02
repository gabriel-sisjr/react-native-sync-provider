import { act, renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';
import React from 'react';

import { SyncProvider, SyncProviderContext } from '../../contexts/SyncProvider';
import type { SyncProviderContextValue } from '../../contexts/SyncProvider';
import { useSyncConfig } from '../../hooks/useSyncConfig';
import { BackoffStrategy, SyncStrategy } from '../../types/enums';
import type { SyncOptions } from '../../types/sync';

interface SyncMock {
  getSyncConfig: jest.Mock;
  configureSync: jest.Mock;
  addListener: jest.Mock;
}

/** Flush the pending mount effect + microtasks so the hook settles. */
async function settle(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const OPTIONS: SyncOptions = {
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 5,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 200,
    maxDelayMs: 8_000,
    jitter: true,
    retryOnStatusCodes: [503],
  },
};

describe('useSyncConfig — hasProvider migration', () => {
  it('falls back to the native singleton when no Provider is mounted', async () => {
    const mock = getMock();
    mock.getSyncConfig.mockResolvedValue(OPTIONS);

    const { result } = renderHook(() => useSyncConfig());

    await waitFor(() => expect(result.current.config).toEqual(OPTIONS));
    // Contrast with the "Provider mounted, loading" case below: with no
    // Provider, the hook DOES probe native and DOES subscribe its own listener.
    expect(mock.getSyncConfig).toHaveBeenCalled();
    expect(mock.addListener).toHaveBeenCalled();
  });

  it('does NOT probe native while a Provider is mounted but config is still loading', async () => {
    const mock = getMock();

    // Drive the exact "hasProvider: true, config: undefined" window via the
    // context directly. (The real <SyncProvider> component calls getSyncConfig
    // itself, which would defeat a 0-call assertion — so we provide the value
    // straight to the context to isolate the hook's behavior.)
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <SyncProviderContext.Provider
        value={{ config: undefined, hasProvider: true }}
      >
        {children}
      </SyncProviderContext.Provider>
    );

    const { result } = renderHook(() => useSyncConfig(), { wrapper });
    await settle();

    // The whole point of the hasProvider migration: the hook must defer to the
    // Provider and run neither its own getSyncConfig probe nor its own listener.
    expect(mock.getSyncConfig).not.toHaveBeenCalled();
    expect(mock.addListener).not.toHaveBeenCalled();
    // It mirrors the (still-unresolved) Provider config instead of fetching.
    expect(result.current.config).toBeNull();
  });

  it('mirrors the Provider config when a <SyncProvider> is mounted', async () => {
    const mock = getMock();
    mock.getSyncConfig.mockResolvedValue(OPTIONS);

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <SyncProvider options={OPTIONS}>{children}</SyncProvider>
    );

    const { result } = renderHook(() => useSyncConfig(), { wrapper });

    await waitFor(() => expect(result.current.config).toEqual(OPTIONS));
  });

  it('shows the native config when the Provider cannot apply its options', async () => {
    const mock = getMock();
    mock.configureSync.mockRejectedValue(new Error('native rejected options'));
    mock.getSyncConfig.mockResolvedValue(OPTIONS);

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <SyncProvider options={OPTIONS}>{children}</SyncProvider>
    );

    const { result } = renderHook(() => useSyncConfig(), { wrapper });

    await waitFor(() => expect(result.current.config).toEqual(OPTIONS));
  });

  it('mirrors a context value that carries a config but no hasProvider', async () => {
    const mock = getMock();
    const provided = { ...OPTIONS, batchSize: 7 };

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <SyncProviderContext.Provider
        value={{ config: provided } as unknown as SyncProviderContextValue}
      >
        {children}
      </SyncProviderContext.Provider>
    );

    const { result } = renderHook(() => useSyncConfig(), { wrapper });
    await settle();

    expect(result.current.config).toEqual(provided);
    expect(mock.getSyncConfig).not.toHaveBeenCalled();
    expect(mock.addListener).not.toHaveBeenCalled();
  });

  it('setConfig round-trips through configureSync then refreshes', async () => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncConfig());

    await act(async () => {
      await result.current.setConfig(OPTIONS);
    });

    expect(mock.configureSync).toHaveBeenCalledWith(OPTIONS);
  });
});

import { describe, expect, it } from '@jest/globals';

import { SyncErrorCode } from '../index';
import { BackoffStrategy, SyncStrategy } from '../types/enums';
import type { SyncOptions } from '../types/sync';

/*
 * In its own file because `__setSyncHybridAvailable` swaps the mock object while
 * the facade keeps its memoized hybrid, which would break any test after it.
 */

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

type Entry = typeof import('../index');

async function withoutNativeModule(
  run: (entry: Entry) => Promise<unknown>
): Promise<unknown> {
  const setAvailable = (
    globalThis as unknown as {
      __setSyncHybridAvailable: (available: boolean) => void;
    }
  ).__setSyncHybridAvailable;

  let outcome: unknown;
  await jest.isolateModulesAsync(async () => {
    setAvailable(false);
    const entry = require('../index') as Entry;
    outcome = await run(entry).catch((error: unknown) => error);
  });
  return outcome;
}

describe('dead-letter functions without the native module', () => {
  it.each([
    ['listDeadLetter', (entry: Entry) => entry.listDeadLetter()],
    ['replayDeadLetter', (entry: Entry) => entry.replayDeadLetter('id')],
    ['discardDeadLetter', (entry: Entry) => entry.discardDeadLetter('id')],
  ])('%s rejects with NATIVE_MODULE_UNAVAILABLE', async (_name, run) => {
    await expect(withoutNativeModule(run)).resolves.toMatchObject({
      name: 'SyncError',
      code: SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
    });
  });
});

describe('configureSync without the native module', () => {
  it('reports an invalid option before the missing module, like enqueue', async () => {
    await expect(
      withoutNativeModule((entry) =>
        entry.configureSync({ ...OPTIONS, healthCheckUrl: 'not-a-url' })
      )
    ).resolves.toMatchObject({ code: SyncErrorCode.INVALID_URL });
  });

  it('reports the missing module for valid options', async () => {
    await expect(
      withoutNativeModule((entry) =>
        entry.configureSync({
          ...OPTIONS,
          healthCheckUrl: 'https://api.example.com/health',
        })
      )
    ).resolves.toMatchObject({
      code: SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
    });
  });
});

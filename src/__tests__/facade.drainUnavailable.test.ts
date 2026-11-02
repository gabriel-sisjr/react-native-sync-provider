import { describe, expect, it } from '@jest/globals';

import { SyncErrorCode } from '../index';

/*
 * In its own file because `__setSyncHybridAvailable` swaps the mock object while
 * the facade keeps its memoized hybrid, which would break any test after it.
 */
describe('drainUntilEmpty without the native module', () => {
  it('rejects and does not leave the drain lock held', async () => {
    const setAvailable = (
      globalThis as unknown as {
        __setSyncHybridAvailable: (available: boolean) => void;
      }
    ).__setSyncHybridAvailable;

    let first: unknown;
    let second: unknown;
    await jest.isolateModulesAsync(async () => {
      setAvailable(false);
      const entry = require('../index') as typeof import('../index');
      first = await entry.drainUntilEmpty().catch((error) => error.code);
      second = await entry.drainUntilEmpty().catch((error) => error.code);
    });

    expect(first).toBe(SyncErrorCode.NATIVE_MODULE_UNAVAILABLE);
    expect(second).toBe(SyncErrorCode.NATIVE_MODULE_UNAVAILABLE);
  });
});

import { describe, expect, it } from '@jest/globals';

import { generateId } from '../../utils/idGenerator';

const UUID_V7_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

describe('generateId', () => {
  it('returns a UUID v7 formatted string', () => {
    expect(generateId()).toMatch(UUID_V7_REGEX);
  });

  it('returns unique ids on consecutive calls', () => {
    const set = new Set<string>();
    for (let i = 0; i < 100; i += 1) set.add(generateId());
    expect(set.size).toBe(100);
  });

  it('returns ids whose lexicographic order roughly tracks creation order', () => {
    const a = generateId();
    // small busy-wait so the v7 timestamp prefix advances at least 1ms
    const start = Date.now();
    while (Date.now() - start < 2) {
      // intentional spin
    }
    const b = generateId();
    expect(a < b).toBe(true);
  });
});

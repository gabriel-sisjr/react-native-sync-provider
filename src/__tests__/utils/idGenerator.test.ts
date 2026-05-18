import { describe, expect, it } from '@jest/globals';

import { generateId } from '../../utils/idGenerator';

const UUID_V4_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

describe('generateId', () => {
  it('returns a string', () => {
    expect(typeof generateId()).toBe('string');
  });

  it('returns a 36-char string', () => {
    expect(generateId()).toHaveLength(36);
  });

  it('returns a UUID v4 formatted string', () => {
    expect(generateId()).toMatch(UUID_V4_REGEX);
  });

  it('returns different ids on consecutive calls', () => {
    // Math.random()-based; this is a statistical check, not a uniqueness
    // proof. Collisions across two calls are astronomically unlikely.
    expect(generateId()).not.toBe(generateId());
  });
});

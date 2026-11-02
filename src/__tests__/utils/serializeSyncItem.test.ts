import { describe, expect, it } from '@jest/globals';

import { SyncError } from '../../errors/SyncError';
import { SyncErrorCode } from '../../errors/SyncErrorCode';
import { HttpMethod } from '../../types/enums';
import type { SyncItem } from '../../types/sync';
import { deserializeSyncItem } from '../../utils/deserializeSyncItem';
import { serializeSyncItem } from '../../utils/serializeSyncItem';

const sample: SyncItem = {
  id: '01H8XYZABCDEF',
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  headers: { 'X-Trace': 't' },
  body: '{"k":"v"}',
  contentType: 'application/json',
  createdAt: 1_700_000_000_000,
  attempts: 2,
  nextRetryAt: 1_717_000_000_000,
  metadata: { source: 'unit' },
};

describe('serializeSyncItem', () => {
  it('produces a JSON string that round-trips through deserializeSyncItem', () => {
    const json = serializeSyncItem(sample);
    expect(typeof json).toBe('string');
    const parsed = deserializeSyncItem(json);
    expect(parsed).toEqual(sample);
  });

  it('throws SyncError(INVALID_PAYLOAD) on cyclic structures', () => {
    const cyclic: Record<string, unknown> = { ...sample };
    cyclic.self = cyclic;
    try {
      serializeSyncItem(cyclic as unknown as SyncItem);
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(SyncError);
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });

  it('serializes minimal items (no headers/metadata) to JSON', () => {
    const minimal: SyncItem = {
      id: '01H8MINI',
      method: HttpMethod.GET,
      url: 'https://x.test/y',
      createdAt: 1,
      attempts: 0,
      nextRetryAt: 0,
    };
    const json = serializeSyncItem(minimal);
    expect(JSON.parse(json)).toEqual(minimal);
  });

  it('round-trips the attempts / nextRetryAt retry fields verbatim', () => {
    const parsed = deserializeSyncItem(serializeSyncItem(sample));
    expect(parsed.attempts).toBe(2);
    expect(parsed.nextRetryAt).toBe(1_717_000_000_000);
  });
});

describe('deserializeSyncItem — attempts / nextRetryAt', () => {
  it('defaults attempts and nextRetryAt to 0 for legacy JSON missing them', () => {
    // JSON written before the retry bookkeeping fields existed.
    const legacy = JSON.stringify({
      id: '01H8LEGACY',
      method: HttpMethod.POST,
      url: 'https://x.test/legacy',
      createdAt: 1_700_000_000_000,
    });

    const parsed = deserializeSyncItem(legacy);

    expect(parsed.attempts).toBe(0);
    expect(parsed.nextRetryAt).toBe(0);
  });

  it('preserves an explicit attempts: 0 / nextRetryAt: 0 (0 is a real value, not skipped)', () => {
    const json = JSON.stringify({
      id: '01H8ZERO',
      method: HttpMethod.GET,
      url: 'https://x.test/zero',
      createdAt: 5,
      attempts: 0,
      nextRetryAt: 0,
    });

    const parsed = deserializeSyncItem(json);

    expect(parsed.attempts).toBe(0);
    expect(parsed.nextRetryAt).toBe(0);
  });

  it('throws INVALID_PAYLOAD when attempts is present but not a number', () => {
    const json = JSON.stringify({
      id: '01H8BAD',
      method: HttpMethod.GET,
      url: 'https://x.test/bad',
      createdAt: 5,
      attempts: 'two',
    });

    try {
      deserializeSyncItem(json);
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(SyncError);
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });

  it('throws INVALID_PAYLOAD when nextRetryAt is present but not a number', () => {
    const json = JSON.stringify({
      id: '01H8BAD2',
      method: HttpMethod.GET,
      url: 'https://x.test/bad2',
      createdAt: 5,
      nextRetryAt: false,
    });

    try {
      deserializeSyncItem(json);
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(SyncError);
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });
});

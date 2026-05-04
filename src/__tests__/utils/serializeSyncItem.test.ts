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
    };
    const json = serializeSyncItem(minimal);
    expect(JSON.parse(json)).toEqual(minimal);
  });
});

import { describe, expect, it } from '@jest/globals';

import { SyncError } from '../../errors/SyncError';
import { SyncErrorCode } from '../../errors/SyncErrorCode';
import { HttpMethod, SyncPriority } from '../../types/enums';
import type { SyncItemInput } from '../../types/sync';
import { validateSyncItem } from '../../utils/validateSyncItem';

describe('validateSyncItem', () => {
  const baseValid: SyncItemInput = {
    method: HttpMethod.POST,
    url: 'https://api.example.com/events',
    body: '{"a":1}',
    contentType: 'application/json',
    priority: SyncPriority.NORMAL,
    headers: { Authorization: 'Bearer x' },
    metadata: { source: 'unit-test' },
  };

  it('accepts a fully populated valid input', () => {
    expect(() => validateSyncItem(baseValid)).not.toThrow();
  });

  it('accepts a minimal input with only method + url', () => {
    expect(() =>
      validateSyncItem({ method: HttpMethod.GET, url: 'https://x.test/y' })
    ).not.toThrow();
  });

  it('throws INVALID_PAYLOAD when input is null', () => {
    try {
      validateSyncItem(null as unknown as SyncItemInput);
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(SyncError);
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });

  it('throws INVALID_PAYLOAD when method is missing', () => {
    expect(() =>
      validateSyncItem({ url: 'https://a.test/b' } as unknown as SyncItemInput)
    ).toThrow(SyncError);
  });

  it('throws INVALID_PAYLOAD when method is not in HttpMethod', () => {
    try {
      validateSyncItem({
        method: 'CONNECT' as unknown as HttpMethod,
        url: 'https://a.test/b',
      });
      throw new Error('expected throw');
    } catch (err) {
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });

  it('throws INVALID_URL when url is empty', () => {
    try {
      validateSyncItem({ method: HttpMethod.GET, url: '' });
      throw new Error('expected throw');
    } catch (err) {
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_URL);
    }
  });

  it('throws INVALID_URL when url is not absolute http(s)', () => {
    try {
      validateSyncItem({ method: HttpMethod.GET, url: 'ftp://x.test/y' });
      throw new Error('expected throw');
    } catch (err) {
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_URL);
    }
  });

  it('throws INVALID_PAYLOAD when body is not a string', () => {
    try {
      validateSyncItem({
        method: HttpMethod.POST,
        url: 'https://a.test/b',
        body: { a: 1 } as unknown as string,
      });
      throw new Error('expected throw');
    } catch (err) {
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });

  it('throws INVALID_PAYLOAD when contentType is not a string', () => {
    try {
      validateSyncItem({
        method: HttpMethod.POST,
        url: 'https://a.test/b',
        contentType: 42 as unknown as string,
      });
      throw new Error('expected throw');
    } catch (err) {
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });

  it('throws INVALID_PAYLOAD when priority is unknown', () => {
    try {
      validateSyncItem({
        method: HttpMethod.POST,
        url: 'https://a.test/b',
        priority: 'CRITICAL' as unknown as SyncPriority,
      });
      throw new Error('expected throw');
    } catch (err) {
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });

  it('throws INVALID_PAYLOAD when a header value is not a string', () => {
    try {
      validateSyncItem({
        method: HttpMethod.POST,
        url: 'https://a.test/b',
        headers: { 'X-Bad': 5 as unknown as string },
      });
      throw new Error('expected throw');
    } catch (err) {
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });

  it('throws INVALID_PAYLOAD when metadata is not an object', () => {
    try {
      validateSyncItem({
        method: HttpMethod.POST,
        url: 'https://a.test/b',
        metadata: 'not-an-object' as unknown as Record<string, string>,
      });
      throw new Error('expected throw');
    } catch (err) {
      expect((err as SyncError).code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    }
  });
});

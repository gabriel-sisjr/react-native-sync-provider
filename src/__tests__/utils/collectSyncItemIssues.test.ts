import { describe, expect, it } from '@jest/globals';

import { SyncError } from '../../errors/SyncError';
import { SyncErrorCode } from '../../errors/SyncErrorCode';
import { HttpMethod, SyncPriority } from '../../types/enums';
import type { SyncItemInput } from '../../types/sync';
import {
  collectSyncItemIssues,
  validateSyncItem,
} from '../../utils/validateSyncItem';

describe('collectSyncItemIssues', () => {
  it('returns an empty array for a fully valid input', () => {
    const input: SyncItemInput = {
      method: HttpMethod.POST,
      url: 'https://api.example.com/x',
      body: '{"a":1}',
      contentType: 'application/json',
      priority: SyncPriority.NORMAL,
      headers: { Authorization: 'Bearer x' },
      metadata: { source: 'unit' },
    };
    expect(collectSyncItemIssues(input)).toEqual([]);
  });

  it('aggregates EVERY violation on a single item (not just the first)', () => {
    const bad = {
      method: 'CONNECT',
      url: 'ftp://nope',
      body: { not: 'a string' },
      contentType: 42,
      priority: 'CRITICAL',
      headers: { 'X-Bad': 5 },
      metadata: { 'M-Bad': true },
    } as unknown as SyncItemInput;

    const issues = collectSyncItemIssues(bad);
    const fields = issues.map((i) => i.field);

    // One issue per malformed field, in declaration order.
    expect(fields).toEqual([
      'method',
      'url',
      'body',
      'contentType',
      'priority',
      'headers.X-Bad',
      'metadata.M-Bad',
    ]);
    expect(issues.length).toBe(7);
    // No itemIndex for single-item collection.
    expect(issues.every((i) => i.itemIndex === undefined)).toBe(true);
  });

  it('reports a single issue for a non-object input and stops there', () => {
    const issues = collectSyncItemIssues(null as unknown as SyncItemInput);
    expect(issues).toHaveLength(1);
    expect(issues[0]?.field).toBe('input');
    expect(issues[0]?.reason).toBe('SyncItemInput must be a non-null object.');
  });

  it('keeps the offending value for method, priority and contentType', () => {
    const issues = collectSyncItemIssues({
      method: 'CONNECT',
      url: 'https://a.test/b',
      contentType: 42,
      priority: 'CRITICAL',
    } as unknown as SyncItemInput);

    expect(issues.map(({ field, value }) => ({ field, value }))).toEqual([
      { field: 'method', value: 'CONNECT' },
      { field: 'contentType', value: 42 },
      { field: 'priority', value: 'CRITICAL' },
    ]);
  });

  it.each<[string, string, unknown]>([
    ['a non-string url', 'url', { url: 5 }],
    ['a malformed url', 'url', { url: 'ftp://x.test?token=SECRET' }],
    ['a non-string body', 'body', { body: { password: 'SECRET' } }],
    ['headers that are not an object', 'headers', { headers: 'Bearer SECRET' }],
    [
      'a non-string header',
      'headers.Authorization',
      { headers: { Authorization: { token: 'SECRET' } } },
    ],
    ['metadata that is not an object', 'metadata', { metadata: 'SECRET' }],
    [
      'a non-string metadata entry',
      'metadata.owner',
      { metadata: { owner: { cpf: 'SECRET' } } },
    ],
  ])('omits the value for %s', (_name, field, overrides) => {
    const issues = collectSyncItemIssues({
      method: HttpMethod.POST,
      url: 'https://a.test/b',
      ...(overrides as object),
    });

    const issue = issues.find((candidate) => candidate.field === field);
    expect(issue).toBeDefined();
    expect(issue).not.toHaveProperty('value');
    expect(JSON.stringify(issues)).not.toContain('SECRET');
  });

  it('omits the value when the whole input is invalid', () => {
    const issues = collectSyncItemIssues('SECRET' as unknown as SyncItemInput);

    expect(issues).toHaveLength(1);
    expect(issues[0]).not.toHaveProperty('value');
  });

  it('keeps a malformed url out of the public reason', () => {
    const [issue] = collectSyncItemIssues({
      method: HttpMethod.GET,
      url: 'ftp://x.test?token=SECRET',
    });

    expect(issue?.reason).toBe(
      'SyncItemInput.url must be an absolute http(s) URL.'
    );
  });
});

describe('validateSyncItem — preserved first-violation behavior', () => {
  it('still throws INVALID_URL with the exact legacy message for a bad URL', () => {
    try {
      validateSyncItem({ method: HttpMethod.GET, url: 'ftp://x.test/y' });
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(SyncError);
      const e = err as SyncError;
      expect(e.code).toBe(SyncErrorCode.INVALID_URL);
      expect(e.message).toBe(
        'SyncItemInput.url must be an absolute http(s) URL (got: "ftp://x.test/y").'
      );
    }
  });

  it('still throws INVALID_PAYLOAD for a non-string body (message preserved)', () => {
    try {
      validateSyncItem({
        method: HttpMethod.POST,
        url: 'https://a.test/b',
        body: { a: 1 } as unknown as string,
      });
      throw new Error('expected throw');
    } catch (err) {
      const e = err as SyncError;
      expect(e.code).toBe(SyncErrorCode.INVALID_PAYLOAD);
      expect(e.message).toBe(
        'SyncItemInput.body must be a string when supplied. Stringify objects on the JS side first.'
      );
    }
  });

  it('now also populates validationErrors with the full list', () => {
    try {
      validateSyncItem({
        method: 'CONNECT' as unknown as HttpMethod,
        url: 'ftp://nope',
      });
      throw new Error('expected throw');
    } catch (err) {
      const e = err as SyncError;
      // Thrown code/message track the FIRST issue (method → INVALID_PAYLOAD)…
      expect(e.code).toBe(SyncErrorCode.INVALID_PAYLOAD);
      // …while validationErrors carries every issue.
      expect(e.validationErrors?.map((i) => i.field)).toEqual([
        'method',
        'url',
      ]);
    }
  });

  it('does not throw for a valid input', () => {
    expect(() =>
      validateSyncItem({ method: HttpMethod.GET, url: 'https://x.test/y' })
    ).not.toThrow();
  });

  const VALID = { method: HttpMethod.POST, url: 'https://a.test/b' };

  it.each<[string, unknown, SyncErrorCode, string]>([
    [
      'a non-object input',
      null,
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput must be a non-null object.',
    ],
    [
      'a bad method',
      { ...VALID, method: 'CONNECT' },
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.method must be one of GET, POST, PUT, PATCH, DELETE.',
    ],
    [
      'an empty url',
      { ...VALID, url: '' },
      SyncErrorCode.INVALID_URL,
      'SyncItemInput.url must be a non-empty string.',
    ],
    [
      'a malformed url',
      { ...VALID, url: 'not-a-url' },
      SyncErrorCode.INVALID_URL,
      'SyncItemInput.url must be an absolute http(s) URL (got: "not-a-url").',
    ],
    [
      'a bad contentType',
      { ...VALID, contentType: 5 },
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.contentType must be a string when supplied.',
    ],
    [
      'a bad priority',
      { ...VALID, priority: 'CRITICAL' },
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.priority must be one of HIGH, NORMAL, LOW.',
    ],
    [
      'bad headers',
      { ...VALID, headers: null },
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.headers must be an object of string→string entries.',
    ],
    [
      'a bad header value',
      { ...VALID, headers: { 'X-Id': 1 } },
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.headers["X-Id"] must be a string.',
    ],
    [
      'bad metadata',
      { ...VALID, metadata: null },
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.metadata must be an object of string→string entries.',
    ],
    [
      'a bad metadata value',
      { ...VALID, metadata: { owner: 1 } },
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.metadata["owner"] must be a string.',
    ],
  ])(
    'keeps the legacy code and message for %s',
    (_name, input, code, message) => {
      try {
        validateSyncItem(input as SyncItemInput);
        throw new Error('expected throw');
      } catch (err) {
        expect(err).toBeInstanceOf(SyncError);
        expect((err as SyncError).code).toBe(code);
        expect((err as SyncError).message).toBe(message);
      }
    }
  );
});

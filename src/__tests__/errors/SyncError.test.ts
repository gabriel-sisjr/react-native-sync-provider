import { describe, expect, it } from '@jest/globals';

import { SyncError } from '../../errors/SyncError';
import { SyncErrorCode } from '../../errors/SyncErrorCode';
import type { ValidationIssue } from '../../errors/ValidationIssue';

describe('SyncError — validationErrors', () => {
  it('keeps the legacy 2-arg constructor working', () => {
    const err = new SyncError(SyncErrorCode.NETWORK_ERROR, 'boom');
    expect(err).toBeInstanceOf(SyncError);
    expect(err.code).toBe(SyncErrorCode.NETWORK_ERROR);
    expect(err.message).toBe('boom');
    expect(err.cause).toBeUndefined();
    expect(err.validationErrors).toBeUndefined();
  });

  it('keeps the legacy 3-arg (cause) constructor working', () => {
    const cause = new Error('inner');
    const err = new SyncError(SyncErrorCode.SERVER_ERROR, 'outer', cause);
    expect(err.code).toBe(SyncErrorCode.SERVER_ERROR);
    expect(err.cause).toBe(cause);
    expect(err.validationErrors).toBeUndefined();
  });

  it('carries the structured validationErrors list when supplied', () => {
    const issues: ValidationIssue[] = [
      { field: 'url', reason: 'bad url', value: 'nope' },
      { field: 'method', reason: 'bad method' },
    ];
    const err = new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'summary',
      undefined,
      issues
    );
    expect(err.validationErrors).toEqual(issues);
    expect(err.cause).toBeUndefined();
  });

  it('preserves instanceof across a re-throw boundary', () => {
    const err = new SyncError(SyncErrorCode.INVALID_URL, 'x', undefined, [
      { field: 'url', reason: 'x' },
    ]);
    let caught: unknown;
    try {
      throw err;
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(SyncError);
    expect((caught as SyncError).validationErrors).toHaveLength(1);
  });
});

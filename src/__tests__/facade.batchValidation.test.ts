import { describe, expect, it } from '@jest/globals';

import { enqueue, enqueueBatch, SyncError, SyncErrorCode } from '../index';
import { HttpMethod } from '../types/enums';
import type { SyncItemInput } from '../types/sync';

interface SyncMock {
  enqueueBatch: jest.Mock;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

describe('enqueueBatch — aggregated validation', () => {
  it('aggregates issues across items and tags each with its itemIndex', async () => {
    const mock = getMock();
    const items = [
      { method: HttpMethod.POST, url: 'https://ok.test/a' }, // valid → index 0
      { method: HttpMethod.POST, url: 'ftp://bad' }, // index 1 → url
      {
        method: 'CONNECT',
        url: 'https://ok.test/c',
      } as unknown as SyncItemInput, // index 2 → method
    ];

    let caught: SyncError | undefined;
    try {
      await enqueueBatch(items);
    } catch (err) {
      caught = err as SyncError;
    }

    expect(caught).toBeInstanceOf(SyncError);
    expect(caught?.code).toBe(SyncErrorCode.INVALID_PAYLOAD);

    const issues = caught?.validationErrors ?? [];
    expect(issues).toHaveLength(2);

    const urlIssue = issues.find((i) => i.field === 'url');
    const methodIssue = issues.find((i) => i.field === 'method');
    expect(urlIssue?.itemIndex).toBe(1);
    expect(methodIssue?.itemIndex).toBe(2);

    // Never reached native because validation failed up front.
    expect(mock.enqueueBatch).not.toHaveBeenCalled();
  });

  it('collects multiple issues from the same item under its index', async () => {
    const items = [
      {
        method: 'CONNECT',
        url: 'ftp://bad',
      } as unknown as SyncItemInput, // index 0 → method + url
    ];

    let caught: SyncError | undefined;
    try {
      await enqueueBatch(items);
    } catch (err) {
      caught = err as SyncError;
    }

    const issues = caught?.validationErrors ?? [];
    expect(issues.map((i) => i.field)).toEqual(['method', 'url']);
    expect(issues.every((i) => i.itemIndex === 0)).toBe(true);
  });

  it('uses the umbrella code when the only problem is a url', async () => {
    const error = await enqueueBatch([
      { method: HttpMethod.POST, url: 'not-a-url' },
    ]).catch((err: SyncError) => err);

    expect(error).toMatchObject({ code: SyncErrorCode.INVALID_PAYLOAD });
  });

  it('rejects an array hole instead of sending it to native', async () => {
    const mock = getMock();
    const valid = { method: HttpMethod.POST, url: 'https://ok.test/a' };
    const items: SyncItemInput[] = [valid, valid, valid];
    delete items[1];

    let caught: SyncError | undefined;
    try {
      await enqueueBatch(items);
    } catch (err) {
      caught = err as SyncError;
    }

    expect(caught?.code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    expect(caught?.validationErrors).toEqual([
      {
        field: 'input',
        reason: 'SyncItemInput must be a non-null object.',
        itemIndex: 1,
      },
    ]);
    expect(mock.enqueueBatch).not.toHaveBeenCalled();
  });

  it('forwards to native when all items are valid', async () => {
    const mock = getMock();
    mock.enqueueBatch.mockResolvedValueOnce(['a', 'b']);
    const ids = await enqueueBatch([
      { method: HttpMethod.POST, url: 'https://a.test/x' },
      { method: HttpMethod.POST, url: 'https://b.test/y' },
    ]);
    expect(ids).toEqual(['a', 'b']);
    expect(mock.enqueueBatch).toHaveBeenCalledTimes(1);
  });
});

describe('validation errors do not expose payload data', () => {
  const SECRET = 'SECRET-TOKEN-abc123';
  const url = 'https://a.test/x';
  const cases: Array<[string, unknown]> = [
    [
      'a body that was not stringified',
      {
        method: HttpMethod.POST,
        url,
        body: { email: 'user@example.com', password: SECRET },
      },
    ],
    [
      'a non-string header value',
      { method: HttpMethod.POST, url, headers: { Authorization: { SECRET } } },
    ],
    [
      'headers passed as a string',
      { method: HttpMethod.POST, url, headers: `Bearer ${SECRET}` },
    ],
    [
      'metadata passed as a string',
      { method: HttpMethod.POST, url, metadata: SECRET },
    ],
    [
      'a url with a token',
      { method: HttpMethod.POST, url: `${url}?token=${SECRET} ` },
    ],
    ['the whole input passed as a string', JSON.stringify({ SECRET })],
  ];

  it.each(cases)('from enqueue: %s', async (_name, input) => {
    const error = await enqueue(input as SyncItemInput).catch(
      (err: SyncError) => err
    );

    expect(error).toBeInstanceOf(SyncError);
    expect(JSON.stringify(error)).not.toContain(SECRET);
    expect(JSON.stringify((error as SyncError).validationErrors)).not.toContain(
      SECRET
    );
  });

  it.each(cases)('from enqueueBatch: %s', async (_name, input) => {
    const error = await enqueueBatch([input as SyncItemInput]).catch(
      (err: SyncError) => err
    );

    expect(error).toBeInstanceOf(SyncError);
    expect(JSON.stringify(error)).not.toContain(SECRET);
    expect((error as SyncError).message).not.toContain(SECRET);
  });

  it('keeps a cyclic body from breaking JSON.stringify(error)', async () => {
    const body: Record<string, unknown> = {};
    body.self = body;

    const error = await enqueue({
      method: HttpMethod.POST,
      url,
      body,
    } as unknown as SyncItemInput).catch((err: SyncError) => err);

    expect(() => JSON.stringify(error)).not.toThrow();
  });
});

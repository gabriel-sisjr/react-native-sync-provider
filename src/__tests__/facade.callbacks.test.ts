import { describe, expect, it } from '@jest/globals';

import {
  BeforeDispatchAction,
  clearAfterDispatch,
  clearBeforeDispatch,
  clearHeaderResolver,
  clearRetryDecider,
  DispatchOutcome,
  HttpMethod,
  isRetryDeciderInstalled,
  RetryAction,
  setAfterDispatch,
  setBeforeDispatch,
  setHeaderResolver,
  setRetryDecider,
  SyncError,
  SyncErrorCode,
} from '../index';
import type {
  AfterDispatchContext,
  AfterDispatchHook,
  BeforeDispatchContext,
  BeforeDispatchHook,
  HeaderResolver,
  HeaderResolverContext,
  RetryDecider,
  RetryDeciderContext,
} from '../index';

type CallbackKind =
  | 'retryDecider'
  | 'headerResolver'
  | 'beforeDispatch'
  | 'afterDispatch';

type NativeCallback = (requestId: string, context: unknown) => void;

interface SyncMock {
  setRetryDecider: jest.Mock;
  clearRetryDecider: jest.Mock;
  isRetryDeciderInstalled: jest.Mock;
  setHeaderResolver: jest.Mock;
  clearHeaderResolver: jest.Mock;
  setBeforeDispatch: jest.Mock;
  clearBeforeDispatch: jest.Mock;
  setAfterDispatch: jest.Mock;
  clearAfterDispatch: jest.Mock;
  answerCallback: jest.Mock;
  __invokeCallback: (kind: CallbackKind, context: unknown) => Promise<unknown>;
  __installedCallback: (kind: CallbackKind) => NativeCallback | undefined;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const RESOLVED = 'RESOLVED';
const FAILED = 'FAILED';

/** Let every pending promise job run, plus one timer tick. */
const flushAsync = () =>
  new Promise<void>((resolve) => {
    setTimeout(resolve, 0);
  });

/** The callback native would call for `kind`, which the test must have set. */
function installedCallback(kind: CallbackKind): NativeCallback {
  const callback = getMock().__installedCallback(kind);
  if (callback === undefined) {
    throw new Error(`No ${kind} is installed.`);
  }
  return callback;
}

const ITEM = {
  itemId: '01HZX0CALLBACKITEM',
  url: 'https://api.example.com/events',
  method: HttpMethod.POST,
  metadata: { kind: 'analytics' },
};

const DECIDER_CONTEXT: RetryDeciderContext = {
  ...ITEM,
  attempt: 2,
  errorCode: SyncErrorCode.SERVER_ERROR,
  statusCode: 503,
};

const RESOLVER_CONTEXT: HeaderResolverContext = { ...ITEM, attempt: 3 };

const BEFORE_CONTEXT: BeforeDispatchContext = {
  ...ITEM,
  attempt: 3,
  headers: { 'Authorization': 'Bearer t0k3n', 'X-Trace': 'abc' },
  contentType: 'application/json',
};

const AFTER_CONTEXT: AfterDispatchContext = {
  ...ITEM,
  attempt: 3,
  outcome: DispatchOutcome.RETRYING,
  statusCode: 503,
  errorCode: SyncErrorCode.SERVER_ERROR,
  durationMs: 87,
};

/*
 * Each helper installs a consumer function, then answers one native request
 * the way native makes it. They take functions that return anything, so a test
 * can return what the public types forbid.
 */

async function decide(
  decider: (context: RetryDeciderContext) => unknown,
  context: RetryDeciderContext = DECIDER_CONTEXT
): Promise<unknown> {
  await setRetryDecider(decider as RetryDecider);
  return getMock().__invokeCallback('retryDecider', context);
}

async function resolveHeaders(
  resolver: (context: HeaderResolverContext) => unknown,
  context: HeaderResolverContext = RESOLVER_CONTEXT
): Promise<unknown> {
  await setHeaderResolver(resolver as HeaderResolver);
  return getMock().__invokeCallback('headerResolver', context);
}

async function beforeDispatch(
  hook: (context: BeforeDispatchContext) => unknown,
  context: BeforeDispatchContext = BEFORE_CONTEXT
): Promise<unknown> {
  await setBeforeDispatch(hook as BeforeDispatchHook);
  return getMock().__invokeCallback('beforeDispatch', context);
}

async function afterDispatch(
  hook: (context: AfterDispatchContext) => unknown,
  context: AfterDispatchContext = AFTER_CONTEXT
): Promise<unknown> {
  await setAfterDispatch(hook as AfterDispatchHook);
  return getMock().__invokeCallback('afterDispatch', context);
}

describe('callback setters', () => {
  const SETTERS = [
    ['setRetryDecider', 'decider', setRetryDecider],
    ['setHeaderResolver', 'resolver', setHeaderResolver],
    ['setBeforeDispatch', 'hook', setBeforeDispatch],
    ['setAfterDispatch', 'hook', setAfterDispatch],
  ] as const;

  describe.each(SETTERS)('%s', (method, parameter, setter) => {
    const install = setter as (argument: unknown) => Promise<void>;

    it.each([
      [undefined, 'undefined'],
      [null, 'null'],
      ['callback', 'string'],
      [42, 'number'],
      [{}, 'object'],
      [[], 'object'],
    ])(
      'rejects %p with INVALID_PAYLOAD before any native call',
      async (argument, received) => {
        const reason = `${method}(${parameter}) requires a function, received ${received}.`;

        const error = await install(argument).catch((e: unknown) => e);

        expect(error).toBeInstanceOf(SyncError);
        expect(error).toMatchObject({
          code: SyncErrorCode.INVALID_PAYLOAD,
          message: reason,
        });
        expect((error as SyncError).validationErrors).toEqual([
          { field: parameter, reason },
        ]);
        expect(getMock()[method]).not.toHaveBeenCalled();
      }
    );

    it('installs a native-facing wrapper, not the consumer function', async () => {
      const consumer = jest.fn();

      await expect(install(consumer)).resolves.toBeUndefined();

      expect(getMock()[method]).toHaveBeenCalledTimes(1);
      const installed: unknown = getMock()[method].mock.calls[0]?.[0];
      expect(installed).toEqual(expect.any(Function));
      expect(installed).not.toBe(consumer);
      expect(consumer).not.toHaveBeenCalled();
    });

    it('passes the stale-owner rejection through as NATIVE_MODULE_UNAVAILABLE', async () => {
      // Native rejects a `set…` from a hybrid a newer JS runtime evicted.
      const stale = new Error(
        'NATIVE_MODULE_UNAVAILABLE: a newer JS runtime owns the installs'
      );
      getMock()[method].mockRejectedValueOnce(stale);

      const error = await install(jest.fn()).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(SyncError);
      expect(error).toMatchObject({
        code: SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
        message: stale.message,
        cause: stale,
      });
      expect((error as SyncError).validationErrors).toBeUndefined();
    });

    it('maps any other native rejection to NATIVE_MODULE_UNAVAILABLE too', async () => {
      getMock()[method].mockRejectedValueOnce(new Error('install failed'));

      await expect(install(jest.fn())).rejects.toMatchObject({
        name: 'SyncError',
        code: SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
        message: 'install failed',
      });
    });
  });

  it('a repeated set replaces the installed callback', async () => {
    await setRetryDecider(() => ({ action: RetryAction.DROP }));
    await setRetryDecider(() => ({ action: RetryAction.DEAD_LETTER }));

    await expect(
      getMock().__invokeCallback('retryDecider', DECIDER_CONTEXT)
    ).resolves.toEqual({
      status: RESOLVED,
      decision: { action: RetryAction.DEAD_LETTER },
    });
  });
});

describe('uninstallers and isRetryDeciderInstalled', () => {
  const CLEARERS = [
    ['clearRetryDecider', clearRetryDecider],
    ['clearHeaderResolver', clearHeaderResolver],
    ['clearBeforeDispatch', clearBeforeDispatch],
    ['clearAfterDispatch', clearAfterDispatch],
  ] as const;

  it.each(CLEARERS)('%s passes through', async (method, clear) => {
    await expect(clear()).resolves.toBeUndefined();

    expect(getMock()[method]).toHaveBeenCalledTimes(1);
    expect(getMock()[method]).toHaveBeenCalledWith();
  });

  it.each(CLEARERS)(
    '%s maps a native rejection to INVALID_PAYLOAD',
    async (method, clear) => {
      getMock()[method].mockRejectedValueOnce(new Error('clear failed'));

      await expect(clear()).rejects.toMatchObject({
        code: SyncErrorCode.INVALID_PAYLOAD,
        message: 'clear failed',
      });
    }
  );

  it('isRetryDeciderInstalled reports what native reports', async () => {
    await expect(isRetryDeciderInstalled()).resolves.toBe(false);

    await setRetryDecider(() => ({ action: RetryAction.RETRY }));
    await expect(isRetryDeciderInstalled()).resolves.toBe(true);

    await clearRetryDecider();
    await expect(isRetryDeciderInstalled()).resolves.toBe(false);
    expect(getMock().isRetryDeciderInstalled).toHaveBeenCalledTimes(3);
  });

  it('isRetryDeciderInstalled maps a native rejection to INVALID_PAYLOAD', async () => {
    getMock().isRetryDeciderInstalled.mockRejectedValueOnce(
      new Error('query failed')
    );

    await expect(isRetryDeciderInstalled()).rejects.toMatchObject({
      code: SyncErrorCode.INVALID_PAYLOAD,
      message: 'query failed',
    });
  });
});

/*
 * The answers below are pinned with `toStrictEqual`, because their shape is the
 * native contract: a RESOLVED answer without its kind's field (`decision`,
 * `headers`, `beforeDispatch`) is FAILED natively. `afterDispatch` needs none.
 */

describe('retry decider answers', () => {
  it.each(Object.values(RetryAction))(
    'passes a sync %s decision',
    async (action) => {
      await expect(decide(() => ({ action }))).resolves.toStrictEqual({
        status: RESOLVED,
        decision: { action },
      });
    }
  );

  it('passes an async decision', async () => {
    await expect(
      decide(async () => ({ action: RetryAction.DROP }))
    ).resolves.toStrictEqual({
      status: RESOLVED,
      decision: { action: RetryAction.DROP },
    });
  });

  it('receives the context native sent, and nothing else', async () => {
    const decider = jest.fn((_context: RetryDeciderContext) => ({
      action: RetryAction.RETRY,
    }));

    await decide(decider);

    expect(decider).toHaveBeenCalledTimes(1);
    expect(decider).toHaveBeenCalledWith(DECIDER_CONTEXT);
  });

  it.each([
    [0, 0],
    [1_500, 1_500],
    [1_500.9, 1_500],
    [0.4, 0],
    [2 ** 53, 2 ** 53],
  ])('keeps delayMs %p as %p', async (delayMs, expected) => {
    await expect(
      decide(() => ({ action: RetryAction.RETRY, delayMs }))
    ).resolves.toStrictEqual({
      status: RESOLVED,
      decision: { action: RetryAction.RETRY, delayMs: expected },
    });
  });

  it.each([
    -1,
    -0.5,
    Number.NaN,
    Number.POSITIVE_INFINITY,
    Number.NEGATIVE_INFINITY,
    '500',
    null,
  ])('drops delayMs %p and keeps the action', async (delayMs) => {
    await expect(
      decide(() => ({ action: RetryAction.RETRY, delayMs }))
    ).resolves.toStrictEqual({
      status: RESOLVED,
      decision: { action: RetryAction.RETRY },
    });
  });

  it('keeps a valid delayMs whatever the action, for native to ignore', async () => {
    await expect(
      decide(() => ({ action: RetryAction.DROP, delayMs: 10.7 }))
    ).resolves.toStrictEqual({
      status: RESOLVED,
      decision: { action: RetryAction.DROP, delayMs: 10 },
    });
  });

  it('forwards only action and delayMs', async () => {
    await expect(
      decide(() => ({ action: RetryAction.RETRY, delayMs: 5, reason: 'x' }))
    ).resolves.toStrictEqual({
      status: RESOLVED,
      decision: { action: RetryAction.RETRY, delayMs: 5 },
    });
  });

  it.each([
    ['undefined', undefined],
    ['null', null],
    ['a bare action string', 'RETRY'],
    ['a number', 42],
    ['an empty object', {}],
    ['a lowercase action', { action: 'retry' }],
    ['a beforeDispatch action', { action: 'SKIP' }],
    ['an array', [RetryAction.RETRY]],
  ])('answers FAILED for %s', async (_label, result) => {
    await expect(decide(() => result)).resolves.toStrictEqual({
      status: FAILED,
      message:
        'retry decider returned an invalid result: expected { action: RETRY | DROP | DEAD_LETTER, delayMs? }.',
    });
  });

  it('answers FAILED for an invalid async result', async () => {
    await expect(
      decide(async () => ({ action: 'NOPE' }))
    ).resolves.toMatchObject({ status: FAILED });
  });

  it('answers FAILED when the decider throws', async () => {
    await expect(
      decide(() => {
        throw new Error('boom');
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'retry decider failed: boom',
    });
  });

  it('answers FAILED when the decider rejects', async () => {
    await expect(
      decide(async () => {
        throw new Error('offline');
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'retry decider failed: offline',
    });
  });

  it('answers FAILED when reading the result throws', async () => {
    await expect(
      decide(() => ({
        get action(): string {
          throw new Error('getter');
        },
      }))
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'retry decider failed: getter',
    });
  });
});

describe('header resolver answers', () => {
  it('passes sync headers as a copy', async () => {
    const headers = { 'Authorization': 'Bearer t0k3n', 'X-Trace': 'abc' };

    const answer = await resolveHeaders(() => headers);

    expect(answer).toStrictEqual({ status: RESOLVED, headers });
    expect((answer as { headers: unknown }).headers).not.toBe(headers);
  });

  it('passes async headers', async () => {
    await expect(
      resolveHeaders(async () => ({ 'X-Tenant': 'acme' }))
    ).resolves.toStrictEqual({
      status: RESOLVED,
      headers: { 'X-Tenant': 'acme' },
    });
  });

  it('passes an empty object, which adds no header', async () => {
    await expect(resolveHeaders(() => ({}))).resolves.toStrictEqual({
      status: RESOLVED,
      headers: {},
    });
  });

  it('accepts an object without a prototype', async () => {
    const headers = Object.assign(Object.create(null) as object, {
      'X-Tenant': 'acme',
    });

    await expect(resolveHeaders(() => headers)).resolves.toStrictEqual({
      status: RESOLVED,
      headers: { 'X-Tenant': 'acme' },
    });
  });

  it('receives the context native sent', async () => {
    const resolver = jest.fn((_context: HeaderResolverContext) => ({}));

    await resolveHeaders(resolver);

    expect(resolver).toHaveBeenCalledWith(RESOLVER_CONTEXT);
  });

  class HeaderBag {
    Authorization = 'Bearer t0k3n';
  }

  it.each([
    ['undefined', undefined],
    ['null', null],
    ['a string', 'Bearer t0k3n'],
    ['a number', 42],
    ['an array of strings', ['Bearer t0k3n']],
    ['a Map', new Map([['Authorization', 'Bearer t0k3n']])],
    ['a class instance', new HeaderBag()],
    ['a number value', { 'X-Count': 1 }],
    ['an undefined value', { 'X-Missing': undefined }],
    ['a null value', { 'X-Null': null }],
    ['an array value', { Accept: ['a', 'b'] }],
  ])('answers FAILED for %s', async (_label, result) => {
    await expect(resolveHeaders(() => result)).resolves.toStrictEqual({
      status: FAILED,
      message:
        'header resolver returned an invalid result: expected an object whose values are all strings.',
    });
  });

  it('never quotes a header in the failure message', async () => {
    const answer = await resolveHeaders(() => ({
      Authorization: { token: 's3cr3t' },
    }));

    expect(answer).toMatchObject({ status: FAILED });
    expect(JSON.stringify(answer)).not.toContain('s3cr3t');
    expect(JSON.stringify(answer)).not.toContain('Authorization');
  });

  it('answers FAILED when the resolver throws', async () => {
    await expect(
      resolveHeaders(() => {
        throw new Error('no session');
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'header resolver failed: no session',
    });
  });

  it('answers FAILED when the resolver rejects', async () => {
    await expect(
      resolveHeaders(() => Promise.reject(new Error('refresh failed')))
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'header resolver failed: refresh failed',
    });
  });
});

describe('beforeDispatch answers', () => {
  const proceed = {
    status: RESOLVED,
    beforeDispatch: { action: BeforeDispatchAction.PROCEED },
  };

  it('maps a sync return of nothing to PROCEED', async () => {
    await expect(beforeDispatch(() => undefined)).resolves.toStrictEqual(
      proceed
    );
  });

  it('maps an async return of nothing to PROCEED', async () => {
    await expect(beforeDispatch(async () => undefined)).resolves.toStrictEqual(
      proceed
    );
  });

  it.each(Object.values(BeforeDispatchAction))('passes %s', async (action) => {
    await expect(beforeDispatch(() => ({ action }))).resolves.toStrictEqual({
      status: RESOLVED,
      beforeDispatch: { action },
    });
  });

  it('passes an async SKIP', async () => {
    await expect(
      beforeDispatch(async () => ({ action: BeforeDispatchAction.SKIP }))
    ).resolves.toStrictEqual({
      status: RESOLVED,
      beforeDispatch: { action: BeforeDispatchAction.SKIP },
    });
  });

  it('receives the context native sent, with the final headers', async () => {
    const hook = jest.fn((_context: BeforeDispatchContext) => undefined);

    await beforeDispatch(hook);

    expect(hook).toHaveBeenCalledWith(BEFORE_CONTEXT);
  });

  it.each([
    ['null', null],
    ['false', false],
    ['a bare action string', 'SKIP'],
    ['a number', 0],
    ['an empty object', {}],
    ['an unknown action', { action: 'ABORT' }],
    ['a retry action', { action: 'RETRY' }],
  ])('answers FAILED for %s', async (_label, result) => {
    await expect(beforeDispatch(() => result)).resolves.toStrictEqual({
      status: FAILED,
      message:
        'beforeDispatch hook returned an invalid result: expected undefined or { action: PROCEED | SKIP }.',
    });
  });

  it('answers FAILED when the hook throws', async () => {
    await expect(
      beforeDispatch(() => {
        throw new Error('policy unavailable');
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'beforeDispatch hook failed: policy unavailable',
    });
  });

  it('answers FAILED when the hook rejects', async () => {
    await expect(
      beforeDispatch(() => Promise.reject(new Error('storage locked')))
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'beforeDispatch hook failed: storage locked',
    });
  });
});

describe('afterDispatch answers', () => {
  it('answers RESOLVED, with no payload, for a return of nothing', async () => {
    await expect(afterDispatch(() => undefined)).resolves.toStrictEqual({
      status: RESOLVED,
    });
  });

  it('ignores what the hook returns', async () => {
    await expect(
      afterDispatch(() => ({ action: BeforeDispatchAction.SKIP }))
    ).resolves.toStrictEqual({ status: RESOLVED });
  });

  it('answers RESOLVED once an async hook resolves', async () => {
    await expect(afterDispatch(async () => 42)).resolves.toStrictEqual({
      status: RESOLVED,
    });
  });

  it('receives the context native sent', async () => {
    const hook = jest.fn();

    await afterDispatch(hook);

    expect(hook).toHaveBeenCalledWith(AFTER_CONTEXT);
  });

  it('answers FAILED with the message when the hook throws', async () => {
    await expect(
      afterDispatch(() => {
        throw new Error('metrics down');
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'afterDispatch hook failed: metrics down',
    });
  });

  it('answers FAILED with String(reason) when the hook rejects with a non-Error', async () => {
    await expect(
      afterDispatch(() => Promise.reject('boom'))
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'afterDispatch hook failed: boom',
    });
  });
});

describe('the native-facing wrapper', () => {
  it('never throws synchronously, even when the consumer throws synchronously', async () => {
    await setRetryDecider(() => {
      throw new Error('sync boom');
    });

    expect(
      installedCallback('retryDecider')('retryDecider:1', DECIDER_CONTEXT)
    ).toBeUndefined();
    await flushAsync();

    expect(getMock().answerCallback).toHaveBeenCalledTimes(1);
    expect(getMock().answerCallback).toHaveBeenCalledWith('retryDecider:1', {
      status: FAILED,
      message: 'retry decider failed: sync boom',
    });
  });

  it('answers each request id native sent, also when requests overlap', async () => {
    await setRetryDecider(async ({ attempt }) => {
      // The first request answers last.
      if (attempt === 1) await flushAsync();
      return {
        action: attempt === 1 ? RetryAction.DROP : RetryAction.DEAD_LETTER,
      };
    });

    const [first, second] = await Promise.all([
      getMock().__invokeCallback('retryDecider', {
        ...DECIDER_CONTEXT,
        attempt: 1,
      }),
      getMock().__invokeCallback('retryDecider', {
        ...DECIDER_CONTEXT,
        attempt: 2,
      }),
    ]);

    expect(first).toEqual({
      status: RESOLVED,
      decision: { action: RetryAction.DROP },
    });
    expect(second).toEqual({
      status: RESOLVED,
      decision: { action: RetryAction.DEAD_LETTER },
    });
  });

  it('answers through answerCallback called as a method of the hybrid', async () => {
    // Nitro reads a hybrid method's native state from `this`.
    const mock = getMock();
    const receivers: unknown[] = [];
    const deliver = mock.answerCallback.getMockImplementation();
    mock.answerCallback.mockImplementationOnce(function (
      this: unknown,
      ...args: unknown[]
    ) {
      receivers.push(this);
      return deliver?.(...args);
    });

    await decide(() => ({ action: RetryAction.RETRY }));

    expect(receivers).toHaveLength(1);
    expect(receivers[0]).toBe(mock);
  });

  it('retries once with a bare FAILED when answerCallback throws', async () => {
    const mock = getMock();
    mock.answerCallback.mockImplementationOnce(() => {
      throw new TypeError('Value is not a string');
    });

    const answer = await decide(() => ({ action: RetryAction.RETRY }));

    expect(mock.answerCallback).toHaveBeenCalledTimes(2);
    expect(mock.answerCallback.mock.calls[0]?.[1]).toStrictEqual({
      status: RESOLVED,
      decision: { action: RetryAction.RETRY },
    });
    expect(mock.answerCallback.mock.calls[1]?.[0]).toBe(
      mock.answerCallback.mock.calls[0]?.[0]
    );
    expect(answer).toStrictEqual({
      status: FAILED,
      message:
        'retry decider: the answer could not be delivered: Value is not a string',
    });
  });

  it('retries once when answerCallback returns a rejected promise', async () => {
    const mock = getMock();
    mock.answerCallback.mockImplementationOnce(() =>
      Promise.reject(new Error('rejected'))
    );

    const answer = await decide(() => ({ action: RetryAction.RETRY }));

    expect(mock.answerCallback).toHaveBeenCalledTimes(2);
    expect(answer).toStrictEqual({
      status: FAILED,
      message: 'retry decider: the answer could not be delivered: rejected',
    });
  });

  it('gives up silently when the retry fails too, leaving native to time out', async () => {
    const mock = getMock();
    mock.answerCallback.mockImplementation(() => {
      throw new Error('converter gone');
    });
    await setRetryDecider(() => ({ action: RetryAction.RETRY }));

    expect(
      installedCallback('retryDecider')('retryDecider:9', DECIDER_CONTEXT)
    ).toBeUndefined();
    await flushAsync();

    expect(mock.answerCallback).toHaveBeenCalledTimes(2);
    expect(mock.answerCallback.mock.calls[1]?.[1]).toStrictEqual({
      status: FAILED,
      message:
        'retry decider: the answer could not be delivered: converter gone',
    });
  });

  it('handles a promise the retry returns, so its rejection is not left unhandled', async () => {
    const mock = getMock();
    const then = jest.fn(
      (_onFulfilled: unknown, onRejected: (reason: unknown) => void) => {
        onRejected(new Error('still failing'));
      }
    );
    mock.answerCallback
      .mockImplementationOnce(() => {
        throw new Error('first');
      })
      .mockImplementationOnce(() => ({ then }));
    await setRetryDecider(() => ({ action: RetryAction.RETRY }));

    installedCallback('retryDecider')('retryDecider:10', DECIDER_CONTEXT);
    await flushAsync();

    expect(mock.answerCallback).toHaveBeenCalledTimes(2);
    expect(then).toHaveBeenCalledTimes(1);
    expect(then).toHaveBeenCalledWith(
      expect.any(Function),
      expect.any(Function)
    );
  });

  it('does not answer while the consumer promise is pending, leaving the timeout to native', async () => {
    await setRetryDecider(() => new Promise<never>(() => undefined));

    installedCallback('retryDecider')('retryDecider:11', DECIDER_CONTEXT);
    await flushAsync();

    expect(getMock().answerCallback).not.toHaveBeenCalled();
  });

  it.each([
    ['an object without a prototype', () => Object.create(null) as unknown],
    [
      'an object whose toString throws',
      () => ({
        toString(): string {
          throw new Error('nope');
        },
      }),
    ],
  ])('answers FAILED when the consumer throws %s', async (_label, make) => {
    await expect(
      decide(() => {
        throw make();
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message:
        'retry decider failed: a value that could not be converted to a string',
    });
  });

  it('names an Error with an empty message by its name', async () => {
    await expect(
      decide(() => {
        throw new TypeError('');
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'retry decider failed: TypeError',
    });
  });

  it('cuts a long message to 1024 characters', async () => {
    const answer = (await decide(() => {
      throw new Error('x'.repeat(5_000));
    })) as { message: string };

    expect(answer.message).toHaveLength(1_024);
    expect(answer.message.startsWith('retry decider failed: xxx')).toBe(true);
  });
});

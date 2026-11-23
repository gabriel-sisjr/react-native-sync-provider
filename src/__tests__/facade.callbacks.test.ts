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
import { toHeadersAnswer } from '../utils/callbackBridge';

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

/**
 * The headers an answer must carry: a copy without a prototype. Since
 * `toStrictEqual` compares constructors, a copy that kept `Object.prototype`
 * fails against it.
 */
const headerCopy = (headers: Record<string, string>): Record<string, string> =>
  Object.assign(Object.create(null) as Record<string, string>, headers);

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
    [-0, 0],
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

    expect(answer).toStrictEqual({
      status: RESOLVED,
      headers: headerCopy(headers),
    });
    expect((answer as { headers: unknown }).headers).not.toBe(headers);
    expect(
      Object.getPrototypeOf((answer as { headers: unknown }).headers)
    ).toBe(null);
  });

  it('keeps a __proto__ key as an own header of the copy', async () => {
    const answer = (await resolveHeaders(
      () => JSON.parse('{"__proto__":"x","X-A":"1"}') as unknown
    )) as { headers: Record<string, string> };

    expect(Object.getPrototypeOf(answer.headers)).toBe(null);
    expect(Object.keys(answer.headers)).toStrictEqual(['__proto__', 'X-A']);
    expect(
      Object.getOwnPropertyDescriptor(answer.headers, '__proto__')?.value
    ).toBe('x');
  });

  it('sends no header that a polluted Object.prototype adds', () => {
    // Native reads every enumerable key, inherited ones included, as `for…in`
    // does. The pollution lasts only for this synchronous block.
    const names: string[] = [];
    // eslint-disable-next-line no-extend-native -- polluted on purpose, and removed below
    Object.defineProperty(Object.prototype, 'X-Polluted', {
      value: 'leak',
      enumerable: true,
      configurable: true,
    });
    try {
      const answer = toHeadersAnswer({ 'X-Tenant': 'acme' });
      for (const name in answer.headers ?? {}) names.push(name);
    } finally {
      delete (Object.prototype as Record<string, unknown>)['X-Polluted'];
    }

    expect(names).toStrictEqual(['X-Tenant']);
  });

  it('passes async headers', async () => {
    await expect(
      resolveHeaders(async () => ({ 'X-Tenant': 'acme' }))
    ).resolves.toStrictEqual({
      status: RESOLVED,
      headers: headerCopy({ 'X-Tenant': 'acme' }),
    });
  });

  it('passes an empty object, which adds no header', async () => {
    await expect(resolveHeaders(() => ({}))).resolves.toStrictEqual({
      status: RESOLVED,
      headers: headerCopy({}),
    });
  });

  it('accepts an object without a prototype', async () => {
    const headers = Object.assign(Object.create(null) as object, {
      'X-Tenant': 'acme',
    });

    await expect(resolveHeaders(() => headers)).resolves.toStrictEqual({
      status: RESOLVED,
      headers: headerCopy({ 'X-Tenant': 'acme' }),
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
        'header resolver returned an invalid result: expected a plain object whose values are all strings.',
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

  it.each([
    ['a string', () => '{"refresh_token":"rt-SECRET"}', 'string'],
    ['an object', () => ({ refresh_token: 'rt-SECRET' }), 'object'],
    [
      'an object with a message',
      () => ({ message: 'rt-SECRET', status: 401 }),
      'object',
    ],
  ])(
    'never quotes %s that the resolver throws or rejects with',
    async (_label, make, type) => {
      const expected = {
        status: FAILED,
        message: `header resolver failed: a thrown ${type}`,
      };

      const thrown = await resolveHeaders(() => {
        throw make();
      });
      const rejected = await resolveHeaders(() => Promise.reject(make()));

      expect(thrown).toStrictEqual(expected);
      expect(rejected).toStrictEqual(expected);
      expect(JSON.stringify([thrown, rejected])).not.toContain('rt-SECRET');
    }
  );

  it('never converts a thrown object to a string', async () => {
    const toString = jest.fn(() => 'rt-SECRET');

    const answer = await resolveHeaders(() => Promise.reject({ toString }));

    expect(answer).toStrictEqual({
      status: FAILED,
      message: 'header resolver failed: a thrown object',
    });
    expect(toString).not.toHaveBeenCalled();
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

  // Only a result with an `action` key asks for something; anything else
  // proceeds, so an observer that returns some value never defers the item.
  it.each([
    ['null', null],
    ['true', true],
    ['false', false],
    ['0', 0],
    ['a number', 3],
    ['an empty string', ''],
    ['a bare action string', BeforeDispatchAction.SKIP],
    ['an array', [BeforeDispatchAction.SKIP]],
    ['an empty object', {}],
    ['an object without an action key', { reason: 'cellular' }],
  ])('maps %s, which has no action key, to PROCEED', async (_label, result) => {
    await expect(beforeDispatch(() => result)).resolves.toStrictEqual(proceed);
  });

  it('maps an async number to PROCEED', async () => {
    await expect(beforeDispatch(async () => 3)).resolves.toStrictEqual(proceed);
  });

  it('proceeds for a void-typed call that returns a number at runtime', async () => {
    const seen: unknown[] = [];
    const track: (x: unknown) => void = (x) => seen.push(x);

    // No cast: a call typed `void` is a valid hook body.
    await setBeforeDispatch((c) => track(c));

    await expect(
      getMock().__invokeCallback('beforeDispatch', BEFORE_CONTEXT)
    ).resolves.toStrictEqual(proceed);
    expect(seen).toStrictEqual([BEFORE_CONTEXT]);
  });

  it('reads an action the result inherits', async () => {
    class Skip {
      get action(): BeforeDispatchAction {
        return BeforeDispatchAction.SKIP;
      }
    }

    await expect(beforeDispatch(() => new Skip())).resolves.toStrictEqual({
      status: RESOLVED,
      beforeDispatch: { action: BeforeDispatchAction.SKIP },
    });
  });

  it.each([
    ['an unknown action', { action: 'NOPE' }],
    ['another unknown action', { action: 'ABORT' }],
    ['a retry action', { action: 'RETRY' }],
    ['a lowercase action', { action: 'skip' }],
    ['an undefined action', { action: undefined }],
    ['a null action', { action: null }],
  ])('answers FAILED for %s', async (_label, result) => {
    await expect(beforeDispatch(() => result)).resolves.toStrictEqual({
      status: FAILED,
      message:
        'beforeDispatch hook returned an invalid result: its action must be PROCEED or SKIP.',
    });
  });

  it('answers FAILED for an async unknown action', async () => {
    await expect(
      beforeDispatch(async () => ({ action: 'NOPE' }))
    ).resolves.toStrictEqual({
      status: FAILED,
      message:
        'beforeDispatch hook returned an invalid result: its action must be PROCEED or SKIP.',
    });
  });

  it('answers FAILED when looking for the action key throws', async () => {
    const result = new Proxy(
      {},
      {
        has(): boolean {
          throw new Error('has trap');
        },
      }
    );

    await expect(beforeDispatch(() => result)).resolves.toStrictEqual({
      status: FAILED,
      message: 'beforeDispatch hook failed: has trap',
    });
  });

  it('answers FAILED when reading the action throws', async () => {
    await expect(
      beforeDispatch(() => ({
        get action(): string {
          throw new Error('getter');
        },
      }))
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'beforeDispatch hook failed: getter',
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

  it('accepts an observer whose expression body returns a value', async () => {
    const list: AfterDispatchContext[] = [];

    // No casts: both lines typecheck only because the hook may return anything.
    await setAfterDispatch((c) => list.push(c));
    await expect(
      getMock().__invokeCallback('afterDispatch', AFTER_CONTEXT)
    ).resolves.toStrictEqual({ status: RESOLVED });

    await setAfterDispatch(async (c) => list.push(c));
    await expect(
      getMock().__invokeCallback('afterDispatch', AFTER_CONTEXT)
    ).resolves.toStrictEqual({ status: RESOLVED });

    expect(list).toStrictEqual([AFTER_CONTEXT, AFTER_CONTEXT]);
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

  it('answers FAILED with the type only when the hook rejects with a non-Error', async () => {
    await expect(
      afterDispatch(() => Promise.reject('boom'))
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'afterDispatch hook failed: a thrown string',
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

  it('retries once with a constant FAILED message when answerCallback throws', async () => {
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
    // The native error is never quoted: it could quote the answer.
    expect(answer).toStrictEqual({
      status: FAILED,
      message: 'retry decider: the answer could not be delivered',
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
      message: 'retry decider: the answer could not be delivered',
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
      message: 'retry decider: the answer could not be delivered',
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

  // A thrown value that is not an Error can be a token or a server response,
  // so only its type reaches the message, and nothing converts it.
  it.each([
    ['a string', () => 's3cr3t', 'string'],
    ['a number', () => 42, 'number'],
    ['a boolean', () => false, 'boolean'],
    ['a bigint', () => BigInt(7), 'bigint'],
    ['a symbol', () => Symbol('s3cr3t'), 'symbol'],
    ['undefined', () => undefined, 'undefined'],
    ['null', () => null, 'null'],
    ['an object with a message', () => ({ message: 's3cr3t' }), 'object'],
    ['an array', () => ['s3cr3t'], 'object'],
    [
      'an object without a prototype',
      () => Object.create(null) as unknown,
      'object',
    ],
    [
      'an object whose toString throws',
      () => ({
        toString(): string {
          throw new Error('nope');
        },
      }),
      'object',
    ],
    ['a function', () => () => 's3cr3t', 'function'],
    [
      'a revoked Proxy',
      () => {
        const { proxy, revoke } = Proxy.revocable({}, {});
        revoke();
        return proxy;
      },
      'object',
    ],
  ])(
    'describes %s the consumer throws by its type only',
    async (_label, make, type) => {
      await expect(
        decide(() => {
          throw make();
        })
      ).resolves.toStrictEqual({
        status: FAILED,
        message: `retry decider failed: a thrown ${type}`,
      });
    }
  );

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

  it('names an Error whose message is not a string by its name', async () => {
    await expect(
      decide(() => {
        throw Object.assign(new RangeError('x'), { message: 42 });
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'retry decider failed: RangeError',
    });
  });

  it.each([
    [
      'neither its message nor its name is a usable string',
      () => Object.assign(new Error(''), { name: '' }),
    ],
    [
      'reading its message throws',
      () =>
        Object.defineProperty(new Error('x'), 'message', {
          get(): string {
            throw new Error('getter');
          },
        }),
    ],
  ])('describes an Error as "a thrown Error" when %s', async (_label, make) => {
    await expect(
      decide(() => {
        throw make();
      })
    ).resolves.toStrictEqual({
      status: FAILED,
      message: 'retry decider failed: a thrown Error',
    });
  });

  it('cuts a long message to 1024 characters', async () => {
    const answer = (await decide(() => {
      throw new Error('x'.repeat(5_000));
    })) as { message: string };

    expect(answer.message).toHaveLength(1_024);
    expect(answer.message.startsWith('retry decider failed: xxx')).toBe(true);
  });

  it('never ends a cut message in half a surrogate pair', async () => {
    const prefix = 'retry decider failed: ';
    // The emoji's first half lands on the last character the cut keeps.
    const pad = 'x'.repeat(1_024 - prefix.length - 1);

    const answer = (await decide(() => {
      throw new Error(`${pad}\u{1F600}tail`);
    })) as { message: string };

    expect(answer.message).toBe(`${prefix}${pad}`);
  });

  it('keeps a surrogate pair that ends at the cut', async () => {
    const prefix = 'retry decider failed: ';
    const pad = 'x'.repeat(1_024 - prefix.length - 2);

    const answer = (await decide(() => {
      throw new Error(`${pad}\u{1F600}tail`);
    })) as { message: string };

    expect(answer.message).toBe(`${prefix}${pad}\u{1F600}`);
    expect(answer.message).toHaveLength(1_024);
  });
});

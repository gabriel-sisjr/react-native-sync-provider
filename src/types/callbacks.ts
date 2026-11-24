/**
 * Public types of the retry decider and the dispatch interceptors.
 *
 * - The retry decider (`setRetryDecider`) can veto a retry, or change its
 *   delay, for a failed attempt that the declarative {@link RetryPolicy} would
 *   retry.
 * - The header resolver (`setHeaderResolver`) supplies headers for each
 *   request right before it is sent.
 * - `beforeDispatch` (`setBeforeDispatch`) sees each request's merged headers
 *   and can defer the item.
 * - `afterDispatch` (`setAfterDispatch`) observes each request's outcome.
 *
 * Every callback runs only in a live JS runtime that installed it, and each
 * answer must arrive within {@link SyncOptions.callbackTimeoutMs}. In a process
 * where nothing was installed, such as a cold background worker or a BGTask
 * launch, requests go out with the persisted `defaultHeaders` and the
 * declarative policy decides retries. A runtime that goes away, on a reload for
 * example, leaves its installs in place until a newer runtime first uses the
 * library, and they fail meanwhile: the header resolver and `beforeDispatch`
 * defer their items, the retry decider leaves the decision to the policy, and
 * `afterDispatch` is only logged. Make the `set…` calls the first library
 * calls at startup: see `setRetryDecider`.
 *
 * The context, result and decision interfaces cross the native bridge, so
 * their fields are flat and their records hold strings only. The four function
 * types are the signatures the facade accepts from consumers; they never cross
 * the bridge themselves. The facade calls them without a `this`, so pass an
 * arrow function or a bound method, not `auth.getHeaders`.
 *
 * A throw or a rejection reaches native as a failure message. An `Error` keeps
 * its message, and native logs up to 256 characters of it: privately on iOS,
 * in the app's logcat on Android. Keep tokens and personal data out of it. Any
 * other thrown value is described by its type only. Never await `flush()`
 * inside a callback: on Android the cycle stalls until the timeout, and on iOS
 * it returns the last result at once.
 */
import type { SyncErrorCode } from '../errors/SyncErrorCode';
import type {
  BeforeDispatchAction,
  DispatchOutcome,
  HttpMethod,
  RetryAction,
} from './enums';

/* -------------------------------------------------------------------------- */
/*                                Retry decider                               */
/* -------------------------------------------------------------------------- */

/** What the retry decider receives for a failed attempt. */
export interface RetryDeciderContext {
  /** ULID of the item whose attempt failed. */
  itemId: string;
  /**
   * The 1-based attempt that just failed, the number its `ITEM_RETRYING` or
   * `ITEM_FAILED` event carries.
   */
  attempt: number;
  /** That attempt's failure code, for example `SERVER_ERROR` or `TIMEOUT`. */
  errorCode: SyncErrorCode;
  /** The HTTP status code, present when an HTTP answer came back. */
  statusCode?: number;
  /** The item's request URL. */
  url: string;
  /** The item's HTTP method. */
  method: HttpMethod;
  /** The item's `SyncItemInput.metadata`, when it has any. */
  metadata?: Record<string, string>;
}

/** What the retry decider answers for a failed attempt. */
export interface RetryDecision {
  /** What to do with the item. See {@link RetryAction}. */
  action: RetryAction;
  /**
   * For `RETRY` only: the delay before the next attempt, in ms, replacing the
   * policy's. It counts from the decision, not from the end of the attempt. It
   * is used as given, with no jitter and no {@link RetryPolicy.maxDelayMs} cap,
   * but within the engine's ceilings: at most 2^53 - 1 ms, and a deadline no
   * later than the largest JavaScript `Date`. Fractions truncate toward zero.
   * Without it the policy's delay applies, and so it does for a value that is
   * not finite or is negative, which native logs as a warning.
   */
  delayMs?: number;
}

/* -------------------------------------------------------------------------- */
/*                            Dispatch interceptors                           */
/* -------------------------------------------------------------------------- */

/** What the header resolver receives, right before a request is sent. */
export interface HeaderResolverContext {
  /** ULID of the item about to be sent. */
  itemId: string;
  /** The 1-based attempt about to be made. */
  attempt: number;
  /** The item's request URL. */
  url: string;
  /** The item's HTTP method. */
  method: HttpMethod;
  /** The item's `SyncItemInput.metadata`, when it has any. */
  metadata?: Record<string, string>;
}

/**
 * What the `beforeDispatch` hook receives: the request as it will be sent,
 * after the header resolver. The body is not included.
 */
export interface BeforeDispatchContext {
  /** ULID of the item about to be sent. */
  itemId: string;
  /** The 1-based attempt about to be made. */
  attempt: number;
  /** The item's request URL. */
  url: string;
  /** The item's HTTP method. */
  method: HttpMethod;
  /**
   * The merged headers: `SyncOptions.defaultHeaders`, then the header
   * resolver's, then the item's own, each layer overriding the one before.
   * Names match case-insensitively, and the winning layer's spelling is kept.
   *
   * The `Content-Type` the engine adds when no layer sets one is left out: it
   * comes from `contentType`, or on Android from its `application/json`
   * default for a body. On Android the body's media type also replaces a
   * layer's `Content-Type` on the wire, so this map can show a `Content-Type`
   * the request does not carry.
   */
  headers: Record<string, string>;
  /** The item's `SyncItemInput.contentType`, as stored, when it has one. */
  contentType?: string;
  /** The item's `SyncItemInput.metadata`, when it has any. */
  metadata?: Record<string, string>;
}

/**
 * What the `beforeDispatch` hook answers. Only a result with an `action` key
 * asks for something: any other value, nothing included, proceeds. See
 * {@link BeforeDispatchHook}.
 */
export interface BeforeDispatchResult {
  /** Send the request, or defer the item. See {@link BeforeDispatchAction}. */
  action: BeforeDispatchAction;
}

/**
 * What the `afterDispatch` hook receives for a request that went out, after
 * the outcome is stored and the item's `ITEM_*` event is emitted. That it
 * arrives after the event is best effort, not a guarantee. It never carries
 * headers or a body.
 */
export interface AfterDispatchContext {
  /** ULID of the item that was sent. */
  itemId: string;
  /** The 1-based attempt that was made. */
  attempt: number;
  /** How the request ended. See {@link DispatchOutcome}. */
  outcome: DispatchOutcome;
  /** The HTTP status code, present when an HTTP answer came back. */
  statusCode?: number;
  /**
   * The code the item's event carried, present unless the outcome is
   * `SUCCEEDED`: for example `SERVER_ERROR`, `UNAUTHORIZED` for a credential
   * hold, or `RETRY_DECIDER_REJECTED` after a retry decider's `DROP` or
   * `DEAD_LETTER`.
   */
  errorCode?: SyncErrorCode;
  /** The item's request URL. */
  url: string;
  /** The item's HTTP method. */
  method: HttpMethod;
  /** The item's `SyncItemInput.metadata`, when it has any. */
  metadata?: Record<string, string>;
  /**
   * How long the request itself took, in whole milliseconds. Callback waits
   * are not included.
   */
  durationMs: number;
}

/* -------------------------------------------------------------------------- */
/*                             Consumer functions                             */
/* -------------------------------------------------------------------------- */

/**
 * A retry decider, installed with `setRetryDecider`. It may return its decision
 * or a promise of it.
 *
 * It is consulted only for a failed attempt that the declarative
 * {@link RetryPolicy} would retry: a retryable failure with attempts left,
 * outside a credential hold. Never for a success, a failure that is not
 * retried (a status missing from `retryOnStatusCodes`, or a `401` or `403`
 * under credentials set in the process), a spent budget, a credential hold or
 * an item that was not sent. A throw, a rejection, an invalid result or no
 * answer within {@link SyncOptions.callbackTimeoutMs} leaves the decision to
 * the policy, with a native log line and no event. Once it is consulted, the
 * delay counts from the decision, whichever side made it.
 *
 * @example
 * await setRetryDecider(({ attempt, statusCode, metadata }) => {
 *   if (metadata?.kind === 'analytics' && attempt >= 2) {
 *     return { action: RetryAction.DROP };
 *   }
 *   if (statusCode === 503) {
 *     return { action: RetryAction.RETRY, delayMs: 5 * 60 * 1000 };
 *   }
 *   return { action: RetryAction.RETRY };
 * });
 */
export type RetryDecider = (
  context: RetryDeciderContext
) => RetryDecision | Promise<RetryDecision>;

/**
 * A header resolver, installed with `setHeaderResolver`. It may return the
 * headers, as a plain object whose values are all strings, or a promise of
 * them. A plain object is an object literal or one without a prototype; a
 * class instance, a `Map` or an array is an invalid result. Each name may use
 * only the characters `!` to `~`, and each value only tab and the characters
 * from space to `~`: anything else is an invalid result too.
 *
 * Called right before each request, retries included. Its headers override
 * `SyncOptions.defaultHeaders`, and the item's own headers override them, with
 * names matching case-insensitively. Do not return two names that differ only
 * by case: which one wins is not specified. A throw, a rejection, an invalid
 * result or no answer within {@link SyncOptions.callbackTimeoutMs} sends
 * nothing: the item is deferred without spending an attempt, with
 * `ITEM_RETRYING` and `INTERCEPTOR_FAILED`.
 *
 * Its headers do not change how a `401` or `403` is handled: that still
 * depends only on where `defaultHeaders` came from. On Android up to
 * `SyncOptions.batchSize` calls can run at once, so share one token refresh
 * between them instead of starting one per call.
 *
 * @example
 * let refreshing: Promise<string> | undefined;
 * const freshToken = () =>
 *   (refreshing ??= getFreshToken().finally(() => (refreshing = undefined)));
 *
 * await setHeaderResolver(async () => ({
 *   Authorization: `Bearer ${await freshToken()}`,
 * }));
 */
export type HeaderResolver = (
  context: HeaderResolverContext
) => Record<string, string> | Promise<Record<string, string>>;

/**
 * A `beforeDispatch` hook, installed with `setBeforeDispatch`. It may return
 * its result, nothing, or a promise of either.
 *
 * Only a result with an `action` key asks for something. Anything else means
 * `PROCEED`: nothing, `null`, a primitive, an array, or an object without
 * `action`. So an observer such as `(c) => track(c)` proceeds even when
 * `track`, though typed `void`, returns a value at runtime. A bare `'SKIP'`
 * string is a primitive too, and proceeds: return
 * `{ action: BeforeDispatchAction.SKIP }`.
 *
 * Called after the header resolver, right before each request, and not at all
 * when the header resolver failed. `SKIP` defers the item the same way a failed
 * header resolver does, and so does an `action` other than `PROCEED` or
 * `SKIP`, a throw, a rejection or no answer within
 * {@link SyncOptions.callbackTimeoutMs}.
 *
 * @example
 * await setBeforeDispatch(({ url }) =>
 *   isPaused(url) ? { action: BeforeDispatchAction.SKIP } : undefined
 * );
 */
export type BeforeDispatchHook = (
  context: BeforeDispatchContext
) => BeforeDispatchResult | void | Promise<BeforeDispatchResult | void>;

/**
 * An `afterDispatch` hook, installed with `setAfterDispatch`, that observes
 * each request that went out. It may return any value or a promise of one,
 * so an expression body such as `(c) => list.push(c)` is fine. Dispatch never
 * waits for it and ignores what it returns or resolves with, so `flush()` may
 * resolve before it runs; a throw, a rejection or no answer in time is only
 * logged. It is not called for an iOS background upload that completes after a
 * relaunch, since no JS runs then.
 *
 * @example
 * await setAfterDispatch(({ url, outcome, durationMs }) => {
 *   metrics.record(url, outcome, durationMs);
 * });
 */
export type AfterDispatchHook = (context: AfterDispatchContext) => unknown;

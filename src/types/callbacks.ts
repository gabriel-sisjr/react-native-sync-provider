/**
 * Public types of the retry decider and the dispatch interceptors.
 *
 * - The retry decider (`setRetryDecider`) can veto a retry, or change its
 *   delay, for a failed attempt that the declarative {@link RetryPolicy} would
 *   retry.
 * - The header resolver (`setHeaderResolver`) supplies headers for each
 *   request right before it is sent.
 * - `beforeDispatch` (`setBeforeDispatch`) sees each request's final headers
 *   and can defer the item.
 * - `afterDispatch` (`setAfterDispatch`) observes each request's outcome.
 *
 * Every callback runs only while JS is alive, in the process that installed
 * it. With no JS running, for example in a background launch, the engine
 * dispatches with the static headers and the declarative policy alone. Each
 * answer must arrive within {@link SyncOptions.callbackTimeoutMs}.
 *
 * The context, result and decision interfaces cross the native bridge, so
 * their fields are flat and their records hold strings only. The four function
 * types are the signatures the facade accepts from consumers; they never cross
 * the bridge themselves.
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
   * policy's. It is used as given, with no jitter and no
   * {@link RetryPolicy.maxDelayMs} cap, but within the engine's ceilings: at
   * most 2^53 - 1 ms, and a deadline no later than the largest JavaScript
   * `Date`. Fractions truncate toward zero. A value that is not finite or is
   * negative is ignored, and the policy's delay applies.
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
   * The final headers the request will carry: `SyncOptions.defaultHeaders`,
   * then the header resolver's, then the item's own, a later source winning on
   * a key collision.
   */
  headers: Record<string, string>;
  /** The item's `SyncItemInput.contentType`, when it has one. */
  contentType?: string;
  /** The item's `SyncItemInput.metadata`, when it has any. */
  metadata?: Record<string, string>;
}

/** What the `beforeDispatch` hook answers. Returning nothing proceeds. */
export interface BeforeDispatchResult {
  /** Send the request, or defer the item. See {@link BeforeDispatchAction}. */
  action: BeforeDispatchAction;
}

/**
 * What the `afterDispatch` hook receives for a request that went out, after
 * the outcome is stored and the item's event emitted. It never carries headers
 * or a body.
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
   * The failure code, present when the request failed, for example
   * `SERVER_ERROR`, or `RETRY_DECIDER_REJECTED` after a retry decider's `DROP`
   * or `DEAD_LETTER`.
   */
  errorCode?: SyncErrorCode;
  /** The item's request URL. */
  url: string;
  /** The item's HTTP method. */
  method: HttpMethod;
  /** The item's `SyncItemInput.metadata`, when it has any. */
  metadata?: Record<string, string>;
  /** How long the request itself took, in whole milliseconds. */
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
 * outside a credential hold. A throw, a rejection, an invalid result or no
 * answer within {@link SyncOptions.callbackTimeoutMs} leaves the decision to
 * the policy.
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
 * class instance, a `Map` or an array is an invalid result.
 *
 * Called right before each request, retries included. Its headers override
 * `SyncOptions.defaultHeaders`, and the item's own headers override them. A
 * throw, a rejection, an invalid result or no answer within
 * {@link SyncOptions.callbackTimeoutMs} sends nothing: the item is deferred
 * without spending an attempt, with `ITEM_RETRYING` and `INTERCEPTOR_FAILED`.
 *
 * @example
 * await setHeaderResolver(async () => ({
 *   Authorization: `Bearer ${await getFreshToken()}`,
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
 * Called after the header resolver, right before each request. `SKIP` defers
 * the item the same way a failed header resolver does, and so does an `action`
 * other than `PROCEED` or `SKIP`, a throw, a rejection or no answer within
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
 * waits for it and ignores what it returns or resolves with; a throw or a
 * rejection is only logged.
 *
 * @example
 * await setAfterDispatch(({ url, outcome, durationMs }) => {
 *   metrics.record(url, outcome, durationMs);
 * });
 */
export type AfterDispatchHook = (context: AfterDispatchContext) => unknown;

/**
 * The JS half of the native callback bridge.
 *
 * Native calls every installed callback as a `void` JS function that receives
 * a native request id and a context. The wrapper built here runs the
 * consumer's function, sync or async, turns its result into a
 * {@link CallbackAnswer}, and answers the request through the spec's
 * `answerCallback`. Native therefore sees a `RESOLVED` answer, a `FAILED` one,
 * or none within `SyncOptions.callbackTimeoutMs`, and never a JS throw.
 *
 * The wrapper never throws, synchronously or through an unhandled rejection:
 * a throw out of a `void` JS callback is a fatal JS error in React Native.
 *
 * @internal
 */
import { SyncErrorCode } from '../errors/SyncErrorCode';
import type { SyncProvider } from '../SyncProvider.nitro';
import { CallbackAnswerStatus } from '../types/bridge';
import type { CallbackAnswer } from '../types/bridge';
import type { RetryDecision } from '../types/callbacks';
import { BeforeDispatchAction, RetryAction } from '../types/enums';

import { throwOnIssues } from './validation';

/** Longest `message` an answer carries; native writes it to a log line. */
const MAX_MESSAGE_LENGTH = 1024;

const RETRY_ACTIONS: ReadonlySet<unknown> = new Set(Object.values(RetryAction));
const BEFORE_DISPATCH_ACTIONS: ReadonlySet<unknown> = new Set(
  Object.values(BeforeDispatchAction)
);

/**
 * One callback kind: how failure messages name it, and how the consumer
 * function's result becomes an answer.
 *
 * @internal
 */
export interface CallbackKind {
  /** Name used in failure messages, for example `retry decider`. */
  readonly label: string;
  /** Turn what the consumer's function returned into an answer. */
  readonly normalize: (result: unknown) => CallbackAnswer;
}

/** The native-facing shape of every installed callback. */
type NativeCallback<C> = (requestId: string, context: C) => void;

/** Where an answer goes: the hybrid's `answerCallback`, called as a method. */
type AnswerTarget = Pick<SyncProvider, 'answerCallback'>;

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (!isObject(value) || Array.isArray(value)) return false;
  const prototype: unknown = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function isRetryAction(value: unknown): value is RetryAction {
  return RETRY_ACTIONS.has(value);
}

function isBeforeDispatchAction(value: unknown): value is BeforeDispatchAction {
  return BEFORE_DISPATCH_ACTIONS.has(value);
}

/** Whether `code` is the first half of a UTF-16 surrogate pair. */
function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff;
}

/**
 * A `FAILED` answer, its message cut to {@link MAX_MESSAGE_LENGTH} characters.
 * A cut that would end on the first half of a surrogate pair drops that half
 * too, so the message never ends in half a character.
 */
function failed(message: string): CallbackAnswer {
  let kept = message.slice(0, MAX_MESSAGE_LENGTH);
  if (
    kept.length < message.length &&
    isHighSurrogate(kept.charCodeAt(kept.length - 1))
  ) {
    kept = kept.slice(0, -1);
  }
  return { status: CallbackAnswerStatus.FAILED, message: kept };
}

/** The type a thrown value is described by: its `typeof`, with `null` apart. */
function typeName(value: unknown): string {
  return value === null ? 'null' : typeof value;
}

/** Whether `value` is an `Error`. A Proxy whose `instanceof` throws is not. */
function isError(value: unknown): value is Error {
  try {
    return value instanceof Error;
  } catch {
    return false;
  }
}

/** `value` when it is a string that is not empty. */
function nonEmptyString(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}

/**
 * Describe a thrown or rejected value for a native log line.
 *
 * - An `Error` is described by its `message`, or by its `name` when the
 *   message is empty or not a string, or as `a thrown Error` when neither is
 *   usable or reading them throws.
 * - Anything else is described by its type only, for example
 *   `a thrown string` or `a thrown object`. Its content can be a token or a
 *   server response, so it is never quoted, nor converted to a string.
 *
 * Never throws.
 *
 * @internal
 */
export function describeError(error: unknown): string {
  if (!isError(error)) return `a thrown ${typeName(error)}`;
  try {
    return (
      nonEmptyString(error.message) ??
      nonEmptyString(error.name) ??
      'a thrown Error'
    );
  } catch {
    return 'a thrown Error';
  }
}

/**
 * Normalize a retry decider's result. It must be `{ action, delayMs? }` with a
 * {@link RetryAction}. A `delayMs` that is not a finite number of at least `0`
 * is dropped and the action kept; fractions truncate toward zero.
 *
 * @internal
 */
export function toRetryDecisionAnswer(result: unknown): CallbackAnswer {
  if (isObject(result)) {
    const { action, delayMs } = result;
    if (isRetryAction(action)) {
      const decision: RetryDecision = { action };
      if (
        typeof delayMs === 'number' &&
        Number.isFinite(delayMs) &&
        delayMs >= 0
      ) {
        // `|| 0` turns a `-0` into `0`.
        decision.delayMs = Math.trunc(delayMs) || 0;
      }
      return { status: CallbackAnswerStatus.RESOLVED, decision };
    }
  }
  return failed(
    'retry decider returned an invalid result: expected { action: RETRY | DROP | DEAD_LETTER, delayMs? }.'
  );
}

/**
 * Copy header entries onto an object without a prototype. Native reads every
 * enumerable key, inherited ones included, so a copy that kept
 * `Object.prototype` would also send what was added to it. Each entry is
 * defined, not assigned, so even a `__proto__` key stays a header.
 */
function copyHeaders(
  entries: ReadonlyArray<[string, string]>
): Record<string, string> {
  const headers: Record<string, string> = Object.create(null);
  for (const [name, value] of entries) {
    Object.defineProperty(headers, name, {
      value,
      enumerable: true,
      writable: true,
      configurable: true,
    });
  }
  return headers;
}

/**
 * Normalize a header resolver's result. It must be a plain object whose
 * values are all strings. The answer carries a copy of its own entries,
 * without a prototype. The failure message never quotes a header, because
 * headers can carry credentials.
 *
 * @internal
 */
export function toHeadersAnswer(result: unknown): CallbackAnswer {
  if (isPlainObject(result)) {
    const entries = Object.entries(result);
    const strings = entries.filter(
      (entry): entry is [string, string] => typeof entry[1] === 'string'
    );
    if (strings.length === entries.length) {
      return {
        status: CallbackAnswerStatus.RESOLVED,
        headers: copyHeaders(strings),
      };
    }
  }
  return failed(
    'header resolver returned an invalid result: expected a plain object whose values are all strings.'
  );
}

/**
 * The action a `beforeDispatch` result asks for, before it is checked. Only an
 * object with an `action` key, own or inherited, asks for one; anything else
 * asks for `PROCEED`.
 */
function requestedAction(result: unknown): unknown {
  return isObject(result) && 'action' in result
    ? result.action
    : BeforeDispatchAction.PROCEED;
}

/**
 * Normalize a `beforeDispatch` hook's result. A result without an `action`
 * key means `PROCEED`: `undefined`, `null`, a primitive, an array, or an object
 * without `action`. An `action` must be `PROCEED` or `SKIP`, and any other
 * value is `FAILED`. The failure message never quotes the result.
 *
 * @internal
 */
export function toBeforeDispatchAnswer(result: unknown): CallbackAnswer {
  const action = requestedAction(result);
  if (isBeforeDispatchAction(action)) {
    return {
      status: CallbackAnswerStatus.RESOLVED,
      beforeDispatch: { action },
    };
  }
  return failed(
    'beforeDispatch hook returned an invalid result: its action must be PROCEED or SKIP.'
  );
}

/**
 * Normalize an `afterDispatch` hook's result, which is ignored: any normal
 * return is `RESOLVED`, with no payload.
 *
 * @internal
 */
export function toAfterDispatchAnswer(): CallbackAnswer {
  return { status: CallbackAnswerStatus.RESOLVED };
}

/** @internal */
export const RETRY_DECIDER: CallbackKind = {
  label: 'retry decider',
  normalize: toRetryDecisionAnswer,
};

/** @internal */
export const HEADER_RESOLVER: CallbackKind = {
  label: 'header resolver',
  normalize: toHeadersAnswer,
};

/** @internal */
export const BEFORE_DISPATCH: CallbackKind = {
  label: 'beforeDispatch hook',
  normalize: toBeforeDispatchAnswer,
};

/** @internal */
export const AFTER_DISPATCH: CallbackKind = {
  label: 'afterDispatch hook',
  normalize: toAfterDispatchAnswer,
};

/** Run the consumer's function and normalize its result. Never rejects. */
async function settle(
  kind: CallbackKind,
  invoke: () => unknown
): Promise<CallbackAnswer> {
  try {
    return kind.normalize(await invoke());
  } catch (error) {
    return failed(`${kind.label} failed: ${describeError(error)}`);
  }
}

/** Send one answer. Resolves with whether it was delivered. Never rejects. */
async function deliver(
  target: AnswerTarget,
  requestId: string,
  answer: CallbackAnswer
): Promise<boolean> {
  try {
    // `answerCallback` is synchronous in the spec. Awaiting its result also
    // covers a variant that returns a promise: a rejection then lands in this
    // catch, like a synchronous throw, and is never left unhandled.
    await target.answerCallback(requestId, answer);
    return true;
  } catch {
    return false;
  }
}

/** Answer one native request. Never rejects. */
async function answerRequest(
  target: AnswerTarget,
  kind: CallbackKind,
  requestId: string,
  invoke: () => unknown
): Promise<void> {
  const answer = await settle(kind, invoke);
  if (await deliver(target, requestId, answer)) return;
  // The answer did not convert, for example. A FAILED answer lets native fall
  // back at once instead of at its timeout; if that fails too, native times
  // out on its own. Its message is constant: the native error could quote the
  // answer, and the answer can carry headers.
  await deliver(
    target,
    requestId,
    failed(`${kind.label}: the answer could not be delivered`)
  );
}

/**
 * Build the native-facing callback for one consumer function.
 *
 * The callback returns at once and never throws. It runs `run` with the
 * context, normalizes the result with `kind`, and answers `requestId` through
 * `target.answerCallback`. A throw or a rejection of `run`, or an invalid
 * result, is answered `FAILED` with a message. If the answer cannot be
 * delivered, it retries once with a `FAILED` answer whose message is constant,
 * then gives up silently.
 *
 * @param target — the hybrid whose `answerCallback` receives the answers.
 * @param kind — the callback kind, which decides how results normalize.
 * @param run — the consumer's function, sync or async.
 *
 * @internal
 */
export function bridgeCallback<C>(
  target: AnswerTarget,
  kind: CallbackKind,
  run: (context: C) => unknown
): NativeCallback<C> {
  return (requestId, context) => {
    void answerRequest(target, kind, requestId, () => run(context));
  };
}

/**
 * Check that a `set…` argument is a function, before any native call.
 *
 * @param value — the argument the consumer passed.
 * @param method — the facade function, for example `setRetryDecider`.
 * @param parameter — the parameter name, which becomes the issue's `field`.
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_PAYLOAD} and one
 *   `validationErrors` entry when `value` is not a function.
 *
 * @internal
 */
export function validateCallback(
  value: unknown,
  method: string,
  parameter: string
): void {
  if (typeof value === 'function') return;
  const received = value === null ? 'null' : typeof value;
  throwOnIssues([
    {
      code: SyncErrorCode.INVALID_PAYLOAD,
      field: parameter,
      reason: `${method}(${parameter}) requires a function, received ${received}.`,
    },
  ]);
}

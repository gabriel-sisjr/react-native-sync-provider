/**
 * Bridge-only types of the callback answer path. Not public: neither
 * `src/types/index.ts` nor the package entry points re-export them.
 *
 * Native calls each installed callback as a `void` JS function whose first
 * argument is a native request id. The facade runs the consumer's function,
 * normalizes its result, and answers that request id through the spec's
 * `answerCallback`. Native never sees a JS throw: it sees a `RESOLVED` answer,
 * a `FAILED` answer, or no answer within `SyncOptions.callbackTimeoutMs`.
 *
 * @internal
 */
import type { BeforeDispatchResult, RetryDecision } from './callbacks';

/**
 * Whether the facade produced a usable answer for a callback request.
 *
 * @internal
 */
export const CallbackAnswerStatus = {
  /**
   * The consumer's function returned a valid result, and the answer's field
   * for the request's kind is set.
   */
  RESOLVED: 'RESOLVED',
  /**
   * The consumer's function threw, rejected or returned an invalid result.
   * `message` says why, and native applies its fallback at once.
   */
  FAILED: 'FAILED',
} as const;
/**
 * See {@link CallbackAnswerStatus} value set.
 *
 * @internal
 */
export type CallbackAnswerStatus =
  (typeof CallbackAnswerStatus)[keyof typeof CallbackAnswerStatus];

/**
 * The facade's answer to one native callback request.
 *
 * With `RESOLVED`, the field for the request's kind is set: `decision` for the
 * retry decider, `headers` for the header resolver, `beforeDispatch` for the
 * `beforeDispatch` hook, and none for `afterDispatch`. Native treats an answer
 * whose field is missing, belongs to another kind or is invalid as `FAILED`.
 *
 * @internal
 */
export interface CallbackAnswer {
  /** Whether the answer carries a usable result. */
  status: CallbackAnswerStatus;
  /** The retry decider's decision. */
  decision?: RetryDecision;
  /** The header resolver's headers; `{}` adds none. */
  headers?: Record<string, string>;
  /** The `beforeDispatch` hook's result. */
  beforeDispatch?: BeforeDispatchResult;
  /** Why the answer is `FAILED`, for the native log line. */
  message?: string;
}

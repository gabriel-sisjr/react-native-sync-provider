/**
 * Shared plumbing for the facade's JS-side validators
 * ({@link validateSyncItem}, {@link validateSyncOptions}).
 *
 * @internal
 */
import { SyncError } from '../errors/SyncError';
import type { SyncErrorCode } from '../errors/SyncErrorCode';
import type { ValidationIssue } from '../errors/ValidationIssue';

/**
 * Internal issue shape — a public {@link ValidationIssue} plus the code to
 * throw for *that* issue, and the thrown message when it must differ from the
 * public `reason`. Both are stripped from the public list.
 *
 * @internal
 */
export interface CollectedIssue extends ValidationIssue {
  code: SyncErrorCode;
  /** Thrown message, when it must differ from the public `reason`. */
  message?: string;
}

/**
 * Strip the internal fields from a {@link CollectedIssue}.
 *
 * @internal
 */
export function toPublicIssue(issue: CollectedIssue): ValidationIssue {
  const out: ValidationIssue = { field: issue.field, reason: issue.reason };
  if (issue.value !== undefined) out.value = issue.value;
  return out;
}

/**
 * Throw a {@link SyncError} for the first collected issue, with its code and
 * message, carrying every issue in `validationErrors`. No-op when the list is
 * empty.
 *
 * @throws {SyncError} with the first issue's code.
 *
 * @internal
 */
export function throwOnIssues(collected: readonly CollectedIssue[]): void {
  const first = collected[0];
  if (first) {
    throw new SyncError(
      first.code,
      first.message ?? first.reason,
      undefined,
      collected.map(toPublicIssue)
    );
  }
}

/**
 * Whether `value` is an absolute `http://` or `https://` URL with no
 * whitespace — the check both item URLs and `healthCheckUrl` must pass.
 *
 * @internal
 */
export function isAbsoluteHttpUrl(value: string): boolean {
  return /^https?:\/\/[^\s]+$/i.test(value);
}

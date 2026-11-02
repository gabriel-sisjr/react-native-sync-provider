import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import type { ValidationIssue } from '../errors/ValidationIssue';
import { HttpMethod, SyncPriority } from '../types/enums';
import type { SyncItemInput } from '../types/sync';

const ALLOWED_METHODS = new Set<string>(Object.values(HttpMethod));
const ALLOWED_PRIORITIES = new Set<string>(Object.values(SyncPriority));

/**
 * Internal issue shape — a public {@link ValidationIssue} plus what the legacy
 * single-throw path used for *that* issue. {@link validateSyncItem} consumes
 * these to reproduce the first-violation throw byte-for-byte, and both are
 * stripped from the public list.
 */
interface CollectedIssue extends ValidationIssue {
  code: SyncErrorCode;
  /** Thrown message, when it must differ from the public `reason`. */
  message?: string;
}

function toPublicIssue(issue: CollectedIssue): ValidationIssue {
  const out: ValidationIssue = { field: issue.field, reason: issue.reason };
  if (issue.value !== undefined) out.value = issue.value;
  return out;
}

/**
 * Run every {@link SyncItemInput} contract check and collect *all* violations,
 * in the same order the legacy throw-on-first implementation evaluated them.
 *
 * The first entry (if any) corresponds exactly to the error
 * {@link validateSyncItem} would have thrown historically — same code, same
 * message text — so single-item behavior is preserved while batch callers can
 * aggregate the full list.
 *
 * `value` is only recorded for `method`, `priority` and `contentType`. Errors
 * end up in logs and crash reporters, and the other fields can hold credentials
 * and personal data.
 *
 * @param input — the user-supplied request shape.
 * @returns every detected issue; an empty array means the input is valid.
 *
 * @internal
 */
function collectIssuesWithCode(input: SyncItemInput): CollectedIssue[] {
  const issues: CollectedIssue[] = [];

  // A non-object input can't be inspected further — mirror the legacy
  // short-circuit that threw immediately and never touched other fields.
  if (input === null || typeof input !== 'object') {
    issues.push({
      code: SyncErrorCode.INVALID_PAYLOAD,
      field: 'input',
      reason: 'SyncItemInput must be a non-null object.',
    });
    return issues;
  }

  if (typeof input.method !== 'string' || !ALLOWED_METHODS.has(input.method)) {
    issues.push({
      code: SyncErrorCode.INVALID_PAYLOAD,
      field: 'method',
      reason: `SyncItemInput.method must be one of ${[...ALLOWED_METHODS].join(', ')}.`,
      value: input.method,
    });
  }

  if (typeof input.url !== 'string' || input.url.length === 0) {
    issues.push({
      code: SyncErrorCode.INVALID_URL,
      field: 'url',
      reason: 'SyncItemInput.url must be a non-empty string.',
    });
  } else if (!/^https?:\/\/[^\s]+$/i.test(input.url)) {
    // Only reachable when `url` is a non-empty string — matches the legacy
    // sequential `if` guards (the absolute-URL check never ran on a bad type).
    issues.push({
      code: SyncErrorCode.INVALID_URL,
      field: 'url',
      reason: 'SyncItemInput.url must be an absolute http(s) URL.',
      // The thrown message keeps the URL for backward compatibility. It stays
      // out of `reason`: URLs can carry tokens in their query string.
      message: `SyncItemInput.url must be an absolute http(s) URL (got: "${input.url}").`,
    });
  }

  if (input.body !== undefined && typeof input.body !== 'string') {
    issues.push({
      code: SyncErrorCode.INVALID_PAYLOAD,
      field: 'body',
      reason:
        'SyncItemInput.body must be a string when supplied. Stringify objects on the JS side first.',
    });
  }

  if (
    input.contentType !== undefined &&
    typeof input.contentType !== 'string'
  ) {
    issues.push({
      code: SyncErrorCode.INVALID_PAYLOAD,
      field: 'contentType',
      reason: 'SyncItemInput.contentType must be a string when supplied.',
      value: input.contentType,
    });
  }

  if (
    input.priority !== undefined &&
    !ALLOWED_PRIORITIES.has(input.priority as string)
  ) {
    issues.push({
      code: SyncErrorCode.INVALID_PAYLOAD,
      field: 'priority',
      reason: `SyncItemInput.priority must be one of ${[...ALLOWED_PRIORITIES].join(', ')}.`,
      value: input.priority,
    });
  }

  if (input.headers !== undefined) {
    if (typeof input.headers !== 'object' || input.headers === null) {
      issues.push({
        code: SyncErrorCode.INVALID_PAYLOAD,
        field: 'headers',
        reason:
          'SyncItemInput.headers must be an object of string→string entries.',
      });
    } else {
      for (const key of Object.keys(input.headers)) {
        if (typeof input.headers[key] !== 'string') {
          issues.push({
            code: SyncErrorCode.INVALID_PAYLOAD,
            field: `headers.${key}`,
            reason: `SyncItemInput.headers["${key}"] must be a string.`,
          });
        }
      }
    }
  }

  if (input.metadata !== undefined) {
    if (typeof input.metadata !== 'object' || input.metadata === null) {
      issues.push({
        code: SyncErrorCode.INVALID_PAYLOAD,
        field: 'metadata',
        reason:
          'SyncItemInput.metadata must be an object of string→string entries.',
      });
    } else {
      for (const key of Object.keys(input.metadata)) {
        if (typeof input.metadata[key] !== 'string') {
          issues.push({
            code: SyncErrorCode.INVALID_PAYLOAD,
            field: `metadata.${key}`,
            reason: `SyncItemInput.metadata["${key}"] must be a string.`,
          });
        }
      }
    }
  }

  return issues;
}

/**
 * Collect *every* {@link SyncItemInput} validation violation without throwing.
 *
 * Performs the same locally checkable subset of the {@link SyncItemInput}
 * contract as {@link validateSyncItem}, but returns the full list of issues
 * instead of throwing on the first one — enabling batch aggregation and
 * machine-readable error reporting.
 *
 * - `method` must be one of {@link HttpMethod}.
 * - `url` must be a non-empty absolute `http://` or `https://` URL.
 * - `body`, when present, must be a string.
 * - `contentType`, when present, must be a string.
 * - `priority`, when present, must be one of {@link SyncPriority}.
 * - `headers` / `metadata`, when present, must be `string → string` maps.
 *
 * @param input — the user-supplied request shape.
 * @returns every detected {@link ValidationIssue}; an empty array means valid.
 *
 * @internal
 */
export function collectSyncItemIssues(input: SyncItemInput): ValidationIssue[] {
  return collectIssuesWithCode(input).map(toPublicIssue);
}

/**
 * Validate a {@link SyncItemInput} before it crosses the JS↔native bridge.
 *
 * Thin wrapper around {@link collectSyncItemIssues}: throws on the first
 * violation (preserving the historical {@link SyncErrorCode} and message for
 * backward compatibility) while attaching the complete, machine-readable list
 * via {@link SyncError.validationErrors}.
 *
 * @param input — the user-supplied request shape.
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_URL} when the
 *   URL is missing, empty, or not absolute http(s).
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_PAYLOAD} for
 *   every other contract violation. The thrown error carries every detected
 *   issue in `validationErrors`.
 *
 * @internal
 */
export function validateSyncItem(input: SyncItemInput): void {
  const collected = collectIssuesWithCode(input);
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

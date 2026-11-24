import { SyncErrorCode } from '../errors/SyncErrorCode';
import { NetworkQuality } from '../types/enums';
import type { SyncOptions } from '../types/sync';

import type { CollectedIssue } from './validation';
import { isAbsoluteHttpUrl, throwOnIssues } from './validation';

const ALLOWED_QUALITIES: ReadonlySet<unknown> = new Set(
  Object.values(NetworkQuality)
);

/** The millisecond options that must be finite and at least `0`. */
const NON_NEGATIVE_MS_FIELDS = [
  'healthCheckTtlMs',
  'healthCheckBackoffMs',
] as const;

function isNonNegativeFinite(value: unknown): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

function isFiniteAtLeastOne(value: unknown): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value >= 1;
}

/**
 * Collect every violation in the {@link SyncOptions} fields the facade checks,
 * in field order. Only the network-quality, health-check and callback-timeout
 * options are checked, and only when present: the other fields are left to
 * native, as before these options existed.
 *
 * `value` is recorded for every field except `healthCheckUrl`, which can carry
 * a token in its query string.
 *
 * @internal
 */
function collectSyncOptionsIssues(options: SyncOptions): CollectedIssue[] {
  if (options === null || typeof options !== 'object') {
    return [
      {
        code: SyncErrorCode.INVALID_PAYLOAD,
        field: 'options',
        reason: 'configureSync(options) requires a SyncOptions object.',
      },
    ];
  }

  const issues: CollectedIssue[] = [];

  const { minNetworkQuality, healthCheckUrl } = options;
  if (
    minNetworkQuality !== undefined &&
    !ALLOWED_QUALITIES.has(minNetworkQuality)
  ) {
    issues.push({
      code: SyncErrorCode.INVALID_PAYLOAD,
      field: 'minNetworkQuality',
      reason: `SyncOptions.minNetworkQuality must be one of ${[...ALLOWED_QUALITIES].join(', ')}.`,
      value: minNetworkQuality,
    });
  }

  if (
    healthCheckUrl !== undefined &&
    (typeof healthCheckUrl !== 'string' || !isAbsoluteHttpUrl(healthCheckUrl))
  ) {
    issues.push({
      code: SyncErrorCode.INVALID_URL,
      field: 'healthCheckUrl',
      reason: 'SyncOptions.healthCheckUrl must be an absolute http(s) URL.',
    });
  }

  for (const field of NON_NEGATIVE_MS_FIELDS) {
    const value = options[field];
    if (value !== undefined && !isNonNegativeFinite(value)) {
      issues.push({
        code: SyncErrorCode.INVALID_PAYLOAD,
        field,
        reason: `SyncOptions.${field} must be a finite number of at least 0.`,
        value,
      });
    }
  }

  const { callbackTimeoutMs } = options;
  if (
    callbackTimeoutMs !== undefined &&
    !isFiniteAtLeastOne(callbackTimeoutMs)
  ) {
    issues.push({
      code: SyncErrorCode.INVALID_PAYLOAD,
      field: 'callbackTimeoutMs',
      reason:
        'SyncOptions.callbackTimeoutMs must be a finite number of at least 1.',
      value: callbackTimeoutMs,
    });
  }

  return issues;
}

/**
 * Validate the network-quality, health-check and callback-timeout fields of a
 * {@link SyncOptions} before it crosses the JS↔native bridge.
 *
 * - `minNetworkQuality`, when present, must be one of {@link NetworkQuality}.
 * - `healthCheckUrl`, when present, must be an absolute `http://` or
 *   `https://` URL, the same check item URLs pass.
 * - `healthCheckTtlMs` / `healthCheckBackoffMs`, when present, must be finite
 *   numbers of at least `0`.
 * - `callbackTimeoutMs`, when present, must be a finite number of at least
 *   `1`. Fractions are allowed: native truncates them.
 *
 * Throws on the first violation, carrying every one in
 * {@link SyncError.validationErrors}.
 *
 * @param options — the configuration passed to `configureSync`.
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_URL} when the
 *   first violation is `healthCheckUrl`.
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_PAYLOAD} for every
 *   other violation, and when `options` is not an object.
 *
 * @internal
 */
export function validateSyncOptions(options: SyncOptions): void {
  throwOnIssues(collectSyncOptionsIssues(options));
}

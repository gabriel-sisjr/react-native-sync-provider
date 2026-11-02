/**
 * A single, machine-readable validation violation.
 *
 * Produced by {@link collectSyncItemIssues} and attached to a
 * {@link SyncError} via its `validationErrors` field, so consumers can react
 * to *every* problem with a payload instead of parsing one opaque `message`.
 *
 * For batch operations ({@link enqueueBatch}), `itemIndex` identifies which
 * input item the issue belongs to (the position in the `items` array). For
 * single-item operations ({@link enqueue}) `itemIndex` is omitted.
 *
 * @example
 * try {
 *   await enqueueBatch(items);
 * } catch (e) {
 *   if (e instanceof SyncError && e.validationErrors) {
 *     for (const issue of e.validationErrors) {
 *       console.warn(`item ${issue.itemIndex} → ${issue.field}: ${issue.reason}`);
 *     }
 *   }
 * }
 *
 * @public
 */
export type ValidationIssue = {
  /**
   * Dotted path of the offending field, e.g. `"url"`, `"method"`,
   * `"headers.Authorization"`, or `"input"` when the whole item is invalid.
   */
  field: string;
  /** Human-readable explanation of why `field` failed validation. */
  reason: string;
  /**
   * The offending value. Set only for `method`, `priority` and `contentType`.
   *
   * It is never set for `url`, `body`, `headers`, `metadata` or a whole-item
   * `input` failure, because errors end up in logs and crash reporters and
   * those fields can hold credentials and personal data.
   */
  value?: unknown;
  /**
   * Zero-based index of the originating item inside a batch operation.
   * Omitted for single-item validation.
   */
  itemIndex?: number;
};

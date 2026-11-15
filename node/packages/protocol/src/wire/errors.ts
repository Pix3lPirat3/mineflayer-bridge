/** Protocol error codes. */
export type ErrorCode =
  | 'invalid_message'
  | 'unknown_type'
  | 'unknown_kind'
  | 'unauthorized'
  | 'permission_denied'
  | 'rate_limited'
  | 'quota_exceeded'
  | 'conflict'
  | 'not_owner'
  | 'world_mismatch'
  | 'not_attached'
  | 'cancelled'
  | 'cooldown'
  | 'timeout'
  | 'disconnected'
  | 'superseded'
  | 'unsupported'
  | 'internal';

/** Base error for everything the bridge reports. `code` is stable and shared with the Java side. */
export class BridgeError extends Error {
  /** A catalogue code, or an extension-defined code. */
  readonly code: ErrorCode | (string & {});
  /** True when the same operation may succeed if retried. Default false. */
  readonly retryable: boolean;
  /** Extra context, such as the current object for `conflict`. */
  readonly details: unknown;

  constructor(code: ErrorCode | (string & {}), message: string, options: { retryable?: boolean; details?: unknown; cause?: unknown } = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'BridgeError';
    this.code = code;
    this.retryable = options.retryable ?? false;
    this.details = options.details;
  }

  /**
   * How long to wait before trying again, in ms, when the mod said so in `details.retryAfterMs`
   * (a `cooldown` refusal does); otherwise `undefined`.
   */
  get retryAfterMs(): number | undefined {
    const d = this.details;
    if (typeof d !== 'object' || d === null) return undefined;
    const v = (d as { retryAfterMs?: unknown }).retryAfterMs;
    return typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : undefined;
  }
}

/** Raised by codecs when bytes do not match the schema. */
export class WireError extends BridgeError {
  constructor(code: ErrorCode, message: string) {
    super(code, message);
    this.name = 'WireError';
  }
}

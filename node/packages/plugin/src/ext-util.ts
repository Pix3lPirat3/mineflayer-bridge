import type { ExtensionContext } from './types.ts';

/** A coalescing scheduler bound to an extension's lifetime. */
export interface Batch {
  /** Marks the output stale; `run` follows within the interval, once, while the player is attached. */
  request(): void;
  /** Runs now when stale and attached, cancelling a pending run. */
  flush(): void;
}

/**
 * Coalesces many change notifications into at most one `run` per `intervalMs` (ms), trailing, so a
 * burst of events costs one update. Nothing runs while the bot is not attached: nobody sees the
 * output then, and the next attach runs it with the current values. Stops when the extension
 * unloads.
 */
export function batch(ctx: ExtensionContext, intervalMs: number, run: () => void): Batch {
  let dirty = false;
  let timer: NodeJS.Timeout | undefined;
  let lastAt = 0;
  const fire = (): void => {
    timer = undefined;
    if (!dirty || !ctx.state.attached || ctx.signal.aborted) return;
    dirty = false;
    lastAt = Date.now();
    try {
      run();
    } catch (e) {
      ctx.log.error('update failed', { error: (e as Error).message });
    }
  };
  const request = (): void => {
    dirty = true;
    if (timer !== undefined || ctx.signal.aborted) return;
    // Always deferred, so events raised in the same tick coalesce even when the interval has passed.
    timer = setTimeout(fire, Math.max(0, lastAt + intervalMs - Date.now()));
  };
  ctx.onAttached(request);
  ctx.signal.addEventListener('abort', () => clearTimeout(timer), { once: true });
  return {
    request,
    flush: () => {
      clearTimeout(timer);
      fire();
    },
  };
}

/** Mini-schema field of the `whenUnfocused` option of the HUD extensions. */
export const WHEN_UNFOCUSED = { type: 'string', default: 'summary', enum: ['summary', 'full'] } as const;

/**
 * Whether an extension should send its detailed data now: detail is full and the bot is focused,
 * unless `whenUnfocused` is `full`. `fn` runs whenever the answer changes; the returned function
 * reads it.
 */
export function detailGate(ctx: ExtensionContext, whenUnfocused: string, fn: () => void): () => boolean {
  const now = (): boolean => ctx.state.detail === 'full' && (whenUnfocused === 'full' || ctx.focused);
  let last = now();
  ctx.onInterest(() => {
    const v = now();
    if (v === last) return;
    last = v;
    fn();
  });
  return now;
}

/** `minecraft:<name>` for a bare registry name; names that carry a namespace are kept. */
export function itemId(name: string): string {
  return name.includes(':') ? name : `minecraft:${name}`;
}

/** True when the value is a finite number. */
export function isFiniteNumber(v: unknown): v is number {
  return typeof v === 'number' && Number.isFinite(v);
}

import { BridgeError, type MessagePayloads } from '@mineflayer-bridge/protocol';
import type { ClientSampleData, SampleRequestOptions, SamplesApi } from './types.ts';
import { asBridgeError } from './util.ts';

/** The player allows at most 20 Hz. */
export const MAX_SAMPLE_RATE_HZ = 20;
const DEFAULT_SAMPLE_RATE_HZ = 4;

type Request = Readonly<Required<SampleRequestOptions>>;

/** What the session gives its sample hub. */
export interface SampleHost {
  /** Sets the latest-wins `sample.request`; the session replays it after every reconnect. */
  sendRequest(p: MessagePayloads['sample.request']): void;
  reportError(error: BridgeError): void;
}

/**
 * Session-wide client samples: merges the requests of every scope into one `sample.request` and
 * fans incoming `client.sample`s out to the scopes' listeners.
 */
export class SampleHub {
  private readonly host: SampleHost;
  private readonly requests = new Map<ScopeSamples, Request>();
  private readonly scopes = new Set<ScopeSamples>();
  /** Last request sent, as a comparable string; `undefined` until something was requested. */
  private sent: string | undefined;
  latest: ClientSampleData | undefined;

  constructor(host: SampleHost) {
    this.host = host;
  }

  /** A per-scope API; `signal` withdraws its request and listeners when it aborts. */
  scope(signal: AbortSignal | undefined): ScopeSamples {
    const s = new ScopeSamples(this);
    this.scopes.add(s);
    signal?.addEventListener(
      'abort',
      () => {
        s.dispose();
        this.scopes.delete(s);
      },
      { once: true },
    );
    return s;
  }

  setRequest(scope: ScopeSamples, request: Request | undefined): void {
    if (request === undefined) this.requests.delete(scope);
    else this.requests.set(scope, request);
    this.publish();
  }

  /** Sends the union of all requests when it changed. Before the first real request nothing is sent. */
  private publish(): void {
    const merged: { rateHz: number; position: boolean; target: boolean; heldItem: boolean } = { rateHz: 0, position: false, target: false, heldItem: false };
    for (const r of this.requests.values()) {
      merged.rateHz = Math.max(merged.rateHz, r.rateHz);
      merged.position ||= r.position;
      merged.target ||= r.target;
      merged.heldItem ||= r.heldItem;
    }
    // A request with every field off is the stop signal; its rate is irrelevant, so it keeps the schema default.
    if (this.requests.size === 0) merged.rateHz = DEFAULT_SAMPLE_RATE_HZ;
    const key = JSON.stringify(merged);
    if (key === this.sent || (this.sent === undefined && this.requests.size === 0)) return;
    this.sent = key;
    this.host.sendRequest(merged);
  }

  receive(p: MessagePayloads['client.sample']): void {
    const sample: ClientSampleData = Object.freeze(stripUndefined(p));
    this.latest = sample;
    for (const s of [...this.scopes]) s.deliver(sample, this.host);
  }

  /** Forgets the latest sample: after a disconnect or detach it no longer describes the player. */
  reset(): void {
    this.latest = undefined;
  }
}

function stripUndefined(p: MessagePayloads['client.sample']): MessagePayloads['client.sample'] {
  const out: MessagePayloads['client.sample'] = {};
  if (p.position !== undefined) out.position = p.position;
  if (p.yaw !== undefined) out.yaw = p.yaw;
  if (p.pitch !== undefined) out.pitch = p.pitch;
  if (p.target !== undefined) out.target = p.target;
  if (p.heldItem !== undefined) out.heldItem = p.heldItem;
  return out;
}

/** Validates request options and applies the defaults. */
function normalizeRequest(o: SampleRequestOptions): Request | undefined {
  if (typeof o !== 'object' || o === null) throw new BridgeError('invalid_message', 'sample request options must be an object');
  const rateHz = o.rateHz ?? DEFAULT_SAMPLE_RATE_HZ;
  if (typeof rateHz !== 'number' || !Number.isFinite(rateHz) || rateHz <= 0 || rateHz > MAX_SAMPLE_RATE_HZ) {
    throw new BridgeError('invalid_message', `sample rateHz must be a number in (0, ${MAX_SAMPLE_RATE_HZ}], got ${String(rateHz)}`);
  }
  const r: Request = { rateHz, position: o.position === true, target: o.target === true, heldItem: o.heldItem === true };
  return r.position || r.target || r.heldItem ? r : undefined;
}

/** {@link SamplesApi} of one scope. */
export class ScopeSamples implements SamplesApi {
  private readonly hub: SampleHub;
  private readonly listeners = new Set<(sample: ClientSampleData) => void>();
  private request_: Request | undefined;
  private disposed = false;

  constructor(hub: SampleHub) {
    this.hub = hub;
  }

  get current(): Request | undefined {
    return this.request_;
  }

  get latest(): ClientSampleData | undefined {
    return this.hub.latest;
  }

  request(options: SampleRequestOptions): void {
    const r = normalizeRequest(options);
    if (this.disposed) return;
    this.request_ = r;
    this.hub.setRequest(this, r);
  }

  stop(): void {
    if (this.request_ === undefined) return;
    this.request_ = undefined;
    this.hub.setRequest(this, undefined);
  }

  on(event: 'sample', fn: (sample: ClientSampleData) => void): () => void {
    if (event !== 'sample') throw new BridgeError('invalid_message', `unknown samples event ${String(event)}`);
    if (this.disposed) return () => undefined;
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  }

  deliver(sample: ClientSampleData, host: SampleHost): void {
    for (const fn of [...this.listeners]) {
      try {
        fn(sample);
      } catch (e) {
        host.reportError(asBridgeError(e));
      }
    }
  }

  dispose(): void {
    this.disposed = true;
    this.listeners.clear();
    this.stop();
  }
}

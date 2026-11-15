import { randomBytes, randomInt } from 'node:crypto';
import {
  BridgeError,
  formatPairingUri,
  LOCAL_DEFAULT_PERMISSIONS,
  REMOTE_DEFAULT_PERMISSIONS,
  toBase64Url,
  verifyAuthProof,
  type EnvelopeHeader,
  type MessageName,
  type MessagePayloads,
} from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import type { Conn, Session } from './session.ts';
import type { IssuedDevice, MockDevice, MockDeviceOrigin, MockPairing } from './types.ts';
import { isDisplay } from './util.ts';

/** Pairing code alphabet: base32 without the look-alikes 0, 1, I and O, as the code is typed by hand. */
const PAIRING_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

interface DeviceRecord {
  deviceId: string;
  name: string;
  origin: MockDeviceOrigin;
  permissions: string[];
  lastSeenAt: number | undefined;
  lastAddress: string | undefined;
}

/** The permission a core kind needs. Layers and styles need none. */
export function kindPermission(kind: string): string | undefined {
  if (kind === 'core:layer' || kind === 'core:style' || !kind.startsWith('core:')) return undefined;
  if (kind.startsWith('core:hud.')) return 'render.hud';
  if (isDisplay(kind) || kind === 'core:group') return 'render.display';
  switch (kind) {
    case 'core:command':
    case 'core:action':
      return 'commands';
    case 'core:keybind':
      return 'input.keybind';
    case 'core:tool':
      return 'input.tool';
    case 'core:intercept':
      return 'input.intercept';
    case 'core:settings':
      return 'settings';
    case 'core:container-view':
      return 'ui.container';
    case 'core:particle-emitter':
      return 'effects.particles';
    case 'core:bossbar':
      return 'effects.title';
    default:
      return 'render.world';
  }
}

/** The permission an inbound session message needs. */
export function messagePermission(type: MessageName, payload: unknown): string | undefined {
  switch (type) {
    case 'notify':
      return 'notify';
    case 'pick.request':
    case 'prompt.request':
      return 'input.pick';
    case 'world-menu.request':
      return 'render.display';
    case 'form.open':
      return 'form';
    case 'container.open':
      return 'ui.container';
    case 'chat.print':
      return 'chat';
    case 'shared.op':
      return 'shared.write';
    case 'shared.subscribe':
      return 'shared.read';
    case 'query.entities':
    case 'query.blocks':
      return 'client.query';
    case 'effect.play': {
      const t = (payload as MessagePayloads['effect.play']).effect.type;
      return t === 'sound' ? 'effects.sound' : t === 'particles' ? 'effects.particles' : 'effects.title';
    }
    default:
      return undefined;
  }
}

/** Device credentials, the registry with granted permissions, and pairing codes. */
export class Devices {
  private readonly known = new Set<string>();
  private readonly secrets = new Map<string, Uint8Array>();
  private readonly registry = new Map<string, DeviceRecord>();
  /** Active pairing codes and their expiry clock times in ms. */
  private readonly pairings = new Map<string, number>();

  constructor(private readonly core: ModCore) {}

  issue(deviceId: string): IssuedDevice {
    const secret = this.newSecret(deviceId);
    this.record(deviceId, 'issued');
    return { deviceId, secret, secretBase64Url: toBase64Url(secret), fingerprint: this.core.fingerprint };
  }

  revoke(deviceId: string): void {
    this.secrets.delete(deviceId);
    this.known.delete(deviceId);
    this.registry.delete(deviceId);
    for (const c of this.core.conns.values()) {
      if (c.deviceId === deviceId) this.core.goodbye(c, 'revoked', 'device revoked', false);
    }
  }

  beginPairing(options: { host?: string; ttlMs?: number }): MockPairing {
    if (!this.core.tls) throw new BridgeError('unsupported', 'mock: pairing needs the tls option, since codes are accepted only over TLS');
    let code = '';
    do {
      code = '';
      for (let i = 0; i < 8; i++) code += PAIRING_ALPHABET[randomInt(PAIRING_ALPHABET.length)];
    } while (this.pairings.has(code));
    const expiresAt = this.core.clock.now() + (options.ttlMs ?? this.core.opts.pairingTtlMs ?? 300_000);
    this.pairings.set(code, expiresAt);
    const bind = this.core.opts.host ?? '127.0.0.1';
    const host = options.host ?? (bind === '0.0.0.0' || bind === '::' ? '127.0.0.1' : bind);
    return { uri: formatPairingUri(host, this.core.port, this.core.fingerprint, code), code, expiresAt };
  }

  /** `pair` is offered only while a code is active. */
  pairingActive(): boolean {
    this.expirePairings();
    return this.pairings.size > 0;
  }

  list(): MockDevice[] {
    return [...this.registry.values()].map(copy).sort((a, b) => (a.deviceId < b.deviceId ? -1 : a.deviceId > b.deviceId ? 1 : 0));
  }

  get(deviceId: string): MockDevice | undefined {
    const r = this.registry.get(deviceId);
    return r === undefined ? undefined : copy(r);
  }

  setPermissions(deviceId: string, permissions: readonly string[]): void {
    const r = this.registry.get(deviceId);
    if (r === undefined) throw new BridgeError('invalid_message', `mock: unknown device ${deviceId}`);
    r.permissions = [...new Set(permissions)];
    for (const c of this.core.conns.values()) {
      if (c.deviceId === deviceId && c.state === 'ready') this.core.sendConn(c, 'permissions', { granted: [...r.permissions] });
    }
  }

  /** The registry entry of a device, created with the initial permissions for `origin` when new. */
  record(deviceId: string, origin: MockDeviceOrigin): DeviceRecord {
    let r = this.registry.get(deviceId);
    if (r === undefined) {
      const initial = this.core.opts.permissions?.({ deviceId, origin }) ?? (origin === 'paired' ? REMOTE_DEFAULT_PERMISSIONS : LOCAL_DEFAULT_PERMISSIONS);
      r = { deviceId, name: '', origin, permissions: [...initial], lastSeenAt: undefined, lastAddress: undefined };
      this.registry.set(deviceId, r);
    }
    return r;
  }

  /** `false` only when the device is registered without `permission`. */
  allows(deviceId: string, permission: string): boolean {
    return this.registry.get(deviceId)?.permissions.includes(permission) !== false;
  }

  granted(s: Session, permission: string): boolean {
    return this.registry.get(s.deviceId)?.permissions.includes(permission) ?? LOCAL_DEFAULT_PERMISSIONS.includes(permission as never);
  }

  isPaired(deviceId: string): boolean {
    return this.registry.get(deviceId)?.origin === 'paired';
  }

  /** Refuses with `permission_denied`, naming the permission in the message and in `details.permission`. */
  deny(s: Session, permission: string, type: string, header: EnvelopeHeader | undefined, relatesTo: number | undefined, key?: string): void {
    this.core.emit('permissionDenied', { session: this.core.info(s), permission, type, key });
    const what = key === undefined ? type : `${type} of ${key}`;
    this.core.sendSessionError(s, 'permission_denied', `${what} needs the ${permission} permission`, header, relatesTo, key === undefined ? { permission } : { permission, key });
  }

  /**
   * Checks an `auth.response`. A first-time `local` device waits for `MockModOptions.approve`.
   * The proof is the UTF-8 code, accepted only over TLS; a valid
   * code is consumed and `pair.result` goes out before `welcome`.
   */
  async verify(conn: Conn, p: MessagePayloads['auth.response']): Promise<boolean> {
    switch (p.method) {
      case 'local': {
        if (!conn.loopback || !verifyAuthProof(this.core.tokenBytes, conn.nonce, conn.id, p.deviceId, p.proof)) return false;
        conn.secret = this.core.tokenBytes;
        if (this.known.has(p.deviceId)) return true;
        let ok: boolean;
        try {
          ok = (await (this.core.opts.approve?.({ deviceId: p.deviceId, deviceName: conn.deviceName, method: 'local', remoteAddress: conn.remoteAddress }) ?? true)) === true;
        } catch {
          ok = false;
        }
        if (ok) this.known.add(p.deviceId);
        return ok;
      }
      case 'device': {
        const secret = this.secrets.get(p.deviceId);
        conn.secret = secret;
        return secret !== undefined && verifyAuthProof(secret, conn.nonce, conn.id, p.deviceId, p.proof);
      }
      case 'pair': {
        this.expirePairings();
        const code = Buffer.from(p.proof).toString('utf8');
        if (!conn.tls || !this.pairings.has(code) || p.deviceId === '' || this.secrets.has(p.deviceId)) return false;
        this.pairings.delete(code);
        const secret = this.newSecret(p.deviceId);
        conn.secret = secret;
        this.registry.delete(p.deviceId);
        this.record(p.deviceId, 'paired');
        this.core.sendConn(conn, 'pair.result', { deviceId: p.deviceId, secret, fingerprint: this.core.fingerprint });
        this.core.emit('paired', { connectionId: conn.id, deviceId: p.deviceId, code });
        return true;
      }
      default:
        return false;
    }
  }

  private newSecret(deviceId: string): Uint8Array {
    const secret = new Uint8Array(randomBytes(32));
    this.secrets.set(deviceId, secret);
    this.known.add(deviceId);
    return secret;
  }

  private expirePairings(): void {
    const now = this.core.clock.now();
    for (const [code, expiresAt] of this.pairings) if (expiresAt <= now) this.pairings.delete(code);
  }
}

function copy(r: DeviceRecord): MockDevice {
  return { ...r, permissions: [...r.permissions] };
}

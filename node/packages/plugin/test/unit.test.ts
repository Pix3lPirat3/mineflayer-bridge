import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { decodePatchFields, encodeKind, Kinds, LOCAL_DEFAULT_PERMISSIONS, PERMISSIONS, REMOTE_DEFAULT_PERMISSIONS } from '@mineflayer-bridge/protocol';
import { dimensionKey, serverKey } from '../src/bridge.ts';
import { orderForLoad } from '../src/extensions.ts';
import { defaultCredentialsFile, defaultEndpointFile, resolveEndpoint } from '../src/files.ts';
import { bridgeUrl, errorFromPayload } from '../src/link.ts';
import { DeclaredStore } from '../src/store.ts';
import { checkKeys, satisfies, toColor, toVec3 } from '../src/util.ts';
import { BridgeError, defineExtension } from '../src/index.ts';

describe('values', () => {
  it('accepts every vector form', () => {
    expect(toVec3([1, 2, 3])).toEqual({ x: 1, y: 2, z: 3 });
    expect(toVec3({ x: 1, y: 2, z: 3 })).toEqual({ x: 1, y: 2, z: 3 });
    class Vec3 {
      constructor(readonly x: number, readonly y: number, readonly z: number) {}
    }
    expect(toVec3(new Vec3(4, 5, 6))).toEqual({ x: 4, y: 5, z: 6 });
    expect(() => toVec3([1, Number.NaN, 3])).toThrow(BridgeError);
  });

  it('packs colours as ARGB', () => {
    expect(toColor('#ff3b30')).toBe(0xffff3b30);
    expect(toColor('#ff3b3080')).toBe(0x80ff3b30);
    expect(toColor(0x00ff00)).toBe(0xff00ff00);
    expect(toColor(0x40000000)).toBe(0x40000000);
    expect(() => toColor('red')).toThrow(BridgeError);
  });

  it('matches semver ranges', () => {
    expect(satisfies('1.4.2', '^1')).toBe(true);
    expect(satisfies('2.0.0', '^1')).toBe(false);
    expect(satisfies('0.2.5', '^0.2')).toBe(true);
    expect(satisfies('0.3.0', '^0.2.1')).toBe(false);
    expect(satisfies('1.2.9', '~1.2')).toBe(true);
    expect(satisfies('1.3.0', '~1.2')).toBe(false);
    expect(satisfies('1.5.0', '>=1.2.0 <2')).toBe(true);
    expect(satisfies('1.0.0', '*')).toBe(true);
  });

  it('normalises world keys', () => {
    expect(dimensionKey('overworld')).toBe('minecraft:overworld');
    expect(dimensionKey('minecraft:the_end')).toBe('minecraft:the_end');
    expect(serverKey({ host: 'Mc.Example.com' })).toBe('mc.example.com:25565');
    expect(serverKey(undefined)).toBe('localhost:25565');
  });
});

describe('discovery paths', () => {
  it('finds endpoint.json in the Minecraft config dir per platform', () => {
    expect(defaultEndpointFile({ platform: 'win32', env: { APPDATA: 'C:\\Users\\u\\AppData\\Roaming' }, home: 'C:\\Users\\u' })).toBe(
      join('C:\\Users\\u\\AppData\\Roaming', '.minecraft', 'config', 'mineflayer_bridge', 'endpoint.json'),
    );
    expect(defaultEndpointFile({ platform: 'darwin', env: {}, home: '/Users/u' })).toBe(join('/Users/u', 'Library', 'Application Support', 'minecraft', 'config', 'mineflayer_bridge', 'endpoint.json'));
    expect(defaultEndpointFile({ platform: 'linux', env: {}, home: '/home/u' })).toBe(join('/home/u', '.minecraft', 'config', 'mineflayer_bridge', 'endpoint.json'));
  });

  it('puts credentials in the OS config dir', () => {
    expect(defaultCredentialsFile({ platform: 'linux', env: { XDG_CONFIG_HOME: '/x' }, home: '/home/u' })).toBe(join('/x', 'mineflayer-bridge', 'credentials.json'));
    expect(defaultCredentialsFile({ platform: 'linux', env: {}, home: '/home/u' })).toBe(join('/home/u', '.config', 'mineflayer-bridge', 'credentials.json'));
    expect(defaultCredentialsFile({ platform: 'darwin', env: {}, home: '/Users/u' })).toBe(join('/Users/u', 'Library', 'Application Support', 'mineflayer-bridge', 'credentials.json'));
    expect(defaultCredentialsFile({ platform: 'win32', env: { APPDATA: 'D:\\r' }, home: 'D:\\h' })).toBe(join('D:\\r', 'mineflayer-bridge', 'credentials.json'));
  });

  it('lets MINEFLAYER_BRIDGE_ENDPOINT override discovery but not explicit options', async () => {
    expect(await resolveEndpoint({}, { MINEFLAYER_BRIDGE_ENDPOINT: 'ws://10.0.0.2:25590' })).toEqual({ url: 'ws://10.0.0.2:25590' });
    expect(await resolveEndpoint({ endpoint: 'ws://a:1' }, { MINEFLAYER_BRIDGE_ENDPOINT: 'ws://b:2' })).toEqual({ url: 'ws://a:1' });
    await expect(resolveEndpoint({}, { MINEFLAYER_BRIDGE_ENDPOINT: join(process.cwd(), 'missing-endpoint.json') })).rejects.toMatchObject({ code: 'disconnected' });
  });

  it('adds the protocol path only when missing', () => {
    expect(bridgeUrl('ws://127.0.0.1:25590')).toBe('ws://127.0.0.1:25590/bridge/v1');
    expect(bridgeUrl('wss://h:1/custom')).toBe('wss://h:1/custom');
  });
});

describe('declared store', () => {
  const box = (x: number) => encodeKind(Kinds['core:box'], { min: { x, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } });
  const input = (key: string, x: number, ttlMs?: number) => ({ key, kind: 'core:box', layer: 'l', lifetime: 'session' as const, scope: 'global' as const, world: undefined, ttlMs, data: box(x) });

  it('coalesces a tick into one write and later changes into patches', () => {
    const s = new DeclaredStore();
    s.put(input('a', 1));
    s.put(input('a', 2));
    const first = s.drain();
    expect(first.writes.map((w) => w.type)).toEqual(['full']);
    s.put(input('a', 2));
    expect(s.hasChanges).toBe(false);
    s.put(input('a', 3));
    const second = s.drain();
    expect(second.writes[0]?.type).toBe('patch');
    if (second.writes[0]?.type === 'patch') expect([...decodePatchFields(second.writes[0].fields).keys()]).toEqual([0]);
    expect(second.writes[0]!.object.rev).toBeGreaterThan(first.writes[0]!.object.rev);
  });

  it('refreshes TTL objects even with unchanged data, as an empty patch', () => {
    const s = new DeclaredStore();
    s.put(input('a', 1, 100));
    s.drain();
    s.put(input('a', 1, 100));
    const w = s.drain().writes[0]!;
    expect(w.type).toBe('patch');
    if (w.type === 'patch') expect(decodePatchFields(w.fields).size).toBe(0);
  });

  it('collapses removals under one prefix and resends removed keys in full', () => {
    const s = new DeclaredStore();
    s.put(input('x/a', 1));
    s.put(input('x/b', 1));
    s.drain();
    s.remove('x/a');
    s.removePrefix('x/');
    s.put(input('x/a', 5));
    const c = s.drain();
    expect(c.keys).toEqual([]);
    expect(c.prefixes).toEqual(['x/']);
    expect(c.writes.map((w) => [w.type, w.object.key])).toEqual([['full', 'x/a']]);
  });

  it('never sends removals for keys the mod never saw', () => {
    const s = new DeclaredStore();
    s.put(input('a', 1));
    s.remove('a');
    expect(s.drain()).toEqual({ keys: [], prefixes: [], writes: [] });
  });
});

describe('dependency order', () => {
  const ext = (id: string, deps: string[] = []) => defineExtension({ id, version: '1.0.0', requires: { extensions: deps }, setup() {} });

  it('sorts dependencies first and honours already-loaded ones', () => {
    expect(orderForLoad([ext('t:c', ['t:b']), ext('t:b', ['t:a']), ext('t:a')], new Set()).map((i) => i.id)).toEqual(['t:a', 't:b', 't:c']);
    expect(orderForLoad([ext('t:c', ['t:b'])], new Set(['t:b'])).map((i) => i.id)).toEqual(['t:c']);
  });

  it('rejects duplicates and self cycles', () => {
    expect(() => orderForLoad([ext('t:a'), ext('t:a')], new Set())).toThrow('extension t:a is listed twice');
    expect(() => orderForLoad([ext('t:a')], new Set(['t:a']))).toThrow('extension t:a is already loaded');
    expect(() => orderForLoad([ext('t:a', ['t:a'])], new Set())).toThrow('extension dependency cycle: t:a -> t:a');
  });
});

describe('permissions and option keys', () => {
  it('trusts local devices with every permission and withholds a few from remote ones', () => {
    expect([...LOCAL_DEFAULT_PERMISSIONS].sort()).toEqual([...PERMISSIONS].sort());
    expect(PERMISSIONS.filter((p) => !REMOTE_DEFAULT_PERMISSIONS.includes(p)).sort()).toEqual(
      ['chat', 'client.query', 'client.sample.inventory', 'client.sample.position', 'client.sample.target', 'controller', 'input.pick.urgent', 'shared.write'],
    );
  });

  it('explains permission_denied without suggesting a prompt, and names the remote defaults for paired devices', () => {
    const denied = (permission?: string) => ({ error: { code: 'permission_denied', message: 'refused', details: permission === undefined ? undefined : { permission } } });
    const local = errorFromPayload(denied('render.world'));
    expect(local).toMatchObject({ code: 'permission_denied', details: { permission: 'render.world' } });
    expect(local.message).toBe('refused (permission "render.world" is not granted to this device; the player can grant it in game under Bridge > Devices)');
    expect(errorFromPayload(denied('chat'), true).message).toBe(
      'refused (permission "chat" is not granted to this device; remotely paired devices start without input.pick.urgent, chat, shared.write, client.sample.position, client.sample.target, client.sample.inventory, client.query, controller; the player can grant it in game under Bridge > Devices)',
    );
    expect(errorFromPayload(denied('render.world'), true).message).not.toMatch(/remotely paired/);
    expect(errorFromPayload(denied(), true).message).toMatch(/a permission is not granted to this device; remotely paired devices start without/);
    expect(errorFromPayload({ error: { code: 'timeout', message: 'late' } }, true).message).toBe('late');
  });

  it('rejects unknown option keys with a suggestion', () => {
    const known = new Set(['items', 'timeoutMs']);
    expect(() => checkKeys('test option', { items: [], timeoutMs: 5, extra: undefined }, known)).not.toThrow();
    expect(() => checkKeys('test option', { options: [] }, known, { options: 'use items' })).toThrow(/unknown test option "options"; use items/);
    expect(() => checkKeys('test option', { TimeoutMS: 1 }, known)).toThrow(/did you mean timeoutMs\?/);
    expect(() => checkKeys('test option', { timeout: 1 }, known)).toThrow(/did you mean timeoutMs\?/);
    expect(() => checkKeys('test option', { zz: 1 }, known)).toThrow(/known: items, timeoutMs/);
    expect(() => checkKeys('test option', [], known)).toThrow(/expected an object of test options/);
    try {
      checkKeys('test option', { zz: 1 }, known);
    } catch (e) {
      expect(e).toBeInstanceOf(BridgeError);
      expect((e as BridgeError).code).toBe('invalid_message');
    }
  });
});

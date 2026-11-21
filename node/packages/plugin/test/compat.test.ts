import { EventEmitter } from 'node:events';
import { describe, expect, it } from 'vitest';
import { createBridge, tickEnd } from '../src/index.ts';

/** A registry whose serverbound play packets include tick_end, shaped like minecraft-data's. */
const REGISTRY_WITH_TICK_END = {
  protocol: { play: { toServer: { types: { packet: ['container', [{ type: ['mapper', { mappings: { '0x0c': 'tick_end', '0x1d': 'position' } }] }]] } } } },
};
const REGISTRY_WITHOUT = {
  protocol: { play: { toServer: { types: { packet: ['container', [{ type: ['mapper', { mappings: { '0x1d': 'position' } }] }]] } } } },
};

class TickBot extends EventEmitter {
  username = 'Alice';
  entity = { position: { x: 0, y: 64, z: 0 }, id: 1 };
  game = { dimension: 'overworld' };
  player = null;
  written: string[] = [];
  _client = { state: 'play', write: (name: string, _params?: object) => void this.written.push(name) };
  constructor(public registry: unknown = REGISTRY_WITH_TICK_END) {
    super();
  }
  /** As mineflayer runs a tick: its listeners first, then the movement packet, when there is one. */
  tick(move = true): void {
    this.emit('physicsTick');
    if (move) this._client.write('position', {});
  }
}

describe('tick_end', () => {
  it('sends tick_end after the movement packet of every physics tick in play', () => {
    const bot = new TickBot();
    tickEnd(bot);
    bot.tick();
    bot.tick();
    expect(bot.written).toEqual(['position', 'tick_end', 'position', 'tick_end']);
  });

  it('ends a tick with no movement packet before the next one, also when ticks run back to back', async () => {
    const bot = new TickBot();
    tickEnd(bot);
    // Catching up: two ticks in one go, the first moves nothing.
    bot.tick(false);
    bot.tick();
    expect(bot.written).toEqual(['tick_end', 'position', 'tick_end']);
    // A lone tick with no move ends once its code has run.
    bot.tick(false);
    await new Promise((r) => setImmediate(r));
    expect(bot.written).toEqual(['tick_end', 'position', 'tick_end', 'tick_end']);
  });

  it('sends nothing on versions without the packet or outside play', () => {
    const old = new TickBot(REGISTRY_WITHOUT);
    tickEnd(old);
    old.tick();
    expect(old.written).toEqual(['position']);
    const login = new TickBot();
    login._client.state = 'configuration';
    tickEnd(login);
    login.emit('physicsTick');
    expect(login.written).toEqual([]);
  });

  it('stops once mineflayer sends tick_end itself', () => {
    const bot = new TickBot();
    tickEnd(bot);
    bot.tick();
    bot._client.write('tick_end', {});
    bot.tick();
    expect(bot.written).toEqual(['position', 'tick_end', 'tick_end', 'position']);
  });

  it('installs once, and the bridge plugin installs it unless told not to', async () => {
    const twice = new TickBot();
    tickEnd(twice);
    tickEnd(twice);
    twice.tick();
    expect(twice.written.filter((n) => n === 'tick_end')).toHaveLength(1);

    const hub = createBridge({ endpoint: 'ws://127.0.0.1:1', reconnect: false });
    const withBridge = new TickBot();
    hub.plugin()(withBridge as never);
    withBridge.tick();
    const without = new TickBot();
    hub.plugin({ tickEnd: false })(without as never);
    without.tick();
    await hub.close();
    expect(withBridge.written).toContain('tick_end');
    expect(without.written).not.toContain('tick_end');
  });
});

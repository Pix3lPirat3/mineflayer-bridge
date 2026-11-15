import { EventEmitter } from 'node:events';
import { createInterface } from 'node:readline';
import { bridge, type BotBridge } from 'mineflayer-bridge';

type Bot = Parameters<ReturnType<typeof bridge>>[0];

/**
 * Loads the real plugin into a fake overworld bot at 0 64 0, waits until the bridge is ready, then
 * emits `spawn`. Returns the bot's bridge API.
 */
export async function startBridge(username: string, options: NonNullable<Parameters<typeof bridge>[0]>): Promise<BotBridge> {
  const bot = Object.assign(new EventEmitter(), {
    username,
    entity: { position: { x: 0, y: 64, z: 0 } },
    game: { dimension: 'minecraft:overworld' },
  });
  bridge(options)(bot as unknown as Bot, { host: 'localhost', port: 25565 } as never);
  const api = (bot as unknown as { bridge: BotBridge }).bridge;
  await api.ready;
  bot.emit('spawn');
  return api;
}

/**
 * Prints `READY`, then reads commands from stdin line by line. `EXIT` closes the bridge and exits
 * with code 0; other lines run the matching handler and unknown lines are ignored.
 */
export async function serveCommands(api: BotBridge, handlers: Readonly<Record<string, () => void>> = {}): Promise<void> {
  console.log('READY');
  for await (const line of createInterface({ input: process.stdin })) {
    if (line === 'EXIT') {
      await api.close();
      process.exit(0);
    }
    if (Object.hasOwn(handlers, line)) handlers[line]!();
  }
}

/** Returns the command-line arguments, or prints `usage` and exits with code 2 when one is missing. */
export function requireArgs(count: number, usage: string): string[] {
  const args = process.argv.slice(2, 2 + count);
  if (args.length < count || args.some((a) => a === '')) {
    console.error(`usage: ${usage}`);
    process.exit(2);
  }
  return args;
}

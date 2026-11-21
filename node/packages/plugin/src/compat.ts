/** The parts of a mineflayer bot {@link tickEnd} uses. */
export interface TickEndBotLike {
  registry?: unknown;
  _client?: { state: string; write(name: string, params: object): void };
  on(event: 'physicsTick', listener: () => void): unknown;
}

const installed = new WeakSet<object>();

/**
 * Sends `tick_end` (ServerboundClientTickEndPacket) at the end of every physics tick, after its movement
 * packet, as vanilla clients do. They send
 * it since 1.21.2, and 26.3 servers kick a client that sends two movement packets without one in
 * between (`multiplayer.disconnect.invalid_player_movement`); mineflayer 4.39 never sends it.
 *
 * The bridge plugin installs this on every bot, so bots need nothing extra. Use it directly only for
 * bots that do not load the bridge. It does nothing on versions without the packet, stops for good
 * once mineflayer sends `tick_end` itself, and installing it twice has no effect.
 *
 * @example
 * const bot = mineflayer.createBot({ host, username });
 * bot.loadPlugin(tickEnd);
 */
export function tickEnd(bot: TickEndBotLike): void {
  if (installed.has(bot)) return;
  installed.add(bot);
  const client = bot._client;
  if (client === undefined || typeof client.write !== 'function') return;
  let supported: boolean | undefined;
  let sending = false;
  let mineflayerSends = false;
  // A tick has run and its end is still to be sent.
  let open = false;
  const write = client.write.bind(client);
  const end = (): void => {
    if (!open) return;
    open = false;
    if (mineflayerSends || !supported || client.state !== 'play') return;
    sending = true;
    try {
      write('tick_end', {});
    } finally {
      sending = false;
    }
  };
  client.write = (name: string, params: object) => {
    if (name === 'tick_end' && !sending) mineflayerSends = true;
    write(name, params);
    // mineflayer writes the tick's movement packet after physicsTick: the end goes right behind it.
    if (MOVES.has(name)) end();
  };
  bot.on('physicsTick', () => {
    if (mineflayerSends) return;
    // A tick that moved nothing ends before the next one begins.
    end();
    // The registry is only complete once the version is known, so support is checked on the first tick.
    supported ??= playPacketNames(bot.registry).includes('tick_end');
    if (!supported || client.state !== 'play') return;
    open = true;
    // A tick with no movement packet ends once its code has run.
    setImmediate(end);
  });
}

/** The movement packets mineflayer writes at the end of a tick. */
const MOVES = new Set(['position', 'position_look', 'look', 'flying']);

/** Names of the serverbound play packets in minecraft-data's protocol for the bot's version. */
function playPacketNames(registry: unknown): string[] {
  const packet = (registry as { protocol?: { play?: { toServer?: { types?: { packet?: unknown } } } } } | undefined)?.protocol?.play?.toServer?.types?.packet;
  const mappings = (packet as [string, [{ type: [string, { mappings?: Record<string, string> }] }]] | undefined)?.[1]?.[0]?.type?.[1]?.mappings;
  return mappings === undefined ? [] : Object.values(mappings);
}

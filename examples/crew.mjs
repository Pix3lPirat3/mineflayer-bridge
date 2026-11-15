// crew.mjs: a controller that gives orders to a small crew of bots. The player writes
// `@crew-1 hop 5`, `@odd hop` or `@all stop`; each order runs once and names the bots it is for.
// A Crew tab on Bridge home lists the bots with buttons, every job is a task the player can pause
// and cancel, and the Bridge map moves the bots when mineflayer-pathfinder is installed.
//
// Run it against a Minecraft client with the mineflayer-bridge mod installed:
//   1. Start Minecraft 26.x with the mod and join the world the bots will join (a LAN world or a
//      local server in offline mode, so several bot names can log in).
//   2. From the repository root: pnpm install && pnpm build
//      For moves from the Bridge map, also: npm install mineflayer-pathfinder
//   3. MC_HOST=localhost MC_PORT=25565 BOTS=3 node examples/crew.mjs
//   4. Allow the connection if the game asks. Then try, in chat:
//        @crew-1 hop 5      one bot       @odd hop     a group       @all stop    everyone
//        @foreman status    the controller's own command
//      Open Bridge home (B) for the Crew tab and the tasks, and the Bridge map to select bots and
//      right-click where they should go.

import mineflayer from 'mineflayer';
import { createBridge, mapOrders, taskHud, tasks } from 'mineflayer-bridge';

// Optional: with pathfinder loaded, bridge:map walks the bots to where the player clicks.
const pathfinder = await import('mineflayer-pathfinder').then((m) => (m.default ?? m).pathfinder, () => undefined);

const count = Math.max(1, Math.min(8, Number(process.env.BOTS ?? 3)));
const names = Array.from({ length: count }, (_, i) => `crew-${i + 1}`);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const hub = createBridge();
hub.on('connected', () => console.log('bridge connected'));
hub.on('disconnected', ({ reason, retry }) => console.log(`bridge disconnected (${reason}), retry: ${retry}`));

const bots = new Map();
for (const username of names) {
  const bot = mineflayer.createBot({
    host: process.env.MC_HOST ?? 'localhost',
    port: Number(process.env.MC_PORT ?? 25565),
    username,
    auth: 'offline',
  });
  if (pathfinder !== undefined) bot.loadPlugin(pathfinder);
  // tasks: jobs the player sees on Bridge home. taskHud: the same as a HUD panel. mapOrders: moves
  // from the Bridge map, each one a task.
  bot.loadPlugin(hub.plugin({ extensions: [tasks(), taskHud(), mapOrders()] }));
  bot.once('spawn', () => {
    bot.bridge.get('bridge:map').onOrder((order) => console.log(`${username}: map order ${order.type}`, order.pos ?? ''));
  });
  bot.on('error', (err) => console.error(`${username}:`, err.message));
  bots.set(username, bot);
}

// A bot by name, once it has spawned.
const online = (name) => (bots.get(name)?.entity != null ? bots.get(name) : undefined);
// The bots an order is for: `ctx.bots` holds the names the player addressed.
const crewOf = (ctx) => (ctx.bots ?? names).map(online).filter((bot) => bot !== undefined);

// One job: the bot jumps `times` times. The player can pause and cancel it from Bridge home.
async function hop(bot, times) {
  const jobs = bot.bridge.get('bridge:tasks');
  if (jobs.tasks.some((t) => t.name === 'Hop')) return;
  const job = jobs.task('Hop', { total: times });
  try {
    for (let i = 0; i < times; i++) {
      await job.whenRunning();
      bot.setControlState('jump', true);
      await sleep(250);
      bot.setControlState('jump', false);
      await sleep(450);
      job.progress(i + 1);
    }
    job.done(`Hopped ${times} times`);
  } catch {
    // cancelled by the player or by `stop`
    bot.setControlState('jump', false);
  }
}

function stop(bot) {
  bot.bridge.get('bridge:tasks').cancelAll();
  bot.bridge.get('bridge:map').stop();
}

const ctl = hub.controller({ name: 'foreman' });

// Who the orders are for, and groups of them: @odd and @even.
ctl.crew.set({
  bots: names,
  groups: { odd: names.filter((_, i) => i % 2 === 0), even: names.filter((_, i) => i % 2 === 1) },
});

// Orders are typed after a bot, a group or `all`, never after the controller's own name.
ctl.orders.register('hop', {
  description: 'Jump a few times',
  args: [{ name: 'times', type: 'int', min: 1, max: 50, default: 3 }],
  run: ({ times }, ctx) => {
    const crew = crewOf(ctx);
    for (const bot of crew) void hop(bot, times);
    return crew.length === 0 ? 'nobody is online' : `${crew.map((b) => b.username).join(', ')}: hopping ${times} times`;
  },
});
ctl.orders.register('stop', {
  description: 'Stop what you are doing',
  run: (_args, ctx) => {
    const crew = crewOf(ctx);
    crew.forEach(stop);
    return `${crew.length} stopped`;
  },
});

// The controller's own command: @foreman status.
const describe = (bot) => {
  if (bot.entity == null) return 'offline';
  const jobs = bot.bridge.get('bridge:tasks').tasks;
  return jobs.length === 0 ? 'idle' : jobs.map((t) => t.name).join(', ');
};
ctl.commands.register('status', {
  description: 'What every bot is doing',
  run: () => names.map((name) => `${name}: ${describe(bots.get(name))}`).join('; '),
});

// A tab on Bridge home. The rows are built again each time the player opens it.
const tab = ctl.screen('crew', {
  title: 'Crew',
  home: 'tab',
  rows: () => [
    { heading: `${names.length} bots` },
    ...names.map((name) => [
      { text: `${name}: ${describe(bots.get(name))}` },
      { button: 'Hop', id: `hop-${name}`, width: 50, onClick: () => crewOf({ bots: [name] }).forEach((bot) => void hop(bot, 3)) },
      { button: 'Stop', id: `stop-${name}`, width: 50, onClick: () => crewOf({ bots: [name] }).forEach(stop) },
    ]),
    [
      { button: 'Hop all', id: 'hop-all', onClick: () => crewOf({}).forEach((bot) => void hop(bot, 3)) },
      { button: 'Stop all', id: 'stop-all', onClick: () => crewOf({}).forEach(stop) },
    ],
  ],
});
// Keep the tab current while it is open.
setInterval(() => tab.isOpen && tab.refresh(), 1000).unref();

process.on('SIGINT', async () => {
  for (const bot of bots.values()) bot.quit();
  await hub.close();
  process.exit(0);
});

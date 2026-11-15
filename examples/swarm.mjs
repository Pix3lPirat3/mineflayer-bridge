// swarm.mjs: several bots on one hub (one connection to the mod, one session per bot), plus a
// controller session that is not a bot.
//
// Run it against a Minecraft client with the mineflayer-bridge mod installed:
//   1. Start Minecraft 26.x with the mod and join the world the bots will join (a LAN world or a
//      local server in offline mode, so several bot names can log in).
//   2. From the repository root: pnpm install && pnpm build
//   3. MC_HOST=localhost MC_PORT=25565 BOTS=3 node examples/swarm.mjs
//   4. Allow the connection if the game asks; every bot shares it. Run the controller's `swarm status` and
//      `swarm spread 6` commands from the bridge command palette or with /bridge.

import mineflayer from 'mineflayer';
import { createBridge, taskHud, vitalsHud } from 'mineflayer-bridge';

const count = Math.max(1, Math.min(20, Number(process.env.BOTS ?? 3)));

// Extensions given to the hub load into every session, the controller's included; hub.plugin()
// adds more per bot. vitalsHud reads a bot, so it is loaded per bot. taskHud shows the tasks of
// bridge:tasks, which is loaded with it.
const hub = createBridge({ extensions: [taskHud()] });
hub.on('connected', () => console.log('bridge connected'));
hub.on('disconnected', ({ reason, retry }) => console.log(`bridge disconnected (${reason}), retry: ${retry}`));

const bots = [];
for (let i = 1; i <= count; i++) {
  const bot = mineflayer.createBot({
    host: process.env.MC_HOST ?? 'localhost',
    port: Number(process.env.MC_PORT ?? 25565),
    username: `swarm-${i}`,
    auth: 'offline',
  });
  bot.loadPlugin(hub.plugin({ extensions: [vitalsHud({ anchor: 'top-left' })] }));
  bot.once('spawn', () => {
    const tasks = bot.bridge.get('bridge:tasks');
    tasks.task('Idle').status('waiting for orders');
    bot.bridge.layer('marker').entityTag('self', bot.entity, { text: bot.username, outline: '#4fc3f7' });
  });
  bot.on('error', (err) => console.error(`${bot.username}:`, err.message));
  bots.push(bot);
}

const ctl = hub.controller({ name: 'swarm-manager' });
ctl.ready.then(() => ctl.get('bridge:tasks').task('Swarm').status(`${count} bots`));
ctl.commands.register('swarm', {
  description: 'Swarm controls',
  children: {
    status: {
      description: 'List the bots and their health',
      run: () =>
        bots
          .map((b) => `${b.username}: ${b.entity ? `${Math.round(b.health ?? 0)} hp at ${b.entity.position.floored()}` : 'offline'}`)
          .join(', '),
    },
    spread: {
      description: 'Show a ring of target markers around the first bot',
      args: [{ name: 'radius', type: 'int', min: 2, max: 32, default: 6 }],
      run: ({ radius }) => {
        const center = bots[0]?.entity?.position;
        if (center === undefined) return 'no bot has spawned yet';
        const layer = ctl.layer('targets');
        bots.forEach((b, i) => {
          const a = (i / bots.length) * Math.PI * 2;
          const pos = center.offset(Math.cos(a) * radius, 0, Math.sin(a) * radius).floored();
          layer.box(b.username, pos, 1, { style: { color: '#ffd54f', fill: 0.15 }, world: 'global' });
          b.bridge?.get('bridge:tasks')?.task('Idle').status(`assigned to ${pos}`);
        });
        return `assigned ${bots.length} targets`;
      },
    },
  },
});

process.on('SIGINT', async () => {
  for (const b of bots) b.quit();
  await hub.close();
  process.exit(0);
});

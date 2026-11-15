// menus.mjs: the menu toolkit. Chest-style menus with buttons, a structure layout, pagination and a
// confirm dialog. Nothing here is a real container, so no packet reaches the server.
//
// Run it against a Minecraft client with the mineflayer-bridge mod installed:
//   1. Start Minecraft 26.x with the mod and join the world the bot will join.
//   2. From the repository root: pnpm install && pnpm build
//   3. MC_HOST=localhost MC_PORT=25565 MC_USER=menu-demo node examples/menus.mjs
//   4. Allow the connection if the game asks, then open the bot's context menu and pick "Jobs", or run the bot's
//      `jobs` command from the bridge palette, or type `@menu-demo jobs` in chat.

import mineflayer from 'mineflayer';
import { bridge } from 'mineflayer-bridge';

const bot = mineflayer.createBot({
  host: process.env.MC_HOST ?? 'localhost',
  port: Number(process.env.MC_PORT ?? 25565),
  username: process.env.MC_USER ?? 'menu-demo',
  auth: process.env.MC_AUTH ?? 'offline',
});
bot.loadPlugin(bridge());

const ORES = ['coal_ore', 'iron_ore', 'copper_ore', 'gold_ore', 'redstone_ore', 'lapis_ore', 'diamond_ore', 'emerald_ore'];
let job = 'idle';

bot.once('spawn', () => {
  const b = bot.bridge;
  const status = b.hud('job', { title: 'Job', anchor: 'top-right' }).text('job', job);
  const setJob = (next) => {
    job = next;
    status.set(next);
    b.notify(`Job: ${next}`);
  };

  // Ores menu: pages of ore buttons, with back and next buttons on the last row and a search
  // button between them that filters the ores through an anvil prompt.
  const ores = b.menu('ores', { title: 'Pick an ore', rows: 3, filler: 'minecraft:black_stained_glass_pane' });
  ores.paginated('ores', {
    area: { x: 0, y: 0, width: 9, height: 2 },
    entries: ORES.map((ore) => ({
      item: `minecraft:${ore}`,
      name: ore.replace(/_/g, ' '),
      lore: ['Click to mine this ore'],
      close: true,
      onClick: () => setJob(`mining ${ore}`),
    })),
    prev: 18,
    next: 26,
    search: { slot: 22, prompt: 'Find an ore' },
  });

  // Main menu from a structure layout: one character per slot.
  const jobs = b.menu('jobs', { title: 'Jobs', rows: 3, filler: 'minecraft:gray_stained_glass_pane' });
  jobs.layout(['         ', '  M F S  ', '        X'], {
    M: { item: 'minecraft:iron_pickaxe', name: 'Mine', lore: ['Choose an ore'], onClick: () => jobs.open(ores) },
    F: { item: 'minecraft:wheat', name: 'Farm', glint: true, close: true, onClick: () => setJob('farming') },
    S: {
      item: 'minecraft:barrier',
      name: 'Stop',
      onClick: async () => {
        if (await b.menu.confirm('Stop the current job?', { yes: 'Stop', no: 'Keep going' })) setJob('idle');
        jobs.open();
      },
    },
    X: { item: 'minecraft:oak_door', name: 'Close', close: true },
  });

  b.commands.register('jobs', { description: 'Open the jobs menu', run: () => jobs.open() });
  b.actions.register('jobs', { label: 'Jobs', icon: 'minecraft:chest', target: { type: 'bot' }, run: () => jobs.open() });
});

bot.on('error', (err) => console.error(err));
process.on('SIGINT', () => bot.quit());

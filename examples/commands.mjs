// commands.mjs: a command tree with aliases, typed arguments, generated usage lines, client-side
// completion sources and asynchronous completion answered by the bot.
//
// Run it against a Minecraft client with the mineflayer-bridge mod installed:
//   1. Start Minecraft 26.x with the mod and join the world the bot will join.
//   2. From the repository root: pnpm install && pnpm build
//   3. MC_HOST=localhost MC_PORT=25565 MC_USER=helper node examples/commands.mjs
//   4. Allow the connection if the game asks, then type in chat (completion works while typing):
//        /bridge helper inv toss <item> [count]   (items come from the bot's inventory)
//        /bridge helper look @looked
//        @helper say hello there
//      or open the bridge command palette and search for "helper".

import mineflayer from 'mineflayer';
import { bridge } from 'mineflayer-bridge';

const bot = mineflayer.createBot({
  host: process.env.MC_HOST ?? 'localhost',
  port: Number(process.env.MC_PORT ?? 25565),
  username: process.env.MC_USER ?? 'helper',
  auth: process.env.MC_AUTH ?? 'offline',
});
bot.loadPlugin(bridge());

/** Item ids the bot carries, for dynamic completion. */
function inventoryIds() {
  return [...new Set(bot.inventory.items().map((i) => `minecraft:${i.name}`))];
}

bot.once('spawn', () => {
  const b = bot.bridge;

  b.commands.register('inv', {
    description: 'Inventory tools',
    aliases: ['i'],
    children: {
      list: {
        description: 'List what the bot carries',
        run: () => bot.inventory.items().map((i) => `${i.count} ${i.name}`).join(', ') || 'empty',
      },
      toss: {
        aliases: ['drop'],
        description: 'Drop items',
        args: [
          {
            name: 'item',
            type: 'item-id',
            // Answered by the bot within the mod's 300 ms completion deadline.
            complete: (input) => inventoryIds().filter((id) => id.includes(input)),
          },
          { name: 'count', type: 'int', min: 1, default: 1 },
        ],
        run: async ({ item, count }) => {
          const name = String(item).replace(/^minecraft:/, '');
          const stack = bot.inventory.items().find((i) => i.name === name);
          if (stack === undefined) return `no ${name} in the inventory`;
          await bot.toss(stack.type, null, Math.min(count, stack.count));
          return `dropped ${Math.min(count, stack.count)} ${name}`;
        },
      },
    },
  });

  b.commands.register('look', {
    description: 'Look at an entity',
    args: [{ name: 'target', type: 'entity', complete: { source: 'entities', near: 'bot', radius: 32, limit: 10 } }],
    run: async ({ target }) => {
      const entity = bot.entities[target];
      if (entity === undefined) return 'that entity is out of sight';
      await bot.lookAt(entity.position.offset(0, entity.height ?? 1.6, 0));
      return `looking at ${entity.displayName ?? entity.name ?? 'entity'}`;
    },
  });

  b.commands.register('mode', {
    description: 'Set the bot mode',
    args: [{ name: 'mode', type: 'enum', choices: ['passive', 'guard', 'follow'], complete: 'choices' }],
    run: ({ mode }) => {
      b.status({ mode });
      return `mode is now ${mode}`;
    },
  });

  b.commands.register('say', {
    description: 'Say something in server chat',
    args: [{ name: 'text', type: 'greedy' }],
    run: ({ text }) => {
      bot.chat(String(text));
    },
  });

  // Free text addressed to the bot (`@helper hi`) that is not one of its commands.
  b.chat.on('message', (text) => b.chat.print(`You said: ${text}`));
});

bot.on('error', (err) => console.error(err));
process.on('SIGINT', () => bot.quit());

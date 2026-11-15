// pathfinder.mjs: mineflayer-pathfinder with bridge:pathfinder-viz (route by move class, goal
// waypoint, explored-nodes heatmap) and bridge:vitals-hud. A `come` command uses client samples to
// walk to where the player stands.
//
// Run it against a Minecraft client with the mineflayer-bridge mod installed:
//   1. Start Minecraft 26.x with the mod and join the world the bot will join.
//   2. From the repository root: pnpm install && pnpm build
//      This example also needs mineflayer-pathfinder: npm install mineflayer-pathfinder
//      (it is not a dependency of the bridge, so it is not installed by the workspace).
//   3. MC_HOST=localhost MC_PORT=25565 MC_USER=walker node examples/pathfinder.mjs
//   4. Allow the connection if the game asks, then run the bot's commands from the bridge palette or with /bridge:
//        goto <x y z>   walk to a block (completion offers the block under the crosshair)
//        come           walk to you; needs the client.sample.position permission for this
//                       device, off by default and granted by the player in the mod's devices
//        stop
//      Toggle the "nodes" layer (category debug) to see the explored-nodes heatmap.

import mineflayer from 'mineflayer';
import { bridge, pathfinderViz, taskHud, vitalsHud } from 'mineflayer-bridge';

let pathfinderModule;
try {
  pathfinderModule = await import('mineflayer-pathfinder');
} catch {
  console.error('This example needs mineflayer-pathfinder: npm install mineflayer-pathfinder');
  process.exit(1);
}
const { pathfinder, Movements, goals } = pathfinderModule.default ?? pathfinderModule;

const bot = mineflayer.createBot({
  host: process.env.MC_HOST ?? 'localhost',
  port: Number(process.env.MC_PORT ?? 25565),
  username: process.env.MC_USER ?? 'walker',
  auth: process.env.MC_AUTH ?? 'offline',
});
bot.loadPlugin(pathfinder);
bot.loadPlugin(bridge({ extensions: [pathfinderViz({ colorByMove: true }), vitalsHud(), taskHud({ bossbar: true })] }));

bot.once('spawn', () => {
  const b = bot.bridge;
  bot.pathfinder.setMovements(new Movements(bot));
  // taskHud shows the tasks of bridge:tasks, which is loaded with it.
  const tasks = b.get('bridge:tasks');

  const go = (goal, label) => {
    const task = tasks.task('Travel').status(label);
    const start = bot.entity.position.distanceTo(goalCenter(goal) ?? bot.entity.position);
    const onMove = () => {
      const center = goalCenter(goal);
      if (center !== undefined) task.progress(Math.max(0, Math.round(start - bot.entity.position.distanceTo(center))), Math.max(1, Math.round(start)));
    };
    bot.on('move', onMove);
    bot.pathfinder
      .goto(goal)
      .then(() => task.done(`Arrived: ${label}`))
      .catch((err) => task.fail(`Failed: ${err.message}`))
      .finally(() => bot.off('move', onMove));
    return `heading to ${label}`;
  };

  b.commands.register('goto', {
    description: 'Walk to a block',
    args: [{ name: 'pos', type: 'block-pos', complete: 'blocks' }],
    run: ({ pos }) => go(new goals.GoalBlock(pos.x, pos.y, pos.z), `${pos.x} ${pos.y} ${pos.z}`),
  });

  b.commands.register('come', {
    description: 'Walk to the player',
    run: async () => {
      b.samples.request({ rateHz: 2, position: true });
      const sample = b.samples.latest ?? (await nextSample(b.samples, 3000));
      b.samples.stop();
      if (sample?.position === undefined) return 'no position sample: grant "client.sample.position" to this device';
      const p = sample.position;
      return go(new goals.GoalNear(p.x, p.y, p.z, 2), 'the player');
    },
  });

  b.commands.register('stop', {
    description: 'Stop walking',
    run: () => {
      bot.pathfinder.stop();
      tasks.task('Travel').done('Stopped');
    },
  });
});

/** Centre of a goal that has a position, as a Vec3. */
function goalCenter(goal) {
  if (typeof goal.x !== 'number' || typeof goal.z !== 'number') return undefined;
  return bot.entity.position.clone().set(goal.x + 0.5, goal.y ?? bot.entity.position.y, goal.z + 0.5);
}

/** The next client sample, or undefined after `timeoutMs`. */
function nextSample(samples, timeoutMs) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      off();
      resolve(undefined);
    }, timeoutMs);
    const off = samples.on('sample', (s) => {
      clearTimeout(timer);
      off();
      resolve(s);
    });
  });
}

bot.on('error', (err) => console.error(err));
process.on('SIGINT', () => bot.quit());

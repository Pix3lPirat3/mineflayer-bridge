// basic.mjs: one bot, a few world-space shapes and a HUD panel.
//
// Run it against a Minecraft client with the mineflayer-bridge mod installed:
//   1. Start Minecraft 26.x with the mod (Fabric or NeoForge) and join the world the bot will join,
//      for example a singleplayer world opened to LAN or a local server in offline mode.
//      The mod writes <minecraft dir>/config/mineflayer_bridge/endpoint.json on start.
//   2. From the repository root: pnpm install && pnpm build
//      (outside this repository: npm install mineflayer mineflayer-bridge).
//   3. MC_HOST=localhost MC_PORT=25565 MC_USER=bridge-demo node examples/basic.mjs
//   4. If the game asks, allow the connection (it asks only with "Ask before allowing local bots"
//      on), then attach the bot from the bridge menu
//      (attached bots in your world are shown automatically by default).
// MINEFLAYER_BRIDGE_ENDPOINT points the plugin at another endpoint file or URL.

import mineflayer from 'mineflayer';
import { bridge } from 'mineflayer-bridge';

const bot = mineflayer.createBot({
  host: process.env.MC_HOST ?? 'localhost',
  port: Number(process.env.MC_PORT ?? 25565),
  username: process.env.MC_USER ?? 'bridge-demo',
  auth: process.env.MC_AUTH ?? 'offline',
});
bot.loadPlugin(bridge());

bot.once('spawn', () => {
  const b = bot.bridge;
  b.style('marker', { color: '#ff3b30', fill: 0.2, lineWidth: 2, depth: 'both' });

  const shapes = b.layer('demo', { category: 'status' });
  const at = bot.entity.position.floored();
  shapes.box('target', at.offset(3, 0, 0), 1, { style: 'marker' });
  shapes.line('pointer', at.offset(0.5, 1.5, 0.5), at.offset(3.5, 0.5, 0.5), { arrow: true, style: { color: '#ffd54f' } });
  shapes.path('ring', ringPoints(at.offset(0.5, 0.1, 0.5), 4, 24), { style: { color: '#4fc3f7', lineWidth: 2 } });
  shapes.waypoint('home', at, 'Home', { beam: true });
  const label = shapes.text('hello', at.offset(0.5, 2.6, 0.5), `Hello from ${bot.username}`, { billboard: 'center' });

  const hud = b.hud('main', { title: bot.username, anchor: 'top-right' });
  const stats = hud.kv('stats', { Position: fmt(bot.entity.position), Health: bot.health ?? 20 });
  const tick = hud.sparkline('tps', { capacity: 60, min: 0, max: 20 });

  bot.on('move', () => stats.set('Position', fmt(bot.entity.position)));
  bot.on('health', () => stats.set('Health', Math.round(bot.health)));
  // Physics ticks per second, a rough view of how well the bot keeps up.
  let ticks = 0;
  let last = Date.now();
  bot.on('physicsTick', () => {
    ticks++;
    const now = Date.now();
    if (now - last >= 1000) {
      tick.push(Math.min(20, (ticks * 1000) / (now - last)));
      ticks = 0;
      last = now;
    }
  });

  b.on('attached', () => {
    b.notify(`${bot.username} is attached`, { level: 'success' });
    label.update({ text: `Hello from ${bot.username}, attached` });
  });
  b.on('detached', () => label.update({ text: `Hello from ${bot.username}` }));
});

process.on('SIGINT', () => bot.quit());

bot.on('kicked', (reason) => console.error('kicked:', reason));
bot.on('error', (err) => console.error(err));

function ringPoints(center, radius, steps) {
  const points = [];
  for (let i = 0; i <= steps; i++) {
    const a = (i / steps) * Math.PI * 2;
    points.push({ x: center.x + Math.cos(a) * radius, y: center.y, z: center.z + Math.sin(a) * radius });
  }
  return points;
}

function fmt(p) {
  return `${p.x.toFixed(1)} ${p.y.toFixed(1)} ${p.z.toFixed(1)}`;
}

// showcase.mjs: one long-running bot that exposes every bridge feature as a command, so a player can
// try them all from inside the game.
//
// Run it against a Minecraft client with the mineflayer-bridge mod installed (26.x, singleplayer
// opened to LAN or a local server). The bot never joins the server; the bridge talks to the client
// directly, and MC_HOST and MC_PORT name the server the player is on:
//   pnpm install && pnpm build
//   MC_HOST=localhost MC_PORT=25565 node examples/showcase.mjs
// Then type `/bridge Showcase help` in game. Optional: MC_DIMENSION, BRIDGE_ENDPOINT_FILE (or
// MINEFLAYER_BRIDGE_ENDPOINT), BRIDGE_CREDENTIALS, SHOWCASE_OGG (an Ogg Vorbis file the sounds demo
// plays as custom audio), SHOWCASE_RUN (demos to run once the player's position is known, such as
// sounds,shapes, for scripted checks).
//
// The bot is virtual (no mineflayer connection) because mineflayer's protocol data does not cover
// every 26.x release yet; it reports the same server and dimension as the player so shapes render.

import { EventEmitter } from 'node:events';
import { readFileSync } from 'node:fs';
import { bridge } from 'mineflayer-bridge';

const host = process.env.MC_HOST ?? 'localhost';
const port = Number(process.env.MC_PORT ?? 25565);
const bot = Object.assign(new EventEmitter(), {
  username: 'Showcase',
  entity: { position: { x: 0, y: -60, z: 0 } },
  game: { dimension: process.env.MC_DIMENSION ?? 'minecraft:overworld' },
});

const options = { name: 'Showcase' };
if (process.env.BRIDGE_ENDPOINT_FILE) options.endpointFile = process.env.BRIDGE_ENDPOINT_FILE;
if (process.env.BRIDGE_CREDENTIALS) options.credentials = process.env.BRIDGE_CREDENTIALS;
bridge(options)(bot, { host, port });
const b = bot.bridge;
b.on('error', (e) => console.error('[bridge]', e.code ?? '', e.message));
b.on('connected', () => console.log('connected to the client'));
b.on('attached', () => console.log('attached'));
b.on('detached', () => console.log('detached'));

await b.ready;
bot.emit('spawn');
console.log('ready: type /bridge Showcase help in game');

b.samples.request({ rateHz: 4, position: true, target: true, heldItem: true });
let player = { x: 0, y: -60, z: 0, yaw: 0 };
b.samples.on('sample', (s) => {
  if (s.position) player = { ...s.position, yaw: s.yaw ?? player.yaw };
  bot.entity.position = { x: player.x, y: player.y, z: player.z };
});
/** A point `dist` blocks in front of the player, on the player's feet level, offset sideways by `side`. */
function ahead(dist, side = 0, up = 0) {
  const r = (player.yaw * Math.PI) / 180;
  const fx = -Math.sin(r), fz = Math.cos(r);
  return { x: player.x + fx * dist - fz * side, y: player.y + up, z: player.z + fz * dist + fx * side };
}
const block = (p) => ({ x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) });

const say = (text, color = '#a7f3d0') => b.chat.print([{ text: '[Showcase] ', color: '#60a5fa', bold: true }, { text, color }]);
async function attempt(name, fn) {
  try {
    const result = await fn();
    if (result !== undefined) say(`${name}: ${typeof result === 'string' ? result : JSON.stringify(result)}`);
    return result;
  } catch (e) {
    say(`${name} failed: ${e.code ?? ''} ${e.message}`, '#fca5a5');
    return undefined;
  }
}

// The HUD texts are templates over variables: the timer below only updates the variables, and the
// client re-renders every text that uses them.
b.vars.setMany({ pos: '?', look: '?', held: '?', load: 0, demo: 'idle' });
const hud = b.hud('main', { title: 'Showcase', anchor: 'top-right' });
hud.kv('you', { Position: '{pos}', Looking: '{look}', Holding: '{held}' });
const load = hud.bar('load', { value: 0, max: 100, label: 'Demo load {load:int}%', color: '#22c55e' });
const wave = hud.sparkline('wave', { capacity: 60, color: '#38bdf8' });
const badge = hud.badge('state', 'READY', '#3b82f6');
hud.item('icon', 'minecraft:nether_star', { label: 'Showcase bot' });
const boss = b.bossbar('demo', { text: 'Showcase {demo}', progress: 0, color: 'blue' });
// Focus: the badge follows the player's focused bot; the sparkline is detail, sent only while focused.
b.on('focus', (focused) => badge.set(focused ? 'FOCUSED' : 'READY', focused ? '#22c55e' : '#3b82f6'));
let t = 0;
setInterval(() => {
  t += 1;
  if (b.focused) wave.push(10 + Math.sin(t / 3) * 8);
  const value = (t * 7) % 100;
  load.set(value);
  const s = b.samples.latest;
  b.vars.setMany({
    load: value,
    pos: s?.position ? `${s.position.x.toFixed(1)}, ${s.position.y.toFixed(1)}, ${s.position.z.toFixed(1)}` : 'grant client samples',
    look: s?.target ? `${s.target.type} ${s.target.block ? `${s.target.block.x},${s.target.block.y},${s.target.block.z}` : s.target.entity ?? ''}` : '-',
    held: s?.heldItem?.id ?? '-',
  });
}, 500).unref();

const settings = b.settings.define({
  color: { type: 'color', default: '#f59e0b', label: 'Shape colour' },
  size: { type: 'int', min: 1, max: 8, default: 2, label: 'Box size' },
  sparkle: { type: 'bool', default: true, label: 'Particles on demos' },
}, { group: 'showcase', title: 'Showcase' });
settings.on('change', (key, value) => say(`setting ${key} = ${value}`));

const layer = b.layer('showcase');
let droneTimer;
let chime;
const sparkle = (p) => settings.get('sparkle') && b.effects.particles('happy_villager', { at: p, count: 12, spread: 0.6 });

const demos = {
  shapes: ['Boxes, a line, a path, labels and a waypoint, shown together', () => {
    const c = ahead(6);
    const size = settings.get('size');
    // One transaction: the client shows all of them in the same frame.
    b.transaction(() => {
      layer.box('box', { x: c.x - size / 2, y: c.y, z: c.z - size / 2 }, size, { style: { fill: 0.25 }, color: '#ffffff', ttlMs: 60_000 });
      layer.line('line', ahead(3, -3, 1), ahead(9, 3, 1), { color: '#ef4444' });
      layer.path('path', [ahead(3, 4), ahead(6, 6, 1), ahead(9, 4, 2), ahead(12, 6, 1)], { color: '#22c55e' });
      layer.label('label', ahead(6, 0, size + 1), 'Shapes from the bot, demo load {load:int}%');
      layer.waypoint('wp', ahead(14, 0, 1), 'Waypoint');
    });
    sparkle(c);
    return 'drawn in front of you (box fades after 60 s)';
  }],
  editable: ['Shift+click to edit a floating text and a box', () => {
    const note = layer.text('note', ahead(5, -1.5, 1.5), 'Shift+click to edit me', {
      billboard: 'center', background: '#80000000',
      editable: [{ field: 'text', label: 'Note' }, { field: 'pos', editor: 'gizmo', label: 'Position' }],
    });
    note.on('edited', (data, previous, { fields }) => say(`note ${fields.join(' and ')} changed: "${previous.text}" -> "${data.text}"`));
    const c = block(ahead(6, 2));
    const crate = layer.box('crate', c, 2, { color: '#f59e0b', style: { fill: 0.2 }, editable: [{ field: 'min', editor: 'gizmo' }, 'size'] });
    // The bot keeps the final say: edits that make the box larger than 6 blocks are refused.
    crate.onEdit((proposed) => Math.max(proposed.size.x, proposed.size.y, proposed.size.z) <= 6 || 'the crate can be at most 6 blocks');
    crate.on('edited', (data) => say(`crate is now ${data.size.x}x${data.size.y}x${data.size.z} at ${data.min.x}, ${data.min.y}, ${data.min.z}`));
    return 'Shift+left-click the text or the box to edit them';
  }],
  focus: ['Ask the player to focus this bot', () => {
    b.requestFocus('the showcase wants your attention');
    return b.focused ? 'already focused' : 'asked; accept it in the toast';
  }],
  displays: ['Floating text, items, blocks and interactive Yes/No', () => {
    // traits.actions: the hologram offers the inspect action in its context menu.
    layer.text('title', ahead(6, 0, 2.5), 'Client-only displays', { billboard: 'center', background: '#80000000', scale: 1.2, actions: ['inspect-hologram'] });
    layer.item('pick', ahead(6, -2, 1), { id: 'minecraft:diamond_pickaxe', glint: true }, { animate: 'spin', scale: 1.4 });
    layer.block('beacon', ahead(6, 2, 0), 'minecraft:beacon', { animate: { type: 'bob', amplitude: 0.2, periodMs: 1500 } });
    layer.text('yes', ahead(5, -1, 1.5), 'Yes', { billboard: 'center', background: '#8022c55e', hover: { scale: 1.3 },
      interactive: { tooltip: 'Click me', on: { primary: () => say('you clicked Yes'), secondary: () => say('right-clicked Yes') } } });
    layer.text('no', ahead(5, 1, 1.5), 'No', { billboard: 'center', background: '#80ef4444', hover: { scale: 1.3 },
      interactive: { on: { primary: () => say('you clicked No'), scroll: (e) => say(`scrolled ${e.delta} on No`) } } });
    return 'look at Yes / No and click or scroll';
  }],
  entities: ['Entity models with properties', () => {
    layer.entity('stand', ahead(7, -4), 'minecraft:armor_stand', { yaw: player.yaw + 180, name: 'Shop', nameVisible: true, armorStand: { showArms: true, pose: { rightArm: [-90, 0, 0] } }, equipment: { head: 'minecraft:diamond_helmet', mainhand: 'minecraft:golden_sword' } });
    layer.entity('ghost', ahead(7, -2), 'minecraft:armor_stand', { yaw: player.yaw + 180, invisible: true, name: 'Invisible', nameVisible: true, equipment: { head: 'minecraft:golden_helmet' } });
    layer.entity('creeper', ahead(7, 0), 'minecraft:creeper', { yaw: player.yaw + 180, charged: true, name: 'charged', nameVisible: true });
    layer.entity('zombie', ahead(7, 2), 'minecraft:zombie', { yaw: player.yaw + 180, onFire: true, glow: '#22c55e' });
    layer.entity('sheep', ahead(7, 4), 'minecraft:sheep', { yaw: player.yaw + 180, color: 'magenta' });
    layer.entity('villager', ahead(7, 6), 'minecraft:villager', { yaw: player.yaw + 180, profession: 'librarian', effects: ['speed'] });
    return 'six entity displays, none exist on the server';
  }],
  ghost: ['A ghost-block blueprint compared with the world', () => {
    const o = block(ahead(8, -3));
    const v = b.volume();
    v.fill({ x: o.x, y: o.y - 1, z: o.z }, { x: o.x + 6, y: o.y - 1, z: o.z + 6 }, 'minecraft:oak_planks');
    for (let y = o.y; y <= o.y + 2; y++) for (let i = 0; i <= 6; i++) {
      for (const [x, z] of [[o.x + i, o.z], [o.x + i, o.z + 6], [o.x, o.z + i], [o.x + 6, o.z + i]]) v.set({ x, y, z }, 'minecraft:stone_bricks');
    }
    v.fill({ x: o.x, y: o.y + 3, z: o.z }, { x: o.x + 6, y: o.y + 3, z: o.z + 6 }, 'minecraft:glass');
    layer.ghostBlocks('house', v, { alpha: 0.5, diffAgainstWorld: true });
    return 'place real blocks inside it: correct ones disappear, wrong ones tint';
  }],
  heatmap: ['A heatmap and block highlights', () => {
    const c = block(ahead(8));
    const cells = [], values = [];
    for (let dx = -8; dx <= 8; dx++) for (let dz = -8; dz <= 8; dz++) { cells.push([c.x + dx, c.y - 1, c.z + dz]); values.push(Math.sin(dx / 3) + Math.cos(dz / 4)); }
    layer.heatmap('heat', cells, values, { colormap: 'turbo', alpha: 0.5 });
    const ring = [];
    for (let a = 0; a < 48; a++) ring.push([Math.round(c.x + Math.cos(a / 7.6) * 10), c.y - 1, Math.round(c.z + Math.sin(a / 7.6) * 10)]);
    layer.blocks('ring', ring, { mode: 'both', style: { color: '#facc15', fill: 0.3 } });
    return 'heatmap and a highlighted ring';
  }],
  zones: ['Shared zone, point and route (edit them with the Zone tool)', async () => {
    const c = block(ahead(8));
    await b.shared.zones.create({ name: 'Showcase zone', boxes: [[{ x: c.x - 4, y: c.y - 1, z: c.z - 4 }, { x: c.x + 4, y: c.y + 3, z: c.z + 4 }]], tags: ['showcase'] });
    await b.shared.points.create({ name: 'Showcase point', pos: { x: c.x + 6.5, y: c.y, z: c.z + 0.5 }, icon: 'minecraft:red_bed', tags: ['showcase'] });
    await b.shared.routes.create({ name: 'Showcase route', nodes: [ahead(4, -6), ahead(12, -6), ahead(12, 6), ahead(4, 6)], looped: true, interpolation: 'catmull_rom', tags: ['showcase'] });
    return 'created; select the Zone tool (tool wheel) and click the zone to drag its corners; Ctrl+Z undoes';
  }],
  'where-am-i': ['Spatial queries over shared zones', () => {
    const inside = b.shared.zones.containing(player).map((z) => z.data?.name ?? z.key);
    const near = b.shared.points.nearest(player);
    return `inside: ${inside.join(', ') || 'no zone'}; nearest point: ${near?.data?.name ?? 'none'}`;
  }],
  menu: ['A GUI-plugin style menu', () => {
    const m = b.menu('jobs', { title: 'Showcase menu', rows: 3 });
    m.fill('minecraft:gray_stained_glass_pane');
    m.slot(10, { item: { id: 'minecraft:diamond', count: 1200, glint: true }, name: [{ text: 'Diamonds', color: '#55ffff', bold: true }], lore: ['count above 64, shown compactly'], onClick: () => say('clicked diamonds') });
    m.slot(12, { item: { id: 'minecraft:iron_pickaxe', bar: 0.35, barColor: '#ef4444' }, name: 'Pickaxe with a progress bar', onClick: () => say('clicked pickaxe') });
    m.slot(14, { item: { id: 'minecraft:leather_chestplate', color: '#3b82f6' }, name: 'Dyed item', onClick: () => say('clicked chestplate') });
    m.slot(16, { item: { id: 'minecraft:clock', frames: ['minecraft:clock', 'minecraft:compass'], frameMs: 400 }, name: 'Animated', close: true, onClick: () => say('clicked animated, menu closes') });
    m.open();
    return 'menu opened';
  }],
  storage: ['A storage view: take copies from the top row, drop them below, the bot is told', () => {
    // Storage contents are a Map of slot index to item; source slots (the top row) are never in it.
    const report = (slots) => [...slots].map(([slot, it]) => `${it.count ?? 1}x ${it.id.replace('minecraft:', '')} (slot ${slot})`).join(', ') || 'empty';
    const m = b.menu('fetch', { title: 'Fill the middle row', rows: 3, onStorage: (slots) => say(`storage now: ${report(slots)}`) });
    m.palette([
      { id: 'minecraft:oak_log', count: 64 },
      { id: 'minecraft:cobblestone', count: 64 },
      { id: 'minecraft:iron_ingot', count: 16 },
      { id: 'minecraft:bread', count: 16 },
    ]);
    m.storage({ x: 0, y: 1, width: 9, height: 1 }, 'place');
    m.slot(18, { item: 'minecraft:paper', name: 'How it works', lore: [
      'Top row: infinite sources, click to take a copy',
      'Middle row: drop copies here',
      'Items from your own inventory are copied in too;',
      'your real stacks never move',
    ] });
    m.slot(26, { item: 'minecraft:writable_book', name: 'Report to the bot', onClick: () => say(`storage: ${report(m.view.storage())}`) });
    m.open();
    return 'top row: take copies; middle row: drop them; your own inventory items can be copied in too (client-only, nothing real moves)';
  }],
  sign: ['Sign prompt', async () => `you wrote ${JSON.stringify(await b.prompt.lines('Write anything on the sign', { wood: 'spruce' }))}`],
  anvil: ['Anvil prompt', async () => `you named it "${await b.prompt.text('Name this chest', { surface: 'anvil', item: 'minecraft:chest' })}"`],
  book: ['Book prompt', async () => `pages: ${JSON.stringify(await b.prompt.pages('Write a short log'))}`],
  dialog: ['Vanilla dialog form', async () => JSON.stringify(await b.form({ title: 'Farm settings', surface: 'dialog', fields: [
    { id: 'crop', type: 'select', label: 'Crop', options: ['wheat', 'carrots', 'potatoes'] },
    { id: 'radius', type: 'slider', label: 'Radius', min: 4, max: 64, default: 16 },
    { id: 'replant', type: 'toggle', label: 'Replant', default: true },
    { id: 'note', type: 'text', label: 'Note' }] }))],
  form: ['Bridge form screen', async () => JSON.stringify(await b.form({ title: 'Build order', fields: [
    { id: 'name', type: 'text', label: 'Name', required: true },
    { id: 'count', type: 'number', label: 'Count', min: 1, max: 64, default: 8 },
    { id: 'where', type: 'block-pos', label: 'Where (use Pick)' }] }))],
  confirm: ['HUD confirm card (press Y or N)', async () => `answer: ${await b.prompt.confirm('Do you like the showcase?', { default: true })}`],
  choice: ['HUD choice card (1-3)', async () => `chose ${await b.prompt.choice('Pick a colour', ['red', 'green', 'blue'])}`],
  'world-confirm': ['Confirm floating in the world', async () => `answer: ${await b.prompt.confirm('Floating question?', { surface: 'world', at: ahead(4, 0, 1.5) })}`],
  'chat-prompt': ['Answer in chat', async () => `you said "${await b.prompt.text('Type anything in chat', { surface: 'chat' })}"`],
  worldmenu: ['World menu ring', async () => `chose ${await b.worldMenu({ at: ahead(4, 0, 1.6), layout: 'ring', radius: 1.5, prompt: 'Which item should I drop?', entries: [
    { id: 'cobble', item: { id: 'minecraft:cobblestone', count: 32 }, label: 'Cobblestone' },
    { id: 'iron', item: { id: 'minecraft:iron_ingot', count: 12 }, label: 'Iron' },
    { id: 'bread', item: { id: 'minecraft:bread', count: 5 }, label: 'Bread' },
    { id: 'torch', item: { id: 'minecraft:torch', count: 64 }, label: 'Torches' }] })}`],
  merchant: ['Merchant chooser', async () => `offer ${await b.prompt.trade('Trade with Showcase', [
    { id: 'pick', cost: { id: 'minecraft:emerald', count: 5 }, result: 'minecraft:diamond_pickaxe' },
    { id: 'bread', cost: { id: 'minecraft:emerald', count: 1 }, result: { id: 'minecraft:bread', count: 6 } }])}`],
  stonecutter: ['Stonecutter chooser', async () => `chose ${await b.prompt.choice('What should I mine?', [
    { id: 'iron', item: 'minecraft:iron_ore', label: 'Iron' }, { id: 'gold', item: 'minecraft:gold_ore', label: 'Gold' },
    { id: 'diamond', item: 'minecraft:diamond_ore', label: 'Diamond' }], { surface: 'stonecutter' })}`],
  crafting: ['Crafting grid: arrange a recipe', async () => `pattern ${JSON.stringify(await b.prompt.recipe('Show me a recipe', { items: ['minecraft:oak_planks', 'minecraft:stick', 'minecraft:cobblestone'] }))}`],
  effects: ['Title, sound, particles and action bar', () => {
    b.effects.title('Showcase', { subtitle: 'client-only effects', fadeIn: 5, stay: 60, fadeOut: 10 });
    b.effects.sound('minecraft:block.note_block.pling', { volume: 1, pitch: 1.5 });
    b.effects.particles('heart', { at: ahead(3, 0, 1.5), count: 10, spread: 0.6 });
    b.effects.actionBar('Action bar from the bot');
    b.notify('Toast from the bot', { level: 'success' });
    return 'played';
  }],
  sounds: ['Sound emitters: a looping hum, a flying drone, a chime and custom audio', () => {
    const hum = ahead(6, -3);
    layer.block('beacon', hum, 'minecraft:beacon');
    // Plays while the object exists; the Bot sounds setting and the Ambient slider both apply.
    layer.sound('hum', { x: hum.x + 0.5, y: hum.y + 0.5, z: hum.z + 0.5 }, 'block.beacon.ambient', { category: 'ambient' });
    // A sound in a group follows the group, as a display would; moving the group moves the sound.
    const drone = layer.group('drone', { pos: ahead(6, 3, 2), interpolate: 1000 });
    drone.item('body', [0, 0, 0], 'minecraft:phantom_membrane', { animate: 'spin' });
    drone.sound('buzz', [0, 0, 0], 'entity.bee.loop', { volume: 0.8 });
    let step = 0;
    clearInterval(droneTimer);
    droneTimer = setInterval(() => {
      step++;
      const a = (step * Math.PI) / 4;
      const c = ahead(6, 3, 2);
      drone.move({ x: c.x + Math.cos(a) * 3, y: c.y, z: c.z + Math.sin(a) * 3 }, { interpolate: 1000 });
    }, 1000).unref();
    // Not looping: plays once now and again on every play().
    chime = layer.sound('chime', ahead(3, 0, 1), 'block.amethyst_block.chime', { loop: false, pitch: 1.2 });
    const ogg = process.env.SHOWCASE_OGG;
    if (ogg) layer.sound('custom', ahead(4, 0, 1), readFileSync(ogg), { interval: 4000 });
    return `a beacon hums, a drone circles, the chime plays on "Ring the chime" in the Showcase menu${ogg ? ', and your Ogg file loops' : ' (set SHOWCASE_OGG for custom audio)'}`;
  }],
  clear: ['Remove everything this showcase drew', async () => {
    clearInterval(droneTimer);
    layer.clear();
    for (const z of b.shared.zones.list()) if (z.data?.tags?.includes('showcase')) await b.shared.zones.remove(z.id).catch(() => {});
    for (const p of b.shared.points.list()) if (p.data?.tags?.includes('showcase')) await b.shared.points.remove(p.id).catch(() => {});
    for (const r of b.shared.routes.list()) if (r.data?.tags?.includes('showcase')) await b.shared.routes.remove(r.id).catch(() => {});
    b.vars.set('demo', 'idle');
    boss.update({ progress: 0 });
    return 'cleared';
  }],
};

const picks = ['point', 'block', 'region', 'polygon', 'entity', 'path', 'direction', 'item'];

b.commands.register('help', { description: 'List the demos', run: () => {
  say('Demos: /bridge Showcase <name>. Also: pick <kind>, mine start|stop|give, @Showcase <text> in chat.');
  for (const [name, [desc]] of Object.entries(demos)) say(`  ${name}: ${desc}`, '#e5e7eb');
  say(`  pick <${picks.join('|')}>: ask you to pick something`, '#e5e7eb');
  return undefined;
} });
for (const [name, [description, run]] of Object.entries(demos)) {
  b.commands.register(name, { description, run: async () => {
    b.vars.set('demo', name);
    boss.update({ progress: 1 });
    return attempt(name, run);
  } });
}
// Screens: the same vanilla widgets Bridge home is built from. The menu
// is offered as the player's primary menu: pick it on Bridge home ("Use as primary") or under Tools >
// Primary menu, and the B key opens it, with Bridge home one button away.
const runDemo = (name) => {
  b.vars.set('demo', name);
  boss.update({ progress: 1 });
  return attempt(name, demos[name][1]);
};
const look = { particles: true, size: 'medium', clicks: 0 };
const widgetRows = () => [
  { heading: 'Every item kind' },
  { text: 'Text wraps at the column width and takes a colour. Values below live in the bot.', color: '#a7f3d0' },
  [{ toggle: 'Particles', id: 'particles', value: look.particles, onChange: (v) => { look.particles = v; } },
    { cycle: 'Size', id: 'size', options: ['small', 'medium', 'large'], value: look.size, onChange: (v) => (v === 'large' ? Promise.reject(new Error('large is refused, so the old value comes back')) : (look.size = v, undefined)) }],
  { item: 'minecraft:diamond', text: `An item row; buttons clicked ${look.clicks} times` },
  [{ button: 'Click me', id: 'click', icon: 'minecraft:bell', sound: 'block.note_block.pling', onClick: () => { look.clicks++; menu.refresh(); return `clicked ${look.clicks} times`; } },
    { button: 'Refused', id: 'refused', onClick: () => { throw new Error('the bot refused this click'); } },
    { button: 'Disabled', id: 'disabled', enabled: false }],
  { spacer: true },
  [{ button: 'Open a child screen', id: 'child', open: 'child', width: 160 },
    { button: 'Ring the chime', id: 'chime', icon: 'minecraft:amethyst_shard', onClick: () => (chime ? (chime.play(), undefined) : 'run the sounds demo first') }],
];
const child = b.screen('child', { title: 'A child screen', rows: [
  { text: 'Opened by the mod without a round trip. Back returns to the parent.' },
  { button: 'Close from the bot', id: 'close', onClick: () => child.close() },
] });
const menu = b.screen('menu', {
  title: 'Showcase',
  home: 'menu',
  tabs: () => [
    { id: 'demos', label: 'Demos', rows: Object.entries(demos).map(([name, [desc]]) => [{ button: name, id: `demo-${name}`, width: 80, onClick: () => runDemo(name) }, { text: desc, color: '#9ca3af' }]) },
    { id: 'widgets', label: 'Widgets', rows: widgetRows() },
  ],
});
b.screen('home-tab', { title: 'Showcase', home: 'tab', rows: [
  { heading: 'Added to Bridge home by the Showcase bot' },
  { text: 'Any bot can add tabs here with home: "tab", or offer a whole primary menu with home: "menu".' },
  [{ button: 'Open the Showcase menu', id: 'open-menu', open: 'menu' }, { button: 'Help in chat', id: 'help', onClick: () => { say('Run /bridge Showcase help for the demo list'); } }],
] });

b.commands.register('pick', {
  description: 'Ask the player to pick something',
  args: [{ name: 'kind', type: 'enum', choices: picks }],
  run: async ({ kind }) => attempt(`pick ${kind}`, async () => JSON.stringify(await b.pick[kind]({ prompt: `Pick a ${kind}`, maxVolume: 50_000 }))),
});
b.commands.register('mine', {
  description: 'Command tree with completion', aliases: ['m'],
  children: {
    start: { description: 'Start mining', args: [{ name: 'block', type: 'block-id', complete: 'block-ids' }, { name: 'count', type: 'int', min: 1, max: 64, default: 64 }], run: ({ block, count }) => `would mine ${count} ${block}` },
    stop: { description: 'Stop mining', run: () => 'stopped' },
    give: { description: 'Dynamic completion from the bot', args: [{ name: 'item', type: 'string', complete: async (input) =>
      ['diamond', 'diamond_pickaxe', 'iron_ingot', 'bread', 'torch'].filter((n) => n.startsWith(input)).map((n) => ({ text: n, tooltip: 'in my inventory' })) }],
      run: ({ item }) => `would give ${item}` },
    near: { description: 'Entity completion near the bot', args: [{ name: 'target', type: 'entity', complete: { source: 'entities', near: 'player', radius: 32 } }], run: ({ target }) => `target entity id ${target}` },
  },
});

b.actions.register('inspect-hologram', { label: 'Inspect hologram', icon: 'minecraft:spyglass', target: { type: 'element' },
  run: (t) => `this is ${t.objectKey ?? 'a hologram'}, drawn only in your client` });
b.actions.register('inspect-block', { label: 'Inspect block', icon: 'minecraft:spyglass', target: { type: 'block' },
  run: (t) => `block ${t.block.x}, ${t.block.y}, ${t.block.z} (${t.face ?? ''})` });
b.actions.register('tag-entity', { label: 'Tag this entity', icon: 'minecraft:name_tag', target: { type: 'entity' },
  run: (t) => { layer.entityTag(`tag-${t.entity}`, t.entity, { text: 'Tagged by Showcase', style: { color: '#f472b6' } }); return `tagged entity ${t.entity}`; } });
b.actions.register('come-here', { label: 'Mark this spot', icon: 'minecraft:compass', target: { type: 'air' },
  run: (t) => { if (t.pos) layer.waypoint('spot', t.pos, 'Marked spot'); return 'marked'; } });

let radius = 1;
b.tools.register('marker', { label: 'Marker', icon: 'minecraft:glowstone_dust', targets: ['block'],
  on: {
    primary: (t) => { if (t.block) layer.blocks(`mark-${t.block.x}-${t.block.y}-${t.block.z}`, cube(t.block, radius - 1), { mode: 'both', style: { color: '#a855f7', fill: 0.3 } }); },
    secondary: (t) => { if (t.block) layer.remove(`mark-${t.block.x}-${t.block.y}-${t.block.z}`); },
    scroll: (d) => { radius = Math.max(1, Math.min(4, radius + Math.sign(d))); b.effects.actionBar(`Marker radius ${radius}`); },
  },
  onActivate: () => say('Marker tool on: left click marks, right click unmarks, scroll sizes'),
});
function cube(c, r) {
  const out = [];
  for (let x = -r; x <= r; x++) for (let y = -r; y <= r; y++) for (let z = -r; z <= r; z++) out.push([c.x + x, c.y + y, c.z + z]);
  return out;
}

b.keybinds.register('ping', { label: 'Ping the showcase bot', keys: ['g'], onPress: () => b.notify('G pressed: keybinds work', { level: 'info' }) });

b.intercepts.register('chest-observe', { target: { type: 'block', ids: ['minecraft:chest'] }, input: 'secondary', mode: 'observe',
  run: (t, { cancelled }) => say(`you opened a chest at ${t.block?.x}, ${t.block?.y}, ${t.block?.z}${cancelled ? ' (cancelled by a rule)' : ''}`) });
b.intercepts.register('chest-cancel', { target: { type: 'block', ids: ['minecraft:chest'] }, input: 'secondary', mode: 'cancel', modifier: 'shift', priority: 'high',
  run: () => { demos.menu[1](); return 'shift-right-click on a chest opens the bot menu instead'; } });

b.chat.on('message', (text, { broadcast }) => say(`you said "${text}"${broadcast ? ' to everyone' : ''}`));

process.on('SIGINT', async () => { await b.close(); process.exit(0); });

const autorun = (process.env.SHOWCASE_RUN ?? '').split(',').map((d) => d.trim()).filter((d) => d !== '');
let autorunDone = autorun.length === 0;
b.samples.on('sample', () => {
  if (autorunDone) return;
  autorunDone = true;
  for (const name of autorun) {
    if (name in demos) void runDemo(name);
    else say(`SHOWCASE_RUN: no demo named ${name}`, '#fca5a5');
  }
});

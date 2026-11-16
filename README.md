# mineflayer-bridge

A client-side Minecraft mod and a [mineflayer](https://github.com/PrismarineJS/mineflayer) plugin
that let bots draw in a real player's game: world overlays, HUD panels, menus, prompts, commands,
chat lines and more. The bot declares what to show; the mod renders it and sends back what the
player does.

The mod is client side only. It never sends packets to the game server, and nothing it shows
exists on the server. Bridge traffic goes over its own local link, which the server cannot see, so
the mod works on any server and gives the player no advantage there.

## Status

Pre-release, version 0.1.0-alpha.10, for Minecraft 26.3 on Fabric and NeoForge. Releases are on
[GitHub Releases](https://github.com/Pix3lPirat3/mineflayer-bridge/releases) only: the Fabric and
NeoForge jars and the plugin tarball.

API reference for 0.1.0-alpha.10: [Node plugin](https://pix3lpirat3.github.io/mineflayer-bridge/node/),
[mod extension API (Java)](https://pix3lpirat3.github.io/mineflayer-bridge/java/api/) and
[client extension API (Java)](https://pix3lpirat3.github.io/mineflayer-bridge/java/client/).

## Install

### Players

Download the jar for your loader from the releases page and put it in your `mods` folder: the
Fabric jar (needs Fabric Loader and Fabric API) or the NeoForge jar, for Minecraft 26.3.

In game, press **B** to open Bridge home, where connected bots, their layers, settings, tasks and
devices live. Other keys: **K** command palette, **R** (hold) context actions, **V** tool wheel; all
of them are under Options > Controls > Mineflayer Bridge. The tool wheel's tools also answer to `/bridge tool zone|point|path|ruler|off`, and the
map has a legend that switches players, bots, mobs, items, zones, points and routes on and off.
Right-clicking a mob, player or point on the map opens the same actions as holding R on it, for the
bots selected there. The first time you join a world the mod
prints a short how-to in chat, and a one-line reminder until you have opened the menu ("Tips on
joining" in the settings turns it off or shows it every join). The
mod settings are under Tools > Settings on Bridge home, and in Mod Menu (Fabric) or the mods list
(NeoForge). By default the mod listens on loopback only, so only programs on your own machine can
connect. Turn on "Ask before allowing local bots" in the settings to approve each new one in game.

### Bot authors

Node 22.12 or newer. Install the plugin from the release tarball:

```sh
npm install --allow-remote=all mineflayer https://github.com/Pix3lPirat3/mineflayer-bridge/releases/download/v0.1.0-alpha.10/mineflayer-bridge-0.1.0-alpha.10.tgz
```

npm 12 refuses tarball URLs unless `allow-remote` permits them, so later installs of the same
project need it too: put `allow-remote=all` in the project's `.npmrc`. Or download the tarball and
`npm install ./mineflayer-bridge-0.1.0-alpha.10.tgz`, which needs no setting.

The package is ESM; CommonJS code can `require()` it on Node 22.12+. TypeScript users get
`bot.bridge` on mineflayer's `Bot` type with:

```ts
/// <reference types="mineflayer-bridge/mineflayer" />
```

Every start, the mod writes `<minecraft dir>/config/mineflayer_bridge/endpoint.json` with its URL
and a fresh token, and the plugin reads it on every connection attempt. Point it elsewhere with the
`MINEFLAYER_BRIDGE_ENDPOINT` environment variable (a `ws://` URL or a file path) or the `endpoint`
and `endpointFile` options.

One process normally makes one hub with `createBridge` and loads its plugin into every bot. All
bots then share one connection:

```js
import { createBridge } from 'mineflayer-bridge'

const hub = createBridge({ store: './bridge-store.json' })   // options apply to every bot
bot.loadPlugin(hub.plugin())
// ...
await hub.close()   // on shutdown
```

The plugin also sends `tick_end` after every physics tick: 26.3 servers kick clients that never send
it, and mineflayer 4.39 does not. It stops by itself once mineflayer sends the packet, and
`plugin({ tickEnd: false })` turns it off.

`bridge()` is the shorthand for a single bot: `bot.loadPlugin(bridge())`. If the game restarts or
the link drops, the plugin reconnects with backoff, resumes each session where it can, and otherwise
declares everything again. Start the bot and the game in any order.

## Quick start

Start Minecraft with the mod, join a world the bot can join too (a local server in offline mode, or
a singleplayer world opened to LAN), then run:

```js
import mineflayer from 'mineflayer'
import { bridge } from 'mineflayer-bridge'

const bot = mineflayer.createBot({ host: 'localhost', port: 25565, username: 'miner-01', auth: 'offline' })
bot.loadPlugin(bridge())

bot.once('spawn', () => {
  const b = bot.bridge
  const at = bot.entity.position.floored()

  const marks = b.layer('marks')
  marks.box('target', at.offset(3, 0, 0), 1, { style: { color: '#ff3b30', fill: 0.2 } })
  marks.waypoint('home', at, 'Home', { beam: true })
  marks.text('hello', at.offset(0.5, 2.6, 0.5), `Hello from ${bot.username}`, { billboard: 'center' })

  const hud = b.hud('main', { title: bot.username, anchor: 'top-right' })
  const stats = hud.kv('stats', { Health: bot.health })
  bot.on('health', () => stats.set('Health', Math.round(bot.health)))

  b.commands.register('come', {
    description: 'Walk to me',
    run: () => 'on my way',   // a returned string is shown to the player
  })

  b.on('attached', () => b.notify(`${bot.username} is attached`, { level: 'success' }))
})
```

A bot on your own machine connects without a question, unless you turned on "Ask before allowing
local bots"; then allow it when the game asks. Bots in your world are attached automatically by default;
otherwise attach the bot from Bridge home. More complete scripts are in [`examples/`](examples):
`basic.mjs`, `swarm.mjs`, `crew.mjs`, `menus.mjs`, `commands.mjs`, `pathfinder.mjs` and `showcase.mjs`.

## Concepts

- **Declared state.** Shapes, HUD panels, commands, menus and so on are declarations. The plugin
  keeps them and sends them again after a reconnect. Writes made in one tick go out as one batch, and
  changing something sends only the changed fields.
- **Handles.** Most calls return a handle with `update(patch)`, `remove()` and `alive`. Calling the
  same method with the same id again updates the same object and returns the same handle. In a
  patch, `null` restores a field's default.
- **Worlds.** World objects are bound to the bot's world (server and dimension) and only render
  while the player is in it. Pass `world: 'global'` to opt out.
- **Inputs.** Positions accept a mineflayer `Vec3`, `{ x, y, z }` or `[x, y, z]`. Colours accept
  `'#rrggbb'`, `'#rrggbbaa'` or a number. Item inputs accept an id, a mineflayer `Item`, or an object
  with `id`, `count`, `name`, `lore`, `glint` and other presentation fields.
- **State and events.** `bot.bridge.state` holds `connected`, `attached`, `worldMatched` and more;
  `bot.bridge.on(event, fn)` returns a remover and reports `connected`, `attached`, `detached`, `worldMatched`, `focus`,
  `permissions`, `player`, `refused`, `error` and others. `bot.bridge.players` lists the linked
  players with their `name` and `uuid`; handlers get the one who acted as `ctx.player`.
- **Errors.** Every failure is a `BridgeError` with a stable `code`, such as `cancelled`, `timeout`,
  `cooldown`, `conflict`, `permission_denied`, `quota_exceeded` or `invalid_message`. What no caller
  could catch (a handler that threw, an extension that failed to load, a session the mod refused)
  goes to the `error` event; while nothing listens for it, it is logged instead of lost.
- **Limits.** `bot.bridge.limits` holds what the mod allows. A declaration past them throws
  `quota_exceeded` at the call: an object too large for one frame, too many objects, interactive
  objects, displays or emitters.
- **Now or never.** `notify` and `chat.print` are about the moment, so they are dropped while the
  session is not open (and the mod shows a notification only while the bot is attached, at most 10
  a second); wait for `bot.bridge.ready` or the `connected` event first. Other one-off
  messages wait for the session, the newest 256 of them. After the bot ends, `bot.bridge` stays in
  place and ignores calls.
- **Connecting.** `await bot.bridge.ready` resolves once the session is open and rejects when an
  extension fails to load or the mod refuses the session for good (for example because the bot's
  identity belongs to another device). The plugin reconnects with a growing delay, which starts over
  once a connection has lasted 10 seconds (`reconnect.stableMs`).

## Features

### Layers and shapes

```js
const nodes = bot.bridge.layer('nodes', { category: 'debug', defaultVisible: false })
bot.bridge.style('danger', { color: '#ff3b30', fill: 0.15, lineWidth: 2, depth: 'both', pulse: true })

nodes.box('lava', pos, [1, 1, 1], { style: 'danger', ttlMs: 10_000 })
nodes.line('aim', from, to, { arrow: true })
nodes.path('route', points, { color: '#4fc3f7' })
nodes.label('note', pos, 'Dig here')
nodes.entityTag('target', entityId, { text: 'Target', tracer: true })
nodes.clear()
```

The player toggles layers per bot, per layer, per extension or per category. `layer.isVisible()`
is true only while someone can see the layer; use it to skip work.

Retained shapes carry large data, streamed once and cached by the mod: ghost-block volumes (with
an optional diff against the player's world), heatmaps and large block-highlight sets.

```js
const vol = bot.bridge.volume()
vol.fill([0, 64, 0], [9, 64, 9], 'minecraft:stone_bricks')
bot.bridge.layer('plan').ghostBlocks('house', vol, { origin: base, diffAgainstWorld: true })
layer.heatmap('danger', cells, values, { colormap: 'magma' })
layer.blocks('ores', positions, { mode: 'faces' })
```

### Displays

Displays show vanilla content in the world without it existing there: text, items, blocks and
entity models, including player skins. Clicking one never sends an attack or use packet.

```js
const holo = bot.bridge.layer('holo')
const title = holo.text('title', pos.offset(0, 2, 0), 'Smelting 12/64', { billboard: 'center' })
holo.item('ore', pos, 'minecraft:iron_ore', { animate: 'spin', glow: '#ffd700' })
holo.block('ghost', pos, 'minecraft:chest[facing=north]', { scale: 0.5 })
holo.entity('me', pos, 'minecraft:player', { skin: bot.username, equipment: { mainhand: 'diamond_pickaxe' } })

title.move(pos.offset(0, 3, 0), { interpolate: 300 })   // animated on the client

const tag = holo.group('tag', { follow: 'self', offset: [0, 2.3, 0] })   // children move with the group
tag.text('name', [0, 0, 0], bot.username)
```

Any object can be made interactive. Handlers run in the bot; the click never reaches vanilla:

```js
holo.item('offer', pos, 'minecraft:diamond', {
  hover: { scale: 1.2 },
  interactive: { tooltip: 'Click to take', on: { primary: () => give(1), secondary: () => give('all') } },
})
```

Objects can also be `editable` by the player (Shift and click, with text, colour, number or move
gizmo editors; the bot can accept or reject each edit with `handle.onEdit`), carry context
`actions`, and show only when near, focused or looked at (`visibleWhen`). Layers also have
`sound` (looping or repeating sound emitters, including custom Ogg files) and `emitter` (particles).
One-shot sounds, particles, titles and action-bar text are in `bot.bridge.effects`.

### HUD

```js
const hud = bot.bridge.hud('main', { title: bot.username, anchor: 'top-right' })
const stats = hud.kv('stats', { Task: 'Mining', Queue: 3 })
const bar = hud.bar('progress', { value: 34, max: 64, label: 'Iron' })
const tps = hud.sparkline('tps', { capacity: 120 })
hud.badge('state', 'busy', '#ff3b30')

stats.set('Queue', 2)
bar.set(35)
tps.push(19.8)

const boss = bot.bridge.bossbar('task', { text: 'Clearing area', progress: 0, color: 'green' })
boss.update({ progress: 0.5 })
```

Panels also have `text`, `list` and `item`; `hud.remove(id)` removes one widget and `hud.destroy()`
the panel. Text in HUD widgets, labels, boss bars and text displays
can use variables, which the mod fills in, so one change updates every text that uses it:

```js
bot.bridge.vars.setMany({ task: 'Mining', done: 0, total: 64 })
hud.text('line', '{task} {done}/{total}')   // also {name:int}, {name:1f}, {name:pct}
bot.bridge.vars.set('done', 12)
```

`bot.bridge.transaction(fn)` makes the mod apply every write made in `fn` together.

### Menus, container views and screens

Container views are vanilla-styled inventory screens whose slots the bot fills. They are never real
containers, and the player's real inventory is never touched. Menus build on them, like server GUI
plugins:

```js
const menu = bot.bridge.menu('jobs', { title: 'Jobs', rows: 3 })
menu.slot(11, { item: 'iron_pickaxe', name: 'Mine', lore: ['Strip-mine at y -58'], onClick: () => startMining() })
menu.slot(15, { item: 'wheat', name: 'Farm', close: true, onClick: () => startFarming() })
menu.fill('gray_stained_glass_pane')
menu.open()

const shop = bot.bridge.menu('shop', { title: 'Shop', rows: 3 })
const pages = shop.paginated('offers', {
  area: { x: 0, y: 0, width: 9, height: 2 },
  entries: offers.map((o) => ({ item: o.item, name: o.name, onClick: () => buy(o) })),
  prev: 18, next: 26, search: { slot: 22 },
})

if (await bot.bridge.menu.confirm('Delete waypoint?', { yes: 'Delete', no: 'Keep' })) deleteIt()
```

The menu toolkit also has string layouts, panes (paginated, scrolling, tabs), toggles, cycles,
counters, sliders, child menus with Back buttons and persisted state. A raw
`bot.bridge.containerView(id, { layout, slots, policies, on })` gives full control, including slot
policies with virtual storage the player can move items in and out of.

Screens are the vanilla-widget screens Bridge home is built from. Anything Bridge home shows, a bot
can show too:

```js
const main = bot.bridge.screen('main', {
  title: 'Miner',
  home: 'tab',   // also a tab on Bridge home; 'menu' offers it as the player's primary menu
  tabs: () => [{
    id: 'work', label: 'Work',
    rows: [
      { heading: 'Jobs' },
      [{ button: 'Mine', icon: 'iron_pickaxe', onClick: () => startMining() }],
      { toggle: 'Auto-deposit', id: 'deposit', value: prefs.deposit, onChange: (v) => { prefs.deposit = v } },
    ],
  }],
})
```

Even when a bot's screen is the primary menu, Bridge home stays one button away, so a broken bot
cannot lock the player out.

### Commands, actions, keybinds and tools

Commands run from `/bridge <bot> ...`, the command palette and `@bot` chat lines, all with the same
parser, usage lines and completion. Handlers get `ctx.source` (`chat`, `command`, `palette`, `menu`,
`screen` or `map`), so later messages can answer the way the player asked. Aliases parse but stay out
of completion unless Settings > "Aliases in command suggestions" is on. A command typed in chat or with `/bridge` answers in chat, one
line per line of its reply; buttons, actions and the palette answer with a toast (a long reply goes to
chat as well). Nothing reaches the server: if a server has its own `/bridge`, or uses
`@` in chat, rename the command or change the prefix in the mod's Settings; the mod warns when a
server's commands clash.

```js
bot.bridge.commands.register('mine', {
  description: 'Mining jobs',
  aliases: ['m'],
  children: {
    start: {
      args: [
        { name: 'block', type: 'block-id', complete: 'block-ids' },
        { name: 'count', type: 'int', min: 1, default: 64 },
      ],
      run: ({ block, count }) => startMining(block, count),
    },
    stop: { run: () => stopMining() },
  },
})

bot.bridge.actions.register('deposit', {
  label: 'Deposit here', icon: 'minecraft:chest',
  target: { type: 'block', tags: ['c:chests'] },
  run: (target) => depositInto(target.block),
})

// Kinds the game has no tag for: mineflayer_bridge:player, :bot, :hostile, :animal, :item.
// From the map, ctx.bots names the bots the player had selected.
bot.bridge.actions.register('attack', {
  label: 'Attack',
  target: { type: 'entity', tags: ['mineflayer_bridge:hostile'] },
  run: (target, ctx) => attack(target.entity, ctx.bots),
})

bot.bridge.keybinds.register('stop', { label: 'Stop', keys: ['g'], onPress: () => bot.pathfinder.stop() })
```

Completion can come from client-side sources (`'players'`, `'entities'`, `'block-ids'`, ...) or
from a function in the bot. Selectors such as `@looked` and `@nearest[type=zombie]` are resolved by
the mod. `tools.register` adds a tool the player selects on the tool wheel, and
`intercepts.register` reacts to (or, with permission, cancels) the player's clicks on real blocks
and entities.

### Picks, prompts and forms

Ask the player for something. One request shows at a time and the rest queue.

```js
const chest  = await bot.bridge.pick.block({ prompt: 'Click the chest', filter: { tags: ['c:chests'] } })
const region = await bot.bridge.pick.region({ prompt: 'Quarry area', maxVolume: 200_000 })
// also pick.point, polygon, entity, path, direction and item

const ok   = await bot.bridge.prompt.confirm('Place 214 blocks?', { default: false, timeoutMs: 30_000 })
const name = await bot.bridge.prompt.text('Name this chest', { surface: 'anvil', item: 'minecraft:chest' })
const sign = await bot.bridge.prompt.lines('Sign text')   // a sign edit screen

const { crop, radius } = await bot.bridge.form({
  title: 'Farm settings',
  fields: [
    { id: 'crop', type: 'select', options: ['wheat', 'carrots'] },
    { id: 'radius', type: 'slider', min: 4, max: 64, default: 16 },
  ],
})

// Buttons besides Save sit in their own row; the one pressed comes back as `button`.
const chest = await bot.bridge.form({
  title: 'Chest',
  fields: [{ id: 'name', type: 'text', default: 'chest1' }],
  buttons: [{ id: 'remove', label: 'Remove container', icon: 'mineflayer_bridge:trash', danger: true }],
})
if (chest.button === 'remove') removeChest()
```

Picks and forms reject with `cancelled`, `timeout` and similar codes (a handler that fails with
`cancelled` ends with nothing shown); prompts resolve with their default at
the deadline. Every call settles: its `timeoutMs` also bounds the wait for a session that is still
connecting, and a lost session rejects with `disconnected`.

The player stays in charge of their screen. After they dismiss one of a bot's surfaces, the mod
refuses that bot's next unasked ones for a while (5 s, then 15 s, then 30 s if they keep
dismissing) with `cooldown`, and it refuses more than 16 waiting requests per session with
`quota_exceeded`. A surface that follows the player running one of the bot's commands or actions
is never held back, and an answered one clears the cooldown. Decide what a refused call does per
call or for the whole bridge:

```js
try {
  await bot.bridge.form({ title: 'Farm settings', fields })
} catch (err) {
  if (err.code === 'cooldown') setTimeout(askAgain, err.retryAfterMs)   // or just leave the player alone
}

// Wait and open again (at most 5 times, and only within the call's own timeoutMs):
await bot.bridge.prompt.confirm('Dump the cobblestone?', { refused: 'wait' })

// One rule for every pick, prompt, form and world menu; a call's own `refused` wins.
bot.loadPlugin(bridge({
  surfaces: { onRefused: ({ surface, code, retryAfterMs, retries }) => (surface === 'form' && retries < 2 ? 'wait' : 'reject') },
}))

// Fired for every refusal; handy for a controller's log. A view or screen the bot opened has no
// promise to reject: besides this event, it closes again with the reason 'refused'.
bot.bridge.on('refused', (r) => console.log(`${r.surface} refused: ${r.code}, retry in ${r.retryAfterMs} ms`))
```

The default is to reject. Prompts can use vanilla-looking surfaces (`dialog`, `anvil`, `sign`, `book`, `chat`,
`world`, and `stonecutter` or `enchanting` for choices; `prompt.trade` and `prompt.recipe` show
merchant and crafting screens). These are the mod's own screens, so no anvil, sign or book packet is
ever sent. `bot.bridge.worldMenu` lays choices out as
displays around a point.

### Settings

Declared settings appear in the bridge settings screen under *Bots*. The game saves the values per
bot and sends them on every connect and change.

```js
const settings = bot.bridge.settings.define({
  radius:  { type: 'int', min: 4, max: 128, default: 32, label: 'Mining radius' },
  replant: { type: 'bool', default: true },
  mode:    { type: 'choice', options: ['fast', 'safe'] },
}, { group: 'mining', title: 'Mining' })

settings.get('radius')
settings.on('change', (key, value) => apply(key, value))
```

### Chat

A client-only channel between the player and the bot; the server never sees it.

```js
bot.bridge.chat.print(['Found ', { text: '12 diamonds', color: '#55ffff', hover: 'at y -58' }])
bot.bridge.chat.on('message', (text) => bot.bridge.chat.print(`You said: ${text}`))
```

A printed line is dropped while the session is not open, like a notification.

The player writes `@miner-01 text` or `@all text`. A line that starts with one of the bot's
commands runs it (`@miner-01 mine start iron_ore 32`); other lines go to `chat.on('message')`, and
lines not addressed to a bot go to the server unchanged. A controller session's commands answer to
its name the same way (`@fleet status`); `@all` means the bots only. Crew orders and groups
(`@farmers harvest`) are described under [Several bots](#several-bots-and-several-players). A line
whose name is one typo away from a known name (`@minr come`) is not sent: the mod offers the
corrected line, or the line with the prefix doubled (`@@minr come`), which always goes to the server
as typed.

Chat spans can carry a click: `action` (a context action of the bot), `command` (one of its command
lines), `suggest` (text for the chat box), `copy` (to the clipboard) or `url` (an http or https link,
opened after the game asks the player).

Notifications go to chat by default; the player can switch them to toasts in Settings (Bot
messages). A notification can carry buttons, and then always goes to chat so they can be clicked:

```js
bot.bridge.notify('Inventory full', {
  level: 'warn',
  sound: 'block.note_block.bell',
  buttons: [{ label: 'Dump', run: () => dumpToChest() }, { label: 'Ignore', run: () => {} }],
})
```

The player's state comes with every session: `bot.bridge.players[0]` has `inWorld`, `alive`, `away`
(no input for the time the player set, 5 minutes by default) and `windowFocused`, and the `player`
event reports changes (`joined`, `left`, `world`, `died`, `respawned`, `away`, `back`, `focus`,
`blur`), so a bot can head somewhere safe while its player is away.

```js
bot.bridge.on('player', (change) => {
  if (change === 'died') bot.bridge.notify('You died; I am coming', { level: 'warn' })
})
```

The player can also bind chat lines to keys on the Hotkeys tab of Bridge home (F6 for
`@farmers stop`); a hotkey runs its line as if typed, and only lines the bridge takes run.

### Shared objects and zones

Zones, points and routes can be shared: they belong to the mod and every bot in the world sees
them, with live views, spatial queries and revision checks on writes. The player can edit them in
game with the Zone, Point and Path tools, with undo.

```js
const { zones, points } = bot.bridge.shared
await zones.create({ name: 'Farm', tags: ['farm'], boxes: [[[0, 60, 0], [15, 70, 15]]] }, { id: 'farm' })
await points.create({ name: 'Home', pos: bot.entity.position, tags: ['home'] })

zones.containing(bot.entity.position)
points.nearest(bot.entity.position, { tag: 'home' })
zones.on('change', (zone) => replan(zone))
```

Private zones, points and routes live on layers (`layer.zone`, `layer.point`, `layer.route`). The
geometry helpers (`zoneContains`, `routePoints`, `closestOnRoute`, ...) are exported for use without
a game.

### What the player's client sees

With the player's permission, a bot can read the player's position, look target and held item
(`bot.bridge.samples.request({ position: true })`) and query entities and blocks the client has
loaded (`await bot.bridge.query.entities({ radius: 32 })`).

## Extensions

Everything above is also available inside an extension: a reusable unit with options, dependencies
and automatic cleanup. On unload, its objects, handlers and pending requests are removed.

```js
import { defineExtension } from 'mineflayer-bridge'

export const pathMarker = defineExtension({
  id: 'acme:path-marker',
  version: '1.0.0',
  requires: { mod: ['core:path@^1'] },
  options: { color: { type: 'string', default: '#4fc3f7' } },   // or any Standard Schema (zod, valibot)
  setup (ctx, options) {
    const route = ctx.layer('route')
    ctx.on(ctx.bot, 'path_update', (r) => route.path('current', r.path, { color: options.color }))
    return { clear: () => route.clear() }   // public API, read with bot.bridge.get(pathMarker)
  },
})

await bot.bridge.use(pathMarker({ color: '#ffb300' }))
bot.bridge.get(pathMarker).clear()        // typed from the extension; get('acme:path-marker') works too
await bot.bridge.unload('acme:path-marker')
```

Extensions load in dependency order. `ctx.whenVisible(layer, fn)` runs expensive work only while
someone can see it, `ctx.state.detail` drops to `reduced` when the mod lowers the level of detail,
and `ctx.store` is a key-value store that persists with the hub's `store` option.

An extension can have a mod half too: a Java `BridgeExtension` registered through the
`mineflayer_bridge` entrypoint on Fabric or `ServiceLoader` on NeoForge, which adds object kinds,
messages and settings. The Node half lists them in `kinds` and `messages` and talks to it with
`ctx.message(type)`. A mod half that also adds to the game (a Bridge home tab, a button on bot rows,
keys, HUD or world drawing) implements `ClientExtension`, described [below](#client-extensions).
See the Java API references for the mod side.

Messages can carry plain values (null, booleans, numbers, strings, arrays and objects), so neither
half needs a code generator. Both halves register the same name. Whole numbers are sent as integers,
other numbers as doubles.

```js
import { defineExtension, plainMessage } from 'mineflayer-bridge'

const NOTE = plainMessage('acme:note', { from: 'both' })      // delivery defaults to 'reliable'

export const notes = defineExtension({
  id: 'acme:notes',
  version: '1.0.0',
  messages: [NOTE],
  setup (ctx) {
    const note = ctx.message(NOTE)
    note.on((msg) => ctx.log.info('mod says', msg))
    note.send({ text: 'hello', pos: [10, 64, -3] })
  },
})
```

```java
public final class Notes implements BridgeExtension {
    static final Message<@Nullable Object> NOTE =
            PlainMessages.message("acme:note", Message.From.BOTH, Message.Delivery.RELIABLE);
    private @Nullable SessionRef last;

    @Override public ExtensionId id() { return ExtensionId.parse("acme:notes"); }
    @Override public SemVer version() { return SemVer.parse("1.0.0"); }

    @Override
    public void register(ExtensionRegistrar r) {
        r.message(NOTE, (ctx, msg) -> {
            last = ctx.session();                    // keep it to send later
            ctx.send(NOTE, Map.of("got", msg));      // reply right away
        });
    }

    // Later, on the client thread, for example from a button.
    void ping() {
        if (last != null && last.isOpen()) {
            last.send(NOTE, Map.of("text", "ping"));  // false when the session closed
        }
    }
}
```

`PlainMessages.message(name, from, delivery)` gives an `api.Message`, the one thing the mod half
registers (`ExtensionRegistrar.message`) and sends (`MessageContext.send`, `SessionRef.send`). Maps
arrive in Java as `Map<String, Object>`, lists as `List<Object>`, whole numbers as `Long` and other
numbers as `Double`. A `SessionRef` survives a reconnect that resumes the session; once the session
ends or a newer one takes over, `isOpen()` is false and `send` returns false.

A message's `delivery` decides what happens to it: `reliable` (the default) arrives in order and
is sent again after a reconnect, `latest` keeps only the newest value, and `unreliable` is dropped
when the session is not open or the connection is busy.

Object kinds work the same way: `plainKind('acme:radar')` listed in `kinds` lets
`ctx.objects.put('dish', 'acme:radar', { range: 12 })` declare an object with a plain value. The mod
half registers the same name with `r.kind(PlainKinds.kind("acme:radar", SemVer.parse("1.0.0")))`;
the overload with a `KindValidator` and a `StoreHandler` checks each write and hears of every write
and removal. For a compact wire format write the codec yourself: `KindType`, `MessageType`, `Codec`,
`WireWriter` and `WireReader` are exported, and `Message.of(type)` wraps such a type on the mod side.

Kind and message names are agreed when the connection is made. The first-party extensions' names
are always offered, so loading one later changes nothing for the others. Loading a third-party
extension with kinds or messages after connecting makes the hub reconnect once to register them,
which starts every session on it afresh; list such extensions in the `extensions` option to avoid
that.

### Client extensions

A mod half that adds to the game implements `ClientExtension` instead of `BridgeExtension` (it
extends it, so the class is still found through the same entrypoint or `ServiceLoader` file).
`registerClient` gets a `ClientRegistrar`: Bridge home tabs (`homeTab`), buttons on bot rows
(`botButton`), keys in the bridge's section of the Controls screen (`keybind`), input stages, and
tick, HUD and world render hooks. `registrar.bridge()` is the `ClientBridge`, a narrow view of the
running bridge that stays valid for the whole session: the sessions and their names, a
`MessageContext` to message a bot's Node half, the player's own objects, the objects and places the
player sees, the crosshair's aim, toasts and chat lines to the bots.

```java
public final class Notes implements ClientExtension {
    static final Message<@Nullable Object> NOTE =
            PlainMessages.message("acme:note", Message.From.BOTH, Message.Delivery.RELIABLE);

    @Override public ExtensionId id() { return ExtensionId.parse("acme:notes"); }
    @Override public SemVer version() { return SemVer.parse("1.0.0"); }

    @Override
    public void register(ExtensionRegistrar r) {
        r.kind(PlainKinds.kind("acme:radar", SemVer.parse("1.0.0")));
        r.message(NOTE, (ctx, msg) -> { });
    }

    @Override
    public void registerClient(ClientRegistrar r) {
        ClientBridge bridge = r.bridge();
        // A "Ping" button on every online bot's row of Bridge home.
        r.botButton(Component.literal("Ping"), "minecraft:bell", botOwner -> {
            MessageContext bot = bridge.session(botOwner);
            if (bot != null) {
                bridge.playerAsked(botOwner);            // the bot may answer with a form or a screen
                bot.send(NOTE, Map.of("text", "ping"));
            }
        });
        r.keybind("acme_notes", InputConstants.UNKNOWN.getValue(),
                () -> bridge.toast("Notes", bridge.sessions().size() + " sessions"));
    }
}
```

`bridge.session(owner).send` throws when that bot's Node side did not load the extension. Hooks run
inside a guard: one that throws is logged and switched off for the rest of the session. Everything
runs on the client thread except world render hooks, and nothing in the API sends a packet to the
game server. The `api` and `api.client` packages are the supported surface; the mod's other packages
change without notice.

### First-party extensions

All are exported from `mineflayer-bridge` and loaded with `bot.bridge.use(...)` or the
`extensions` option.

| Extension | What it does |
|---|---|
| `pathfinderViz()` | mineflayer-pathfinder routes by move type, the goal, and an explored-nodes heatmap |
| `vitalsHud()` | Health, food, saturation, oxygen and effects in a HUD panel |
| `inventoryHud()` | Held item, hotbar and armor in a HUD panel |
| `tasks()` | `task(name).progress(n, total).status(text).done()`: tasks the player can pause, resume and cancel from Bridge home |
| `taskHud()` | The tasks of `tasks()` as a HUD panel and boss bar |
| `botConsole()` | A per-bot log screen with levels, search and clickable positions |
| `mapOrders()` | Orders from the Bridge map: select bots, right-click to move them, Shift to queue |
| `trails()` | Where the bot has been, as a fading trail with marks |
| `schematic()` | Sponge `.schem` files as ghost blocks, with materials and build progress |
| `desyncView()` | The bot's world model compared with the player's world |
| `windowMirror()` | The bot's open window (a chest, a server's selector menu) or its inventory in your game; your clicks are the bot's clicks |
| `selection()` | A two-corner region picked with a tool in the tool wheel, drawn in the world and read by the bot |
| `recorder()` | The connection's protocol frames written to a `.mfbrec` file |
| `ping()` | The player's pings: a key marks the block or entity they look at, up to 192 blocks away |
| `statusTag()` | A few lines of text above the bot, such as its task and health |

```js
await bot.bridge.use(tasks())
const job = bot.bridge.get('bridge:tasks').task('Mine iron', { total: 32, onCancel: () => bot.pathfinder.stop() })
for (let i = 0; i < 32 && !job.signal.aborted; i++) {
  await job.whenRunning()   // waits while the player has it paused
  await mineOne()
  job.progress(i + 1)
}
job.done('Mined 32 iron')
```

```js
await bot.bridge.use(ping(), statusTag())
bot.bridge.get('bridge:ping').on((p) => goTo(p.block ?? p.pos))
bot.bridge.get('bridge:status-tag').set(['Farming wheat', `HP ${bot.health}`])
```

#### The bot's windows

`windowMirror()` shows what the bot has open as a container view. `open()` mirrors the bot's open
window, or its inventory when there is none (`open('inventory')` asks for the inventory). Each
gesture in the view becomes the bot's own `bot.clickWindow` with the button and mode vanilla sends,
so a server selector clicked in your game is clicked by the bot. The view follows the bot to the
next window the server opens, closes when the bot's window closes, and closing it closes the bot's
window. Item names, lore and window titles are read with the bot's prismarine-chat when mineflayer
is installed, and as plain text otherwise.

```js
await bot.bridge.use(windowMirror())
const mirror = bot.bridge.get(windowMirror)
bot.on('windowOpen', () => mirror.open())          // show every window the server opens
bot.bridge.commands.register('inv', { run: () => void mirror.open('inventory') })
```

Options: `title` for the inventory view, `action: false` to leave out the *Open inventory* and
*Open window* context actions on the bot, `closeWindow: false`, `follow: false`, and
`gestures: 'quick'`, which manages the inventory without a cursor: shift-click drops the stack, the
drop key drops one item (Ctrl the stack) and a number key moves the stack to that hotbar slot.
`clickArgs(click, hotbar)` and `layoutFor(size, type)` are exported for bots that build their own
view of a window.

#### Region selection

`selection()` adds a *Region select* tool to the tool wheel: left click sets corner 1 on the block
you look at, right click corner 2. The box and its numbered corners are drawn on the `selection`
layer, and the bot reads the result. It loads into a controller as well as a bot.

```js
await bot.bridge.use(selection())
const sel = bot.bridge.get(selection)
sel.on('change', (area) => console.log(sel.describe()))   // 5 x 1 x 7 (35 blocks) from 1 64 -2 to 5 64 4
bot.bridge.commands.register('farm', {
  run: () => {
    const area = sel.area()                                // { min, max } in whole blocks, or undefined
    return area === undefined ? 'Select a region first' : farm(area)
  },
})
sel.set(1, [0, 64, 0])   // corners can also be set from code
sel.clear()
```

Options: `tool: false` (API only), `label`, `icon`, `notify: false` to stay quiet on clicks, and
`color` for the box.

The mod also ships a spectate extension: Spectate buttons on bot rows move the camera to follow
the bot, with nothing sent to the server. The map extension has a second key, Select bots in view:
the cursor is freed over the world, a drag boxes the bots on screen, and right-click moves them or
offers actions for an entity, as on the map.

## Several bots and several players

One hub serves any number of bots on one connection. `hub.controller({ name })` adds a participant
that is not a bot, such as a dashboard or a bot manager.

```js
const hub = createBridge({ extensions: [vitalsHud()] })   // loaded into every bot
for (const b of bots) b.loadPlugin(hub.plugin({ extensions: [pathfinderViz()] }))
const manager = hub.controller({ name: 'swarm-manager' })
manager.actions.register('add-bot', { label: 'Add bot', scope: 'global', run: () => addBot() })
```

A manager that drives its bots declares a crew and gives it orders. Orders are typed after a bot,
a group or `all`, never after the manager's own name, and run once per line with `ctx.bots` naming
who was addressed, so the manager can split the work between them. Its own commands stay under its
name: `@fleet status` is about the manager, `@farmers harvest` is for the bots.

```js
manager.crew.set({ bots: ['farmer1', 'farmer2', 'miner1'], groups: { farmers: ['farmer1', 'farmer2'] } })
manager.orders.register('harvest', { run: (_args, ctx) => harvest(ctx.bots) })   // @farmer1, @farmers, @all
manager.commands.register('status', { run: () => summary() })                     // @swarm-manager status
```

[`examples/crew.mjs`](examples/crew.mjs) is a complete controller with a crew: orders, groups, a
tab on Bridge home, tasks and moves from the Bridge map.

The palette and the Bridge home screen offer orders too, with a "For" choice of bot, group or all;
a bot's Commands button lists the orders that reach it and runs them for that bot. The palette
lists the commands run from screens most recently first, to run again with the same values.

The player has one focused bot at a time; `bot.bridge.focused` and the `focus` event tell each bot
whether it is the one, `bot.bridge.requestFocus(reason)` asks the player for it, and the HUD
extensions send full detail only while focused. In an extension the same are `ctx.focused`,
`ctx.onFocus(fn)` and `ctx.requestFocus(reason)`.

A hub can also link several players' mods at once. Every bot's declared state goes to every link;
`bot.bridge.players` lists the players, and input handlers know which player triggered them, so a
prompt opened from a command goes to whoever typed it.

```js
const hub = createBridge({
  links: [
    { id: 'alex', endpointFile: '/home/alex/.minecraft/config/mineflayer_bridge/endpoint.json' },
    { id: 'sam', pair: process.env.SAM_PAIRING_URI },
  ],
})
bot.bridge.player('sam')?.run(() => bot.bridge.notify('only Sam sees this'))
```

## Remote bots and pairing

Bots do not have to run on the player's machine. To accept them, the player sets the mod's bind
address to the LAN interface (or `0.0.0.0`) in the mod settings. Off loopback the mod always uses
TLS and accepts only paired devices.

To pair, open *Bridge > Devices > Pair new device* in game. The mod shows a single-use URI, valid for
5 minutes. On the bot machine run:

```sh
npx mineflayer-bridge pair 'mfb://192.168.1.20:25590?fp=RZ6L...&code=K7QM2XWA'
```

or pass the URI as the `pair` option or the `MINEFLAYER_BRIDGE_PAIR` environment variable. The
plugin stores a device credential pinned to the mod's certificate; later runs connect with
`endpoint: 'wss://192.168.1.20:25590'`, with the same `pair` URI left in place, or with
`remoteFallback: true`. `npx mineflayer-bridge devices` lists
stored credentials and `forget` removes one. Treat the pairing URI like a password until it is used,
and the credential file like an SSH key.

**Choosing the endpoint.** A link connects to, in order: the `endpoint` option, the `pair` URI (or
`MINEFLAYER_BRIDGE_PAIR`), `endpointFile`, `MINEFLAYER_BRIDGE_ENDPOINT`, then the local
`<minecraft dir>/config/mineflayer_bridge/endpoint.json`. If none of these is set and the local
file is missing, the attempt fails and is retried until the game starts. Set `remoteFallback: true`
to connect to the most recently paired remote mod instead in that case. The link logs at info level
which endpoint it chose and why. Plain `ws://` is accepted only for a mod on the same machine
(`localhost`, `127.0.0.0/8`, `::1`); any other host is refused with `unauthorized` unless you set
`allowInsecure: true`. Both options can be set on the hub or per entry in `links`.

Both sides prove themselves. The plugin proves it holds the local token or device secret, and the
mod answers with a proof over a nonce the plugin chose, made with the same secret. A peer that
cannot do that (something else listening on the port, or a mod older than the plugin) is refused
with `unauthorized` and the plugin does not reconnect to it.

The player grants permissions per device and can revoke a device at any time. Local programs hold
every permission; remote devices start without some (chat, client samples and queries, shared
writes, urgent picks, controllers). `bot.bridge.can('chat')` checks one, and a request that needs a
missing permission rejects with `permission_denied`.

## Recordings

`recorder({ path })` writes every frame of the connection to a `.mfbrec` file, the same format the
mod's inspector exports. The file is the ASCII magic `MFBREC1` and a line feed, then one record per
frame: a direction byte (`0` from the mod, `1` from Node), a big-endian u64 time in ms since the
epoch, a big-endian u32 length, and the frame bytes exactly as sent. `readRecording(bytes)` parses
one. Recordings leave out the pairing result, which carries the device secret, but still hold the
handshake and everything bots and player sent, so treat them as private.

```js
import { readFileSync } from 'node:fs'
import { bridge, readRecording, recorder } from 'mineflayer-bridge'

bot.loadPlugin(bridge({ extensions: [recorder({ path: './runs/today.mfbrec' })] }))
const frames = readRecording(readFileSync('./runs/today.mfbrec'))   // [{ direction, atMs, bytes }]
```

## API reference

Every option, handle and event is documented in the reference generated from the code. It describes
the latest release, 0.1.0-alpha.10; older versions are not kept.

- [Node plugin](https://pix3lpirat3.github.io/mineflayer-bridge/node/): the `mineflayer-bridge` package.
- [Mod extension API (Java)](https://pix3lpirat3.github.io/mineflayer-bridge/java/api/): for mods that
  extend the bridge with kinds, messages and settings.
- [Client extension API (Java)](https://pix3lpirat3.github.io/mineflayer-bridge/java/client/): for mod
  extensions that add to the game client, `ClientExtension` and `ClientBridge`.

Bugs and questions go to [GitHub issues](https://github.com/Pix3lPirat3/mineflayer-bridge/issues).

## Building from source

Requirements: Java 25, Node 22.12 or newer, pnpm 10.

```sh
pnpm install
pnpm build
pnpm test

cd mod
./gradlew build   # both loaders and all Java tests
```

## License

[PolyForm Noncommercial 1.0.0](LICENSE). You may use, change and share the mod and the plugin for
any noncommercial purpose: personal use, hobby projects, research, study and testing, and use by the
charities, schools and public bodies the license names. Any commercial use needs a separate license
from the author; ask Pix3lPirat3 on Discord.

The mod jar bundles [aircompressor](https://github.com/airlift/aircompressor), which keeps its own
Apache 2.0 license; its license and notice ship in the jar under `META-INF/licenses/aircompressor/`.
Fabric Loader and Fabric API (Apache 2.0), NeoForge (LGPL 2.1) and mineflayer (MIT) are
dependencies, not part of this project, and keep their own licenses.

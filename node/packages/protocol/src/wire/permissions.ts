/** Permission names. Mods may grant names beyond these. */
export type Permission =
  | 'render.world'
  | 'render.hud'
  | 'notify'
  | 'input.pick'
  | 'input.pick.urgent'
  | 'input.keybind'
  | 'input.tool'
  | 'input.intercept'
  | 'commands'
  | 'form'
  | 'ui.container'
  | 'render.display'
  | 'effects.sound'
  | 'effects.particles'
  | 'effects.title'
  | 'settings'
  | 'chat'
  | 'shared.read'
  | 'shared.write'
  | 'client.sample.position'
  | 'client.sample.target'
  | 'client.sample.inventory'
  | 'client.query'
  | 'controller';

/** Every standard permission. */
export const PERMISSIONS: readonly Permission[] = [
  'render.world',
  'render.hud',
  'notify',
  'input.pick',
  'input.pick.urgent',
  'input.keybind',
  'input.tool',
  'input.intercept',
  'commands',
  'form',
  'ui.container',
  'render.display',
  'effects.sound',
  'effects.particles',
  'effects.title',
  'settings',
  'chat',
  'shared.read',
  'shared.write',
  'client.sample.position',
  'client.sample.target',
  'client.sample.inventory',
  'client.query',
  'controller',
];

const OFF_EVERYWHERE: ReadonlySet<Permission> = new Set<Permission>([
  'input.pick.urgent',
  'client.sample.position',
  'client.sample.target',
  'client.sample.inventory',
]);
const OFF_REMOTE: ReadonlySet<Permission> = new Set<Permission>(['chat', 'shared.write', 'client.query', 'controller']);

/**
 * Default grants for a device that authenticated with the local token: every permission,
 * since local devices are trusted and never prompted. Intercept `cancel` mode is on for their bots.
 */
export const LOCAL_DEFAULT_PERMISSIONS: readonly Permission[] = [...PERMISSIONS];

/**
 * Default grants for a remotely paired device: everything except `input.pick.urgent`, the
 * client samples, chat, shared.write, client.query and controller. Intercept `cancel` mode is off for its bots.
 */
export const REMOTE_DEFAULT_PERMISSIONS: readonly Permission[] = PERMISSIONS.filter((p) => !OFF_EVERYWHERE.has(p) && !OFF_REMOTE.has(p));

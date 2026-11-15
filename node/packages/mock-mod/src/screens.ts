import { BridgeError, Messages } from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import { isLive, type Session } from './session.ts';
import type { MockScreen } from './types.ts';

/**
 * Declared screens: which screens the player has open. Several can be
 * open, as a child opened from a parent keeps the parent open.
 */
export class Screens {
  private readonly open = new Set<string>();
  /** Open screens their bot opened, so closing one counts as dismissing it. */
  private readonly byBot = new Set<string>();

  constructor(private readonly core: ModCore) {}

  list(): MockScreen[] {
    return this.core.store.declarations('core:screen', '~screen/').map((d) => ({ ...d, open: this.open.has(d.key) }));
  }

  playerOpen(key: string): void {
    const s = this.core.ownerSession(key, 'core:screen');
    if (this.open.has(key)) return;
    this.open.add(key);
    this.core.gate.asked(s.owner, this.core.clock.now());
    this.core.sendSession(s, 'screen.open', { screen: key });
    this.core.emit('screenOpened', { key, by: 'player' });
  }

  playerClose(key: string): boolean {
    if (!this.open.delete(key)) return false;
    const s = this.owner(key);
    if (s !== undefined && this.byBot.delete(key)) this.core.gate.dismissed(s.owner, this.core.clock.now());
    if (s !== undefined) this.core.sendSession(s, 'screen.close', { screen: key });
    this.core.emit('screenClosed', { key, by: 'player' });
    return true;
  }

  isOpen(key: string): boolean {
    return this.open.has(key);
  }

  /** A bot asks to show one of its screens; a screen it does not own or declare is ignored. */
  onBotOpen(s: Session, key: string): void {
    if (this.open.has(key)) return;
    if (!key.startsWith(s.prefix) || this.core.store.kindAt(key) !== 'core:screen') {
      this.core.sendSessionError(s, 'invalid_message', `no core:screen of this session at ${key}`, undefined);
      return;
    }
    if (s.superseded || !this.core.player.attachState(s).attached) return;
    if (this.core.refuseSurface(s, undefined, Messages['screen.open'].id, 'screen', key)) return;
    this.open.add(key);
    this.byBot.add(key);
    this.core.emit('screenOpened', { key, by: 'bot' });
  }

  onBotClose(s: Session, key: string): void {
    this.byBot.delete(key);
    if (key.startsWith(s.prefix) && this.open.delete(key)) this.core.emit('screenClosed', { key, by: 'bot' });
  }

  private owner(key: string): Session | undefined {
    for (const s of this.core.sessions.values()) if (isLive(s) && key.startsWith(s.prefix)) return s;
    return undefined;
  }
}

/** @throws BridgeError `invalid_message` for an item the screen does not have. */
export function checkScreenItem(core: ModCore, key: string, item: string): void {
  const d = core.store.declarations('core:screen', '~screen/').find((x) => x.key === key);
  const found = d?.data.tabs.some((t) => t.rows.some((r) => r.items.some((i) => i.id === item && i.enabled !== false)));
  if (found !== true) throw new BridgeError('invalid_message', `mock: screen ${key} has no enabled item ${item}`);
}

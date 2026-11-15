import { BridgeError, type MessagePayloads } from '@mineflayer-bridge/protocol';
import { ScopeChat } from './chat.ts';
import type { EffectsApi, WorldMenuOptions } from './display-types.ts';
import { EffectsImpl } from './effects.ts';
import { BossbarImpl, HudPanelImpl } from './hud.ts';
import { ScopeInput, type InputHost } from './input.ts';
import type {
  ActionsApi,
  BossbarHandle,
  BossbarOptions,
  ChatApi,
  CommandsApi,
  CrewApi,
  ConfirmMenuOptions,
  ContainerView,
  ContainerViewOptions,
  FormDefinition,
  FormFieldDef,
  FormButtonDef,
  FormResult,
  FormValues,
  HudPanel,
  HudPanelOptions,
  InputAndHud,
  InterceptsApi,
  KeybindsApi,
  MenuButton,
  MenuFactory,
  MenuOptions,
  PickApi,
  PromptApi,
  Settings,
  SettingsApi,
  SettingsGroupOptions,
  Screen,
  ScreenOptions,
  SettingsSchema,
  ToolsApi,
} from './input-types.ts';
import { MenuImpl } from './menus.ts';
import { ScreenImpl } from './screens.ts';
import type { ScopeObjects } from './scope.ts';
import { SettingsGroup } from './settings.ts';
import type { KeyValueStore } from './types.ts';
import { checkViewId, ContainerViewImpl } from './views.ts';
import { checkKeys, waitFor } from './util.ts';

const SETTINGS_DIR = '~settings/';
const CONFIRM_DEFAULT_TIMEOUT_MS = 30_000;
const CONFIRM_MENU_KEYS: ReadonlySet<string> = new Set(['yes', 'no', 'item', 'default', 'timeoutMs', 'signal']);
const CONFIRM_YES = 11;
const CONFIRM_ITEM = 13;
const CONFIRM_NO = 15;

/**
 * Input and HUD of one scope. Everything is declared through the scope's objects, so it is part of
 * declared state and replayed after reconnects; {@link dispose} drops the routes when the scope
 * unloads (its objects go with the scope's prefix removal).
 */
export class ScopeFeatures implements InputAndHud {
  readonly pick: PickApi;
  readonly prompt: PromptApi;
  readonly commands: CommandsApi;
  readonly orders: CommandsApi;
  readonly crew: CrewApi;
  readonly actions: ActionsApi;
  readonly intercepts: InterceptsApi;
  readonly keybinds: KeybindsApi;
  readonly tools: ToolsApi;
  readonly settings: SettingsApi;
  readonly menu: MenuFactory;
  readonly chat: ChatApi;
  readonly effects: EffectsApi;
  private readonly input: ScopeInput;
  private readonly scopeChat: ScopeChat;
  private readonly panels = new Map<string, HudPanelImpl>();
  private readonly bossbars = new Map<string, BossbarImpl>();
  private readonly groups = new Map<string, SettingsGroup<SettingsSchema>>();
  /** Last values the player sent per group, kept so a redeclared group starts from them. */
  private readonly received = new Map<string, Readonly<Record<string, unknown>>>();
  private readonly views = new Map<string, ContainerViewImpl>();
  private readonly menus = new Map<string, MenuImpl>();
  private readonly screens = new Map<string, ScreenImpl>();
  private confirmSeq = 0;
  private readonly offOpened: () => void;

  /**
   * @param store The scope's key-value store; menus with `persist` keep their pane state in it.
   */
  constructor(
    private readonly host: InputHost,
    private readonly objects: ScopeObjects,
    private readonly signal: AbortSignal,
    private readonly store: KeyValueStore,
  ) {
    this.input = new ScopeInput(host, objects, signal);
    this.pick = this.input.pick;
    this.prompt = this.input.prompt;
    this.commands = this.input.commands;
    this.orders = this.input.orders;
    this.crew = this.input.crew;
    this.actions = this.input.actions;
    this.intercepts = this.input.intercepts;
    this.keybinds = this.input.keybinds;
    this.tools = this.input.tools;
    this.settings = { define: (schema, options) => this.defineSettings(schema, options) };
    this.scopeChat = new ScopeChat(host, objects.scope);
    this.chat = this.scopeChat;
    this.effects = new EffectsImpl(host);
    this.menu = Object.assign((id: string, options: MenuOptions) => this.createMenu(id, options, false), {
      confirm: (title: string, options?: ConfirmMenuOptions) => this.confirmMenu(title, options ?? {}),
    });
    this.offOpened = host.events.on('opened', (fresh) => {
      for (const v of [...this.views.values()]) v.sessionOpened(fresh);
      for (const sc of [...this.screens.values()]) sc.sessionOpened(fresh);
    });
  }

  containerView(id: string, options: ContainerViewOptions): ContainerView {
    checkViewId('container view id', id);
    return this.view(id, options);
  }

  screen(id: string, options: ScreenOptions): Screen {
    const prev = this.screens.get(id);
    if (prev !== undefined) {
      prev.set(options);
      return prev;
    }
    const sc = new ScreenImpl(this.host, this.objects, id, options, (x) => {
      if (this.screens.get(id) === x) this.screens.delete(id);
    });
    this.screens.set(id, sc);
    return sc;
  }

  private view(id: string, options: ContainerViewOptions): ContainerViewImpl {
    const prev = this.views.get(id);
    if (prev !== undefined) {
      prev.redeclare(options);
      return prev;
    }
    const v = new ContainerViewImpl(this.host, this.objects, id, options, (x) => {
      if (this.views.get(id) === x) this.views.delete(id);
    });
    this.views.set(id, v);
    return v;
  }

  private createMenu(id: string, options: MenuOptions, internal: boolean): MenuImpl {
    if (!internal) checkViewId('menu id', id);
    const prev = this.menus.get(id);
    if (prev !== undefined) {
      prev.reconfigure(options);
      return prev;
    }
    const menu = new MenuImpl(
      {
        view: (viewId, o) => this.view(viewId, o),
        forget: (m) => {
          if (this.menus.get(id) === m) this.menus.delete(id);
        },
        items: this.host,
        store: this.store,
        promptText: (text, o) => this.prompt.text(text, o),
        reportError: (e) => this.host.reportError(e),
      },
      id,
      options,
    );
    this.menus.set(id, menu);
    return menu;
  }

  /** A yes/no menu that is removed once answered. */
  private confirmMenu(title: string, o: ConfirmMenuOptions): Promise<boolean> {
    try {
      checkKeys('confirm menu option', o, CONFIRM_MENU_KEYS, { defaultValue: 'use default', yesLabel: 'use yes', noLabel: 'use no' });
    } catch (e) {
      return Promise.reject(e);
    }
    const timeoutMs = o.timeoutMs ?? CONFIRM_DEFAULT_TIMEOUT_MS;
    if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) return Promise.reject(new BridgeError('invalid_message', 'confirm menu timeoutMs must be a positive integer'));
    const signal = o.signal === undefined ? this.signal : AbortSignal.any([o.signal, this.signal]);
    const button = (b: ConfirmMenuOptions['yes'], item: string, label: string): Omit<MenuButton, 'onClick' | 'close'> =>
      b === undefined ? { item, name: label } : typeof b === 'string' ? { item, name: b } : b;
    return waitFor<boolean>(
      signal,
      timeoutMs,
      'confirm menu cancelled',
      (s) => s.ok(o.default ?? false),
      (s) => {
        const menu = this.createMenu(`~confirm-${++this.confirmSeq}`, { title, rows: 3, onClose: () => s.ok(false) }, true);
        menu.slot(CONFIRM_YES, { ...button(o.yes, 'minecraft:lime_stained_glass_pane', 'Yes'), onClick: () => s.ok(true) });
        menu.slot(CONFIRM_NO, { ...button(o.no, 'minecraft:red_stained_glass_pane', 'No'), onClick: () => s.ok(false) });
        if (o.item !== undefined) menu.slot(CONFIRM_ITEM, { item: o.item });
        menu.open();
        return () => menu.remove();
      },
    );
  }

  form<const F extends readonly FormFieldDef[], const B extends readonly FormButtonDef[] = readonly []>(definition: FormDefinition<F, B>): Promise<FormResult<F, B>> {
    return this.input.form(definition);
  }

  worldMenu<const I extends string>(options: WorldMenuOptions<I>): Promise<I> {
    return this.input.worldMenu(options);
  }

  private defineSettings<const S extends SettingsSchema>(schema: S, options: SettingsGroupOptions = {}): Settings<S> {
    const id = options.group ?? 'default';
    if (id === '' || id.includes('/')) throw new BridgeError('invalid_message', `settings group must be a non-empty string without /: ${id}`);
    const rel = `${this.objects.scope}/${SETTINGS_DIR}${id}`;
    const group = new SettingsGroup(rel, schema, options.title);
    const prev = this.groups.get(id);
    prev?.dispose();
    this.groups.set(id, group as unknown as SettingsGroup<SettingsSchema>);
    const last = this.received.get(id);
    if (last !== undefined) group.apply(last);
    this.objects.put(`${SETTINGS_DIR}${id}`, 'core:settings', group.data, { world: 'global' });
    this.host.router.set(rel, {
      accepts: 'settings.values',
      handle: (payload) => {
        const values = (payload as MessagePayloads['settings.values']).values;
        this.received.set(id, values);
        this.groups.get(id)?.apply(values);
      },
    });
    return group;
  }

  hud(panelId: string, options?: HudPanelOptions): HudPanel {
    let panel = this.panels.get(panelId);
    if (panel === undefined) {
      panel = new HudPanelImpl(this.objects, panelId);
      this.panels.set(panelId, panel);
      panel.update(options ?? {});
    } else if (options !== undefined) {
      panel.update(options);
    }
    return panel;
  }

  bossbar(id: string, options: BossbarOptions): BossbarHandle {
    const prev = this.bossbars.get(id);
    if (prev !== undefined) {
      prev.replace(options);
      return prev;
    }
    const bar = new BossbarImpl(this.objects, id, options, (b) => {
      if (this.bossbars.get(id) === b) this.bossbars.delete(id);
    });
    this.bossbars.set(id, bar);
    return bar;
  }

  /** Drops routes and listeners; called after the scope's objects were removed. */
  dispose(): void {
    this.input.dispose();
    this.offOpened();
    this.scopeChat.dispose();
    for (const v of [...this.views.values()]) v.retire();
    this.views.clear();
    this.menus.clear();
    for (const sc of [...this.screens.values()]) sc.retire();
    this.screens.clear();
    for (const [id, g] of this.groups) {
      g.dispose();
      this.host.router.delete(`${this.objects.scope}/${SETTINGS_DIR}${id}`);
    }
    this.groups.clear();
    for (const p of this.panels.values()) p.retire();
    this.panels.clear();
    for (const b of this.bossbars.values()) b.retire();
    this.bossbars.clear();
  }
}

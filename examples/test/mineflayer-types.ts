/// <reference types="mineflayer-bridge/mineflayer" />
// Typechecked, never run: the plugin against the real mineflayer types, as a bot author's project
// sees it. `pnpm typecheck` covers this file.
import type { Bot } from 'mineflayer';
import { bridge, createBridge, selection, windowMirror, type BotBridge, type SelectionApi, type WindowApi } from 'mineflayer-bridge';

/** A real bot takes the plugin, alone or from a hub, and `bot.bridge` is typed. */
export function loads(bot: Bot): BotBridge {
  bot.loadPlugin(bridge());
  bot.loadPlugin(createBridge().plugin({ extensions: [windowMirror(), selection()] }));
  // @ts-expect-error an unknown member of a typed bot.bridge is an error
  bot.bridge.noSuchMember;
  return bot.bridge;
}

/** Extension APIs are inferred from the extension itself. */
export function apis(bot: Bot): [WindowApi | undefined, SelectionApi | undefined] {
  return [bot.bridge.get(windowMirror), bot.bridge.get(selection)];
}

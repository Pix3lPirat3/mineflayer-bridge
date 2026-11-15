// Adds `bot.bridge` to mineflayer's Bot type. Opt in with:
//   /// <reference types="mineflayer-bridge/mineflayer" />
import type { BotBridge } from '../dist/index.js';

declare module 'mineflayer' {
  interface Bot {
    /** Installed by the mineflayer-bridge plugin. */
    bridge: BotBridge;
  }
}

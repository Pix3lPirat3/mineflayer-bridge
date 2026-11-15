import type { KindType, MessageType } from '@mineflayer-bridge/protocol';
import { registerWire } from './ext-wire.ts';
import { ConsoleKinds, ConsoleMessages } from './generated/bridge-console.ts';
import { MapKinds, MapMessages } from './generated/bridge-map.ts';
import { PingKinds, PingMessages } from './generated/bridge-ping.ts';
import { TasksKinds, TasksMessages } from './generated/bridge-tasks.ts';

const kinds: KindType<any>[] = [ConsoleKinds, MapKinds, PingKinds, TasksKinds].flatMap((table) => Object.values(table));
const messages: MessageType<any>[] = [ConsoleMessages, MapMessages, PingMessages, TasksMessages].flatMap((table) => Object.values(table));

// Their codecs are known from the start, so a message of one decodes whenever its extension loads.
registerWire({ kinds, messages });

/**
 * Kind and message names of the first-party extensions that have a mod half (tasks, console, map,
 * ping). Every `hello` offers them, so loading one of these after connecting needs no reconnect.
 */
export const FIRST_PARTY_WIRE: { readonly kinds: readonly string[]; readonly messages: readonly string[] } = {
  kinds: kinds.map((k) => k.name),
  messages: messages.map((m) => m.name),
};

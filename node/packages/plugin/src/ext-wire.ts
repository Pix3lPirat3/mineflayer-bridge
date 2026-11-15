import { BridgeError, WireWriter, type KindType, type MessageType } from '@mineflayer-bridge/protocol';

/**
 * Codecs of extension kinds and messages, by name. Filled by `defineExtension` from the generated
 * `KindType` and `MessageType` entries an extension lists; names are namespaced, so one
 * process-wide table serves every hub, like the core `Kinds` and `Messages` tables. Wire ids are
 * not here: the mod assigns them per connection in `welcome`.
 */
const kinds = new Map<string, KindType<unknown>>();
const messages = new Map<string, MessageType<unknown>>();

/** A kind or message as an extension lists it: a bare name, or a generated entry with its codec. */
export type WireEntry<T> = string | T;

/** Returns the name of a listed kind or message. */
export function wireName(entry: WireEntry<{ readonly name: string }>): string {
  return typeof entry === 'string' ? entry : entry.name;
}

/** Records the codecs an extension lists. A second, different codec for one name is refused. */
export function registerWire(def: { readonly kinds?: readonly WireEntry<KindType<any>>[]; readonly messages?: readonly WireEntry<MessageType<any>>[] }): void {
  for (const k of def.kinds ?? []) if (typeof k !== 'string') put(kinds, k, 'kind');
  for (const m of def.messages ?? []) if (typeof m !== 'string') put(messages, m, 'message');
}

function put<T extends { readonly name: string; readonly codec: unknown }>(table: Map<string, T>, entry: T, what: string): void {
  const prior = table.get(entry.name);
  if (prior !== undefined && prior.codec !== entry.codec) throw new BridgeError('invalid_message', `extension ${what} ${entry.name} is defined twice with different codecs`);
  table.set(entry.name, entry);
}

/** The codec of an extension kind, or `undefined` when no loaded definition lists one. */
export function extensionKind(name: string): KindType<unknown> | undefined {
  return kinds.get(name);
}

/** The codec of an extension message, or `undefined` when no loaded definition lists one. */
export function extensionMessage(name: string): MessageType<unknown> | undefined {
  return messages.get(name);
}

const scratch = new WireWriter(256);

/** Encodes a payload with an extension message's codec. */
export function encodeExtension<T>(type: MessageType<T>, payload: T): Uint8Array {
  scratch.reset();
  type.codec.write(scratch, payload);
  return scratch.toBytes();
}

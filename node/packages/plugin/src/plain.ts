import { BridgeError, type Codec, type KindType, type MessageType } from '@mineflayer-bridge/protocol';

/** One codec for every plain message and kind, so listing a name twice is not a codec clash. */
const PLAIN: Codec<unknown> = {
  write: (w, v) => w.any(v),
  read: (r) => r.any(),
};

const NAME_PATTERN = /^[a-z0-9][a-z0-9_.-]*:[^\s]+$/;
const FROM = new Set(['node', 'mod', 'both']);
const DELIVERY = new Set(['reliable', 'latest', 'unreliable']);

/** Options of {@link plainMessage}. */
export interface PlainMessageOptions {
  /** Which side sends it. */
  from: 'node' | 'mod' | 'both';
  /** Reliable is sequenced and replayed on resume, latest keeps only the newest, unreliable may drop. Default `reliable`. */
  delivery?: 'reliable' | 'latest' | 'unreliable';
}

/**
 * A message type carrying a plain value, for extensions that do not use the bridge's code
 * generator. List it in `defineExtension({ messages })` and use it with `ctx.message(type)`; the mod
 * half registers the same name with `PlainMessages.type`.
 *
 * Values are `null`, booleans, numbers, strings, arrays and plain objects (or `Map`s) with string
 * keys. Integers are sent as MessagePack ints, other numbers as float64; `undefined` object fields
 * are left out and `undefined` itself is sent as `null`. Received objects have no prototype.
 *
 * @param name the message name, `<namespace>:<name>`, starting with the extension's namespace
 * @throws BridgeError `invalid_message` for a bad name, `from` or `delivery`.
 */
export function plainMessage<T = unknown>(name: string, options: PlainMessageOptions): MessageType<T> {
  if (!NAME_PATTERN.test(name)) throw new BridgeError('invalid_message', `message name must be namespace:name, got ${name}`);
  if (!FROM.has(options.from)) throw new BridgeError('invalid_message', `${name}: from must be node, mod or both`);
  const delivery = options.delivery ?? 'reliable';
  if (!DELIVERY.has(delivery)) throw new BridgeError('invalid_message', `${name}: delivery must be reliable, latest or unreliable`);
  return Object.freeze({ id: 0, name, from: options.from, delivery, codec: PLAIN as Codec<T> });
}

/**
 * An object kind whose data is a plain value, for extensions that do not use the bridge's code
 * generator. List it in `defineExtension({ kinds })`; `objects.put(id, kind.name, value)` then
 * encodes the value. The mod half registers the same name as a `KindType` over `WireCodecs.ANY`.
 *
 * Values are as for {@link plainMessage}. An object's data must fit in one frame, so keep it small
 * and split large state over several objects.
 *
 * @param name the kind name, `<namespace>:<name>`, starting with the extension's namespace
 * @throws BridgeError `invalid_message` for a bad name.
 */
export function plainKind<T = unknown>(name: string): KindType<T> {
  if (!NAME_PATTERN.test(name)) throw new BridgeError('invalid_message', `kind name must be namespace:name, got ${name}`);
  return Object.freeze({ id: 0, name, codec: PLAIN as Codec<T> });
}

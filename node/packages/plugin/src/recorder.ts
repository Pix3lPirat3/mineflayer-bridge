import { createWriteStream, mkdirSync, type WriteStream } from 'node:fs';
import { dirname } from 'node:path';
import { BridgeError, Messages, readHeader } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import type { RawFrame } from './types.ts';

/** First bytes of a `.mfbrec` file: ASCII `MFBREC1` and a line feed (README, "Recordings"). */
export const RECORDING_MAGIC: Uint8Array = new TextEncoder().encode('MFBREC1\n');
/** Bytes before each frame: direction (u8), time (u64 BE), length (u32 BE). */
export const RECORD_HEADER_BYTES = 13;
/** Direction byte of a frame the mod sent. */
export const FROM_MOD = 0;
/** Direction byte of a frame the plugin sent. */
export const FROM_NODE = 1;

const PAIR_RESULT = Messages['pair.result'].id;

/** True for a frame that carries a device secret (`pair.result`), which recordings leave out. */
function carriesSecret(bytes: Uint8Array): boolean {
  try {
    return readHeader(bytes).header.type === PAIR_RESULT;
  } catch {
    return false;
  }
}

/** One frame read back from a recording. */
export interface RecordedFrame {
  /** `in`: from the mod (direction byte 0); `out`: from the plugin (1). */
  readonly direction: 'in' | 'out';
  /** When the plugin sent or received it, in ms since the Unix epoch. */
  readonly atMs: number;
  /** The whole frame: envelope header and payload. */
  readonly bytes: Uint8Array;
}

/** Encodes one record of the `.mfbrec` format (README, "Recordings"). */
export function encodeRecord(frame: RawFrame): Uint8Array {
  const out = new Uint8Array(RECORD_HEADER_BYTES + frame.bytes.length);
  const view = new DataView(out.buffer);
  view.setUint8(0, frame.direction === 'in' ? FROM_MOD : FROM_NODE);
  view.setBigUint64(1, BigInt(Math.max(0, Math.floor(frame.atMs))), false);
  view.setUint32(9, frame.bytes.length, false);
  out.set(frame.bytes, RECORD_HEADER_BYTES);
  return out;
}

/**
 * Parses a whole `.mfbrec` recording (README, "Recordings"). Frames are returned in file order,
 * which is the order the plugin saw them.
 *
 * @throws BridgeError `invalid_message` when the magic is wrong, a direction byte is unknown, or
 * the last record is cut short.
 */
export function readRecording(data: Uint8Array): RecordedFrame[] {
  if (data.length < RECORDING_MAGIC.length || RECORDING_MAGIC.some((b, i) => data[i] !== b)) {
    throw new BridgeError('invalid_message', 'not a mineflayer-bridge recording (bad magic)');
  }
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const frames: RecordedFrame[] = [];
  let at = RECORDING_MAGIC.length;
  while (at < data.length) {
    if (data.length - at < RECORD_HEADER_BYTES) throw new BridgeError('invalid_message', `recording truncated in a record header at byte ${at}`);
    const dir = view.getUint8(at);
    if (dir !== FROM_MOD && dir !== FROM_NODE) throw new BridgeError('invalid_message', `unknown direction ${dir} at byte ${at}`);
    const atMs = Number(view.getBigUint64(at + 1, false));
    const length = view.getUint32(at + 9, false);
    const start = at + RECORD_HEADER_BYTES;
    if (data.length - start < length) throw new BridgeError('invalid_message', `recording truncated in a frame at byte ${start}`);
    frames.push({ direction: dir === FROM_MOD ? 'in' : 'out', atMs, bytes: data.slice(start, start + length) });
    at = start + length;
  }
  return frames;
}

/** Public API of `bridge:recorder`. */
export interface RecorderApi {
  /** File being written. */
  readonly path: string;
  /** Frames recorded so far. */
  readonly frames: number;
  /** Bytes written so far, magic and record headers included. */
  readonly bytes: number;
  /** False once stopped, by {@link stop}, by `maxBytes`, by a write error, or by unloading. */
  readonly recording: boolean;
  /** Stops recording and resolves when the file is flushed and closed. Rejects with the write error, if any. */
  stop(): Promise<void>;
}

/**
 * `bridge:recorder`: records every frame of the hub's connection, as the plugin sent and received
 * it, to a `.mfbrec` file (format in the README, "Recordings": magic `MFBREC1\n`, then per frame a
 * u8 direction, u64 big-endian epoch ms, u32 big-endian length and the frame bytes). Frames of
 * every session on the connection are recorded, so load it into one session per hub. Recording
 * starts when it loads and stops when it unloads; frames sent before that are not in the file. The
 * handshake is included (device id, device name, authentication proof); `pair.result`, the one
 * frame that carries a device secret, is left out. Treat recordings as private.
 *
 * Options: `path` (required; parent directories are created; an existing file is replaced) and
 * `maxBytes` (stop once the file would grow past it, in bytes; default unlimited).
 */
export const recorder = defineExtension({
  id: 'bridge:recorder',
  version: '0.1.0',
  options: {
    path: { type: 'string', required: true },
    maxBytes: { type: 'integer', min: 1 },
  },
  setup(ctx, options): RecorderApi {
    const path = options.path;
    if (path === '') throw new BridgeError('invalid_message', 'bridge:recorder: path must not be empty');
    mkdirSync(dirname(path), { recursive: true });
    const stream: WriteStream = createWriteStream(path, { flags: 'w' });
    let frames = 0;
    let bytes = 0;
    let recording = true;
    let failure: Error | undefined;
    let off: () => void = () => undefined;
    const closed = new Promise<void>((resolve) => stream.once('close', () => resolve()));
    stream.on('error', (e) => {
      failure = e;
      ctx.log.error('recording failed', { path, error: e.message });
      finish();
    });
    const write = (chunk: Uint8Array): void => {
      stream.write(chunk);
      bytes += chunk.length;
    };
    const finish = (): void => {
      if (!recording) return;
      recording = false;
      off();
      stream.end();
    };
    write(RECORDING_MAGIC);
    off = ctx.onFrame((frame) => {
      if (!recording || carriesSecret(frame.bytes)) return;
      const record = encodeRecord(frame);
      if (options.maxBytes !== undefined && bytes + record.length > options.maxBytes) {
        ctx.log.warn('recording reached maxBytes; stopped', { path, maxBytes: options.maxBytes });
        finish();
        return;
      }
      write(record);
      frames++;
    });
    ctx.signal.addEventListener('abort', finish, { once: true });
    return {
      path,
      get frames() {
        return frames;
      },
      get bytes() {
        return bytes;
      },
      get recording() {
        return recording;
      },
      async stop() {
        finish();
        await closed;
        if (failure !== undefined) throw new BridgeError('internal', `recording to ${path} failed: ${failure.message}`);
      },
    };
  },
});

// Shared option validators. Each takes the name used in the message first and returns the checked
// value, so a conversion reads `d.scale = finite('scale', o.scale)`. All throw `invalid_message`.
import { BridgeError, type Vec3d } from '@mineflayer-bridge/protocol';
import type { Vec3Like } from './types.ts';
import { toVec3 } from './util.ts';

/** An `invalid_message` error with `message`. */
export function invalid(message: string): BridgeError {
  return new BridgeError('invalid_message', message);
}

export function finite(what: string, v: unknown): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw invalid(`${what} must be a finite number`);
  return v;
}

export function positive(what: string, v: unknown): number {
  if (finite(what, v) <= 0) throw invalid(`${what} must be positive`);
  return v as number;
}

/** A number from 0 to 1. */
export function unit(what: string, v: unknown): number {
  if (!(typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= 1)) throw invalid(`${what} must be between 0 and 1`);
  return v;
}

export function int(what: string, v: unknown): number {
  if (typeof v !== 'number' || !Number.isInteger(v)) throw invalid(`${what} must be an integer`);
  return v;
}

export function nonNegativeInt(what: string, v: unknown, max = 0xffffffff): number {
  if (typeof v !== 'number' || !Number.isInteger(v) || v < 0 || v > max) throw invalid(`${what} must be an integer from 0 to ${max}`);
  return v;
}

export function bool(what: string, v: unknown): boolean {
  if (typeof v !== 'boolean') throw invalid(`${what} must be a boolean`);
  return v;
}

export function text(what: string, v: unknown): string {
  if (typeof v !== 'string') throw invalid(`${what} must be a string`);
  return v;
}

export function nonEmpty(what: string, v: unknown): string {
  if (typeof v !== 'string' || v === '') throw invalid(`${what} must be a non-empty string`);
  return v;
}

/** One path segment of an object key: non-empty and without `/`. */
export function segment(what: string, id: unknown): string {
  if (typeof id !== 'string' || id === '' || id.includes('/')) throw invalid(`${what} must be a non-empty string without /: ${String(id)}`);
  return id;
}

/** A name typed in chat, such as a command: non-empty, without `/` or spaces. */
export function word(what: string, id: unknown): string {
  if (typeof id !== 'string' || id === '' || id.includes('/') || /\s/.test(id)) throw invalid(`${what} must be a non-empty string without / or spaces: ${String(id)}`);
  return id;
}

export function oneOf<T extends string>(what: string, v: T, allowed: ReadonlySet<string>): T {
  if (!allowed.has(v)) throw invalid(`unknown ${what} ${String(v)}; use ${[...allowed].join(', ')}`);
  return v;
}

/** A resource id; a bare name gets the `minecraft:` namespace. */
export function resourceId(what: string, id: unknown): string {
  if (typeof id !== 'string' || id === '' || /\s/.test(id)) throw invalid(`${what} must be a non-empty id without spaces: ${String(id)}`);
  return id.includes(':') ? id : `minecraft:${id}`;
}

/** One number for every axis, or a vector per axis. */
export function spreadVec(what: string, v: number | Vec3Like): Vec3d {
  if (typeof v === 'number') {
    finite(what, v);
    return { x: v, y: v, z: v };
  }
  return toVec3(v, what);
}

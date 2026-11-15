import { BridgeError, type EditableField, type ElementInput, type HoverStyle, type Interactive, type MessagePayloads, type ObjectTraits, type Visibility } from '@mineflayer-bridge/protocol';
import { bool, finite, positive, segment, spreadVec, text } from './check.ts';
import type { DragPhase, ElementEvent, ElementHandler, ElementHandlers, ElementInputName, HoverOptions, InteractiveOptions } from './display-types.ts';
import { guard } from './input.ts';
import type { EditorName, TraitOptions, VisibilityOptions } from './traits-types.ts';
import { toColor } from './util.ts';

const SHIFT = 1;
const CTRL = 2;
const ALT = 4;
const EDITORS: ReadonlySet<string> = new Set<EditorName>(['text', 'anvil', 'gizmo', 'color', 'cycle', 'number', 'toggle']);
/** Directory of action declarations in a scope; trait action ids resolve against it. */
const ACTION_DIR = '~action/';

/** Vanilla key names; bare names such as `q` mean `key.keyboard.q`, as for keybinds. */
export function keyName(k: string): string {
  if (typeof k !== 'string' || k === '') throw new BridgeError('invalid_message', 'key names must be non-empty strings');
  return k.includes('.') ? k : `key.keyboard.${k.toLowerCase()}`;
}

function checkHandler(name: string, fn: unknown): void {
  if (fn !== undefined && typeof fn !== 'function') throw new BridgeError('invalid_message', `the ${name} handler must be a function`);
}

/** The inputs declared to the mod are exactly those a handler exists for. */
function interactiveData(i: InteractiveOptions): Interactive {
  const on: ElementHandlers = i.on ?? {};
  for (const name of ['hover', 'leave', 'primary', 'secondary', 'middle', 'scroll', 'drag'] as const) checkHandler(name, on[name]);
  const inputs: ElementInput[] = [];
  if (on.hover !== undefined) inputs.push('hover_enter');
  if (on.leave !== undefined) inputs.push('hover_leave');
  if (on.primary !== undefined) inputs.push('primary');
  if (on.secondary !== undefined) inputs.push('secondary');
  if (on.middle !== undefined) inputs.push('middle');
  if (on.scroll !== undefined) inputs.push('scroll');
  const keys = new Set((i.keys ?? []).map(keyName));
  if (on.key !== undefined) {
    if (typeof on.key === 'object' && on.key !== null) {
      for (const [k, fn] of Object.entries(on.key)) {
        checkHandler(`key ${k}`, fn);
        keys.add(keyName(k));
      }
    } else {
      checkHandler('key', on.key);
    }
    if (keys.size === 0) throw new BridgeError('invalid_message', 'a key handler needs keys: list them in keys or use a handler per key');
    inputs.push('key');
  }
  if (on.drag !== undefined) inputs.push('drag_start', 'drag', 'drag_end');
  const d: Interactive = { inputs };
  if (i.tooltip !== undefined) d.tooltip = text('tooltip', i.tooltip);
  if (keys.size > 0) d.keys = [...keys];
  if (i.maxDistance !== undefined) d.maxDistance = positive('interaction maxDistance', i.maxDistance);
  if (i.hitbox !== undefined) {
    const h = spreadVec('hitbox', i.hitbox);
    if (h.x <= 0 || h.y <= 0 || h.z <= 0) throw new BridgeError('invalid_message', 'hitbox sizes must be positive');
    d.hitbox = h;
  }
  if (i.tags !== undefined) d.tags = i.tags.map((t) => text('element tag', t));
  return d;
}

function hoverData(o: HoverOptions): HoverStyle {
  const h: HoverStyle = {};
  if (o.scale !== undefined) h.scale = finite('hover scale', o.scale);
  if (o.glow !== undefined) h.glow = toColor(o.glow);
  if (o.text !== undefined) h.text = text('hover text', o.text);
  return h;
}

function editableData(list: TraitOptions['editable']): EditableField[] {
  if (!Array.isArray(list)) throw new BridgeError('invalid_message', 'editable must be a list of field names or { field, editor, label }');
  const seen = new Set<string>();
  return list.map((f) => {
    const e = typeof f === 'string' ? { field: f } : f;
    if (typeof e !== 'object' || e === null || typeof e.field !== 'string' || e.field === '') throw new BridgeError('invalid_message', 'editable entries need a non-empty field name');
    if (seen.has(e.field)) throw new BridgeError('invalid_message', `editable lists ${e.field} twice`);
    seen.add(e.field);
    const out: EditableField = { field: e.field };
    if (e.editor !== undefined) {
      if (!EDITORS.has(e.editor)) throw new BridgeError('invalid_message', `unknown editor ${String(e.editor)}; use ${[...EDITORS].join(', ')}`);
      out.editor = e.editor;
    }
    if (e.label !== undefined) out.label = text('editable label', e.label);
    return out;
  });
}

function visibilityData(v: VisibilityOptions): Visibility {
  if (typeof v !== 'object' || v === null) throw new BridgeError('invalid_message', 'visibleWhen must be an object');
  const d: Visibility = {};
  const distance = (what: string, x: unknown): number => {
    const n = finite(what, x);
    if (n < 0) throw new BridgeError('invalid_message', `${what} must not be negative`);
    return n;
  };
  if (v.minDistance !== undefined) d.minDistance = distance('visibleWhen minDistance', v.minDistance);
  if (v.maxDistance !== undefined) d.maxDistance = distance('visibleWhen maxDistance', v.maxDistance);
  if (d.minDistance !== undefined && d.maxDistance !== undefined && d.minDistance > d.maxDistance) throw new BridgeError('invalid_message', 'visibleWhen minDistance is above maxDistance');
  if (v.focusedOnly !== undefined) d.focusedOnly = bool('visibleWhen focusedOnly', v.focusedOnly);
  if (v.lookedAt !== undefined) d.lookedAt = bool('visibleWhen lookedAt', v.lookedAt);
  return d;
}

/**
 * Encodes trait options as wire traits, or `undefined` when none is set. Action ids become
 * owner-relative keys `<scope>/~action/<id>`; the session adds the owner.
 * @throws BridgeError `invalid_message` for a malformed trait.
 */
export function traitsData(o: TraitOptions, scope: string): ObjectTraits | undefined {
  const t: ObjectTraits = {};
  if (o.interactive != null) t.interactive = interactiveData(o.interactive);
  if (o.hover != null) t.hover = hoverData(o.hover);
  if (o.editable != null) t.editable = editableData(o.editable);
  if (o.actions != null) {
    if (!Array.isArray(o.actions)) throw new BridgeError('invalid_message', 'actions must be a list of action ids');
    t.actions = [...new Set(o.actions.map((a: string) => `${scope}/${ACTION_DIR}${segment('action id', a)}`))];
  }
  if (o.visibleWhen != null) t.visibleWhen = visibilityData(o.visibleWhen);
  return Object.keys(t).length === 0 ? undefined : t;
}

function modifiers(bits: number): { shift: boolean; ctrl: boolean; alt: boolean } {
  return { shift: (bits & SHIFT) !== 0, ctrl: (bits & CTRL) !== 0, alt: (bits & ALT) !== 0 };
}

const DRAG_PHASES: Readonly<Partial<Record<ElementInput, DragPhase>>> = { drag_start: 'start', drag: 'move', drag_end: 'end' };

/** Calls the handler of `on` an `element.event` addresses; handler errors go to `host.reportError`. */
export function dispatchElement(host: { reportError(e: BridgeError): void }, id: string, layer: string, on: ElementHandlers, p: MessagePayloads['element.event']): void {
  const input: ElementInputName =
    p.input === 'hover_enter' ? 'hover' : p.input === 'hover_leave' ? 'leave' : p.input === 'drag_start' || p.input === 'drag_end' ? 'drag' : p.input;
  const event: ElementEvent = {
    id,
    layer,
    input,
    local: p.local === undefined ? undefined : { x: p.local.x, y: p.local.y, z: p.local.z },
    modifiers: modifiers(p.modifiers ?? 0),
    delta: p.delta,
    key: p.key,
    phase: DRAG_PHASES[p.input],
  };
  const call = (fn: ElementHandler | undefined): void => {
    if (fn !== undefined) guard(host, () => fn(event));
  };
  switch (input) {
    case 'hover':
    case 'leave':
    case 'primary':
    case 'secondary':
    case 'middle':
    case 'drag':
      call(on[input]);
      return;
    case 'scroll': {
      const fn = on.scroll;
      if (fn !== undefined) guard(host, () => fn(p.delta ?? 0, event));
      return;
    }
    case 'key': {
      const key = p.key;
      const handler = on.key;
      if (key === undefined || handler === undefined) return;
      if (typeof handler === 'function') guard(host, () => handler(key, event));
      else call(Object.entries(handler).find(([k]) => keyName(k) === key)?.[1]);
      return;
    }
  }
}

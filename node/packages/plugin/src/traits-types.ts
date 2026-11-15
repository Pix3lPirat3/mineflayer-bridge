import type { HoverOptions, InteractiveOptions } from './display-types.ts';

/**
 * Editors the mod opens for an editable field. Without one the mod picks by the field's schema
 * type: text fields get `text`, vectors `gizmo`, colours `color`, enums `cycle`, numbers `number`,
 * booleans `toggle`.
 */
export type EditorName = 'text' | 'anvil' | 'gizmo' | 'color' | 'cycle' | 'number' | 'toggle';

/** One editable field: a data field name of the object's kind, with an optional editor and label. */
export interface EditableFieldOptions {
  /** Wire field name in the kind schema, such as `text`, `pos` or `min`. */
  field: string;
  editor?: EditorName;
  /** Shown in the editor instead of the field name. */
  label?: string;
}

/** When the mod shows an object. Distances are in blocks from the camera. */
export interface VisibilityOptions {
  /** Hidden closer than this. */
  minDistance?: number;
  /** Hidden farther than this. */
  maxDistance?: number;
  /** Shown only while the owning bot is focused. Default false. */
  focusedOnly?: boolean;
  /** Shown only while under the crosshair. Default false. */
  lookedAt?: boolean;
}

/**
 * Traits any declared object can carry, whatever its kind. Every shape, display, group, emitter,
 * retained shape and spatial definition accepts them; they travel in the object's `traits` block,
 * not in its data.
 */
export interface TraitOptions {
  /** Inputs, tooltip and handlers. Element events for the object go to these handlers. */
  interactive?: InteractiveOptions;
  /** Look while hovered. */
  hover?: HoverOptions;
  /**
   * Fields the player may edit with Shift and the primary input: field names, or
   * `{ field, editor, label }`. Names must be data fields of the kind; accepted edits arrive through
   * the handle's `onEdit` and `edited`.
   */
  editable?: readonly (string | EditableFieldOptions)[];
  /** Context action ids registered in the same scope (`actions.register`), offered on this object. */
  actions?: readonly string[];
  visibleWhen?: VisibilityOptions;
}

/**
 * The bot's answer to a player edit: `true` accepts, `false` rejects, a string rejects with that
 * reason (shown to the player).
 */
export type EditVerdict = boolean | string;

/** What changed in a proposed edit. */
export interface EditInfo {
  /** Data field names whose value differs from the declared state. */
  readonly fields: readonly string[];
}

/**
 * Decides on a player edit of a bot-owned object. `proposed` is the full wire data after the edit,
 * `current` the data before it. A thrown error or a rejected promise rejects the edit with the
 * error message and is reported as the session's `error`.
 */
export type EditHandler<D> = (proposed: D, current: D, info: EditInfo) => EditVerdict | Promise<EditVerdict>;

/** Events every layer handle emits. */
export interface HandleEvents<D> {
  /** An accepted player edit was applied; `previous` is the data before it. */
  edited: [data: D, previous: D, info: EditInfo];
  /** The handle stopped controlling its object: removed, replaced, cleared or unloaded. */
  removed: [];
}

/**
 * The handle contract shared by every object a layer declares. After removal the handle is inert:
 * writes are ignored and edits are no longer offered to it.
 */
export interface LayerObjectHandle<D> {
  /** Shape id in the layer. */
  readonly id: string;
  /** Layer name. */
  readonly layer: string;
  /** Object key relative to the owner: `<scope>/<layer>/<id>`. */
  readonly key: string;
  /** False once the handle no longer controls its object. */
  readonly alive: boolean;
  remove(): void;
  /**
   * Sets the handler for player edits, replacing any earlier one. Without a handler an edit is
   * accepted when every changed field is listed in `editable` and the result is valid. Returns a
   * function that clears it.
   */
  onEdit(handler: EditHandler<D>): () => void;
  /** Listens for handle events. Returns a remover; listeners go away with the handle. */
  on<E extends keyof HandleEvents<D>>(event: E, fn: (...args: HandleEvents<D>[E]) => void): () => void;
}

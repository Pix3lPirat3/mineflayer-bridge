import type { BridgeError, KindPayloads, MessagePayloads, ObjectTraits } from '@mineflayer-bridge/protocol';
import type { PlacedKind } from './displays.ts';
import type { ElementHandlers } from './display-types.ts';
import type { EditRegistry, EditTarget } from './edits.ts';
import type { InputRouter, Route } from './input.ts';
import type { RetainedKind } from './retained.ts';
import type { ShapeKind } from './shapes.ts';
import type { SpatialKind } from './spatial-types.ts';
import { dispatchElement } from './traits.ts';
import type { EditHandler, EditInfo, HandleEvents, TraitOptions } from './traits-types.ts';
import { Emitter } from './util.ts';

/** Kinds a layer keeps handles for. */
export type HandleKind = PlacedKind | RetainedKind | SpatialKind | ShapeKind;

/** The kind-independent face of a handle, as the layer keeps them. */
export interface AnyPlaced {
  readonly kind: HandleKind;
  readonly id: string;
  /** Makes the handle inert without touching its object. */
  retire(): void;
  /** Another kind took over the shape id: retire, and drop what only this handle owns. */
  replaced(): void;
  remove(): void;
}

/** What every handle needs from its layer. */
export interface HandleHost {
  /** Layer name. */
  readonly name: string;
  /** Routes `element.event`s to the handles' element handlers. */
  readonly router: InputRouter;
  /** Player edit targets of the session. */
  readonly edits: EditRegistry;
  /** Drops the handle from the layer's table. */
  forget(handle: AnyPlaced): void;
  /** Owner-relative key of a shape in this layer. */
  routeKey(shapeId: string): string;
  reportError(error: BridgeError): void;
  /** Encodes trait options; action ids resolve in the layer's scope. */
  traits(o: TraitOptions): ObjectTraits | undefined;
}

type Data = Record<string, unknown>;

/**
 * Base of every layer handle: shapes, displays, groups, emitters, retained and spatial shapes. It
 * owns the parts of the handle contract every kind shares: element routing from the `interactive`
 * trait, player edits, and the `edited` and `removed` events. A removed, replaced or unloaded
 * handle is inert, so it cannot bring its object back.
 */
export abstract class LayerHandle<K extends HandleKind, H extends HandleHost, D = K extends keyof KindPayloads ? KindPayloads[K] : never> implements AnyPlaced, EditTarget {
  private live = true;
  private route: Route | undefined;
  private elements: ElementHandlers | undefined;
  private handler: EditHandler<D> | undefined;
  private readonly events = new Emitter<HandleEvents<D>>();

  constructor(
    protected readonly host: H,
    readonly id: string,
    readonly kind: K,
  ) {}

  get layer(): string {
    return this.host.name;
  }

  get alive(): boolean {
    return this.live;
  }

  get key(): string {
    return this.host.routeKey(this.id);
  }

  get editHandler(): EditHandler<never> | undefined {
    return this.handler as EditHandler<never> | undefined;
  }

  /** Removes the declared object; runs once, after the handle retired. */
  protected abstract drop(): void;

  /** Releases what the handle holds besides its table entry; runs once, on the first retire. */
  protected retired(): void {}

  /**
   * The write that applies an edit, built from the proposed wire data and the changed fields, or
   * `undefined` when the kind takes no edits. It must validate before returning.
   */
  protected abstract editWrite(proposed: D, fields: readonly string[]): (() => void) | undefined;

  /** Records the trait options in force after a successful write, and registers the routes once. */
  protected bound(o: TraitOptions): void {
    if (!this.live) return;
    this.elements = o.interactive?.on;
    if (this.route !== undefined) return;
    this.route = { accepts: 'element.event', handle: (p) => this.element(p as MessagePayloads['element.event']) };
    this.host.router.set(this.key, this.route);
    this.host.edits.set(this.key, this);
  }

  private element(p: MessagePayloads['element.event']): void {
    if (this.live && this.elements !== undefined) dispatchElement(this.host, this.id, this.layer, this.elements, p);
  }

  prepareEdit(proposed: Data, current: Data, info: EditInfo): (() => void) | undefined {
    const write = this.editWrite(proposed as D, info.fields);
    if (write === undefined) return undefined;
    return () => {
      if (!this.live) return;
      write();
      this.events.emit('edited', proposed as D, current as D, info);
    };
  }

  onEdit(handler: EditHandler<D>): () => void {
    this.handler = handler;
    return () => {
      if (this.handler === handler) this.handler = undefined;
    };
  }

  on<E extends keyof HandleEvents<D>>(event: E, fn: (...args: HandleEvents<D>[E]) => void): () => void {
    return this.events.on(event, fn);
  }

  remove(): void {
    if (!this.live) return;
    this.retire();
    this.drop();
  }

  retire(): void {
    if (!this.live) return;
    this.live = false;
    this.host.forget(this);
    if (this.route !== undefined) {
      this.host.router.delete(this.key, this.route);
      this.host.edits.delete(this.key, this);
      this.route = undefined;
    }
    this.retired();
    this.events.emit('removed');
    this.events.removeAll();
  }

  replaced(): void {
    this.retire();
  }
}

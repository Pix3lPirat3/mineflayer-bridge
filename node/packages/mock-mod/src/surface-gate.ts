interface GateState {
  /** Dismissals in a row. */
  cancels: number;
  lastCancelAt: number;
  /** Clock time until which unasked surfaces are refused. */
  until: number;
  /** Clock time of the player's last use of this owner. */
  askedAt: number;
}

/**
 * Decides whether an owner may put a surface (form, prompt, pick, world menu, screen, container
 * view) in front of the player right now, as the mod does. After the player dismisses one, the
 * owner's unasked surfaces are refused for a while, longer with each dismissal in a row. A surface is
 * asked for when the player ran something of that owner a moment ago; those always pass.
 */
export class SurfaceGate {
  private readonly owners = new Map<string, GateState>();

  /**
   * @param cooldownsMs How long surfaces are refused after the first, second, ... dismissal in a
   *   row; the last entry repeats. Empty switches the gate off.
   * @param resetMs Time without a dismissal after which the count starts over.
   * @param askedWindowMs How long after the player used an owner its surfaces count as asked for.
   */
  constructor(
    private readonly cooldownsMs: readonly number[],
    private readonly resetMs: number,
    private readonly askedWindowMs: number,
  ) {}

  private state(owner: string): GateState {
    let st = this.owners.get(owner);
    if (st === undefined) this.owners.set(owner, (st = { cancels: 0, lastCancelAt: 0, until: 0, askedAt: -Infinity }));
    return st;
  }

  /** The player ran a command, action, button or menu entry of `owner`. */
  asked(owner: string, now: number): void {
    this.state(owner).askedAt = now;
  }

  /** The player dismissed a surface of `owner`. */
  dismissed(owner: string, now: number): void {
    if (this.cooldownsMs.length === 0) return;
    const st = this.state(owner);
    if (st.cancels > 0 && now - st.lastCancelAt >= this.resetMs) st.cancels = 0;
    st.cancels++;
    st.lastCancelAt = now;
    st.until = now + this.cooldownsMs[Math.min(st.cancels, this.cooldownsMs.length) - 1]!;
  }

  /** The player answered a surface of `owner`. */
  completed(owner: string): void {
    const st = this.owners.get(owner);
    if (st === undefined) return;
    st.cancels = 0;
    st.until = 0;
  }

  /** Ms left until `owner` may open an unasked surface, or `undefined` when it may now. */
  refusedFor(owner: string, now: number): number | undefined {
    const st = this.owners.get(owner);
    if (st === undefined || st.cancels === 0 || now >= st.until || now - st.askedAt <= this.askedWindowMs) return undefined;
    return st.until - now;
  }
}

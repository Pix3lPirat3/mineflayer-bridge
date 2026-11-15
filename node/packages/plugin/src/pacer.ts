import type { Budget } from '@mineflayer-bridge/protocol';

/**
 * Token bucket over the mod's budget, with one second of burst. The mod counts a device as a whole,
 * so one pacer serves every session on a connection.
 */
export class Pacer {
  private budget: Budget | undefined;
  private messages = 0;
  private bytes = 0;
  private at = 0;

  /** Starts over with a full bucket for `budget`; `undefined` paces nothing. */
  reset(budget: Budget | undefined): void {
    this.budget = budget;
    this.messages = budget?.messagesPerSecond ?? 0;
    this.bytes = budget?.bytesPerSecond ?? 0;
    this.at = Date.now();
  }

  /** Takes one message of `size` bytes. Returns 0 when it may go now, else the ms to wait. */
  take(size: number): number {
    const b = this.budget;
    if (b === undefined) return 0;
    const now = Date.now();
    const dt = (now - this.at) / 1000;
    this.at = now;
    this.messages = Math.min(b.messagesPerSecond, this.messages + dt * b.messagesPerSecond);
    this.bytes = Math.min(b.bytesPerSecond, this.bytes + dt * b.bytesPerSecond);
    const need = Math.min(size, b.bytesPerSecond);
    if (this.messages >= 1 && this.bytes >= need) {
      this.messages -= 1;
      this.bytes -= need;
      return 0;
    }
    const waitMsg = this.messages >= 1 ? 0 : ((1 - this.messages) / Math.max(1, b.messagesPerSecond)) * 1000;
    const waitBytes = this.bytes >= need ? 0 : ((need - this.bytes) / Math.max(1, b.bytesPerSecond)) * 1000;
    return Math.max(1, Math.ceil(Math.max(waitMsg, waitBytes)));
  }
}

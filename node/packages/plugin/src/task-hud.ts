import { defineExtension } from './extensions.ts';
import { tasks, type TaskSnapshot, type TasksApi } from './tasks.ts';
import { batch, detailGate, WHEN_UNFOCUSED } from './ext-util.ts';
import type { BossbarColor, BossbarHandle, HudAnchorName, HudPanel } from './input-types.ts';

/**
 * `bridge:task-hud`: the tasks of `bridge:tasks` in a HUD panel. It has no API of its own: tasks are
 * started with `bot.bridge.get('bridge:tasks').task(name)`, and `bridge:tasks` is loaded with it
 * when it is not loaded yet. The panel shows the current task (the oldest active top-level one) with
 * its status and a progress bar, the queue of the other top-level tasks and the last result; with
 * `bossbar` the current task also gets a boss bar. A paused task shows `Paused` before its status.
 * Changes are coalesced to at most one redraw per `intervalMs` and wait while the player is not
 * attached. While detail is reduced or the bot is not focused the queue and the last result are left
 * out. It works in bot and controller sessions.
 *
 * The current line, the progress label and the boss bar text are templates over the extension's
 * variables `task` (the current line, `Idle` without a task), `done` and `total`, so progress
 * updates patch only the variables and the bar value.
 *
 * Options: `title` (default `Tasks`), `anchor` (default `top-right`), `intervalMs` (default 100 ms),
 * `maxQueue` (rows of the queue list, default 5), `bossbar` (default false), `bossbarColor`
 * (default `blue`) and `whenUnfocused` (`summary` by default, or `full`).
 */
export const taskHud = defineExtension({
  id: 'bridge:task-hud',
  version: '0.3.0',
  requires: { extensions: [tasks] },
  options: {
    title: { type: 'string', default: 'Tasks' },
    anchor: { type: 'string', default: 'top-right', enum: ['top-left', 'top-center', 'top-right', 'middle-left', 'middle-right', 'bottom-left', 'bottom-right'] },
    intervalMs: { type: 'integer', default: 100, min: 0, max: 60_000 },
    maxQueue: { type: 'integer', default: 5, min: 1, max: 50 },
    bossbar: { type: 'boolean', default: false },
    bossbarColor: { type: 'string', default: 'blue', enum: ['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white'] },
    whenUnfocused: WHEN_UNFOCUSED,
  },
  setup(ctx, options): void {
    const source = ctx.require<TasksApi>('bridge:tasks');
    const panel: HudPanel = ctx.hud('tasks', { title: options.title, anchor: options.anchor as HudAnchorName });
    let last: { text: string; ok: boolean } | undefined;
    let bar: BossbarHandle | undefined;

    const line = (t: TaskSnapshot): string => {
      const status = t.state === 'paused' ? `Paused${t.status === undefined || t.status === '' ? '' : `, ${t.status}`}` : t.status;
      return status === undefined || status === '' ? t.name : `${t.name}: ${status}`;
    };
    const fraction = (t: TaskSnapshot): number | undefined => (t.total === undefined || t.current === undefined ? undefined : t.total === 0 ? 1 : Math.min(1, t.current / t.total));

    const draw = (): void => {
      const full = rich();
      const top = source.tasks.filter((t) => t.parent === undefined);
      const current = top[0];
      const f = current === undefined ? undefined : fraction(current);
      const total = current?.total ?? 0;
      const done = current?.current ?? 0;
      // The texts are fixed templates, so a progress step patches the variables
      // and the bar value, never the text widgets or the boss bar text.
      ctx.vars.setMany({ task: current === undefined ? 'Idle' : line(current), done, total });
      panel.text('current', '{task}', { order: 0 });
      if (current !== undefined && f !== undefined) {
        // A total of 0 counts as complete; the bar's range must not be empty.
        panel.bar('progress', { value: total > 0 ? Math.min(done, total) : 1, min: 0, max: total > 0 ? total : 1, label: '{done}/{total}', color: '#4fc3f7', order: 1 });
      } else if (panel.has('progress')) {
        panel.remove('progress');
      }
      const queue = top.slice(1).map((t) => t.name);
      if (full && queue.length > 0) panel.list('queue', queue, { maxRows: options.maxQueue, order: 2 });
      else if (panel.has('queue')) panel.remove('queue');
      if (full && last !== undefined) panel.badge('last', last.text, last.ok ? '#81c784' : '#e57373', { order: 3 });
      else if (panel.has('last')) panel.remove('last');
      if (!options.bossbar) return;
      if (current === undefined) {
        bar?.remove();
        bar = undefined;
        return;
      }
      const spec = { text: '{task}', progress: f ?? 0, color: options.bossbarColor as BossbarColor };
      if (bar === undefined) bar = ctx.bossbar('task', spec);
      else bar.update(spec);
    };
    const redraw = batch(ctx, options.intervalMs, draw);
    const rich = detailGate(ctx, options.whenUnfocused, () => redraw.request());

    const changed = (): void => redraw.request();
    const offs = [
      source.on('start', changed),
      source.on('update', changed),
      source.on('pause', changed),
      source.on('resume', changed),
      source.on('end', (t, text) => {
        if (t.parent === undefined) {
          const ok = t.state === 'done';
          last = { text: text ?? (ok ? `Done: ${t.name}` : t.state === 'cancelled' ? 'Cancelled' : `Failed: ${t.name}`), ok };
        }
        redraw.request();
      }),
    ];
    ctx.signal.addEventListener('abort', () => offs.forEach((off) => off()), { once: true });

    redraw.request();
  },
});

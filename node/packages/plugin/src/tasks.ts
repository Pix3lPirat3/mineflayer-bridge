import { BridgeError, encodeKind } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { isFiniteNumber } from './ext-util.ts';
import { TasksKinds, TasksMessages, type TaskData, type TaskState } from './generated/bridge-tasks.ts';

/** What a task is doing. */
export type TaskStateName = TaskState;

/** A snapshot of one task of `bridge:tasks`. */
export interface TaskSnapshot {
  readonly id: string;
  readonly name: string;
  readonly state: TaskStateName;
  readonly status: string | undefined;
  readonly current: number | undefined;
  readonly total: number | undefined;
  /** Id of the parent task, for subtasks. */
  readonly parent: string | undefined;
}

/** Options of {@link TasksApi.task} and {@link TaskRef.subtask}. */
export interface TaskOptions {
  /** Units in total; progress is indeterminate without it. */
  total?: number;
  status?: string;
  /** Whether the player may pause it. Default true. */
  pausable?: boolean;
  /** Whether the player may cancel it. Default true. */
  cancellable?: boolean;
  /** Called when the player or the bot pauses the task. */
  onPause?: () => void;
  /** Called when the task resumes. */
  onResume?: () => void;
  /** Called when the task is cancelled; `signal` aborts as well. */
  onCancel?: () => void;
}

/**
 * One task. Methods chain; after the task ended (done, failed or cancelled) they do nothing. A
 * long-running job checks {@link signal} and awaits {@link whenRunning} between steps, so pausing and
 * cancelling from the player's client take effect.
 */
export interface TaskRef {
  readonly id: string;
  readonly name: string;
  readonly state: TaskStateName;
  /** False once the task ended. */
  readonly active: boolean;
  /** Aborts when the task is cancelled. */
  readonly signal: AbortSignal;
  /**
   * Sets progress: `current` of `total` units, both finite and non-negative; without `total` the
   * previous total is kept.
   * @throws BridgeError `invalid_message` for a negative or non-finite value.
   */
  progress(current: number, total?: number): this;
  status(text: string): this;
  pause(): this;
  resume(): this;
  /** Resolves while the task runs, at once or when it resumes; rejects with `cancelled` when it is cancelled. */
  whenRunning(): Promise<void>;
  /** A task under this one, shown nested. */
  subtask(name: string, options?: TaskOptions): TaskRef;
  done(text?: string): void;
  fail(text?: string): void;
  cancel(): void;
}

/** Events of {@link TasksApi.on}. */
export interface TaskEvents {
  start: [task: TaskSnapshot];
  update: [task: TaskSnapshot];
  pause: [task: TaskSnapshot];
  resume: [task: TaskSnapshot];
  /** The task ended; `state` says how: `done`, `failed` or `cancelled`. */
  end: [task: TaskSnapshot, text: string | undefined];
}

/** Public API of `bridge:tasks`, from `bot.bridge.get('bridge:tasks')`. */
export interface TasksApi {
  /**
   * The active top-level task with this name, or a new one.
   * @throws BridgeError `invalid_message` for an empty name.
   */
  task(name: string, options?: TaskOptions): TaskRef;
  /** Active tasks, oldest first. */
  readonly tasks: readonly TaskSnapshot[];
  /** Pauses every pausable task, or resumes them all when `false`. */
  pauseAll(paused?: boolean): void;
  /** Calls `fn` for task events. Returns a remover. */
  on<E extends keyof TaskEvents>(event: E, fn: (...args: TaskEvents[E]) => void): () => void;
  /** Cancels every active task. */
  cancelAll(): void;
}

interface Task {
  id: string;
  name: string;
  state: TaskStateName;
  status: string | undefined;
  current: number | undefined;
  total: number | undefined;
  parent: string | undefined;
  pausable: boolean;
  cancellable: boolean;
  startedAt: number;
  options: TaskOptions;
  abort: AbortController;
  waiters: { resolve: () => void; reject: (e: Error) => void }[];
}

const ENDED: ReadonlySet<TaskStateName> = new Set(['done', 'failed', 'cancelled']);

function checkAmount(what: string, v: number): void {
  if (!isFiniteNumber(v) || v < 0) throw new BridgeError('invalid_message', `task ${what} must be a finite number >= 0, got ${String(v)}`);
}

/**
 * `bridge:tasks`: tasks with progress, subtasks, pause, resume and cancel. Each task is a
 * `bridge:task` object, so a mod with the `bridge:tasks` extension lists every bot's tasks on
 * Bridge home, lets the player pause, resume or cancel them, and pauses all bots with one key; it
 * answers with `bridge:task.control`. Without the mod half the API works the same and the objects
 * are simply not drawn. Ended tasks stay visible for `keepMs` (default 5 s).
 *
 * Methods chain: `task(name).progress(current, total).status(text).done()`. `bridge:task-hud` shows
 * the tasks in a HUD panel, and `bridge:trails` and `bridge:schematic` report their work as tasks
 * when this extension is loaded.
 */
export const tasks = defineExtension({
  id: 'bridge:tasks',
  version: '1.0.0',
  kinds: [TasksKinds['bridge:task']],
  messages: [TasksMessages['bridge:task.control']],
  options: {
    keepMs: { type: 'integer', default: 5000, min: 0, max: 600_000 },
  },
  setup(ctx, options): TasksApi {
    const all = new Map<string, Task>();
    const listeners = new Map<keyof TaskEvents, Set<(...args: never[]) => void>>();
    let next = 1;

    const snapshot = (t: Task): TaskSnapshot => ({ id: t.id, name: t.name, state: t.state, status: t.status, current: t.current, total: t.total, parent: t.parent });
    const emit = <E extends keyof TaskEvents>(event: E, ...args: TaskEvents[E]): void => {
      for (const fn of [...(listeners.get(event) ?? [])]) {
        try {
          (fn as unknown as (...a: TaskEvents[E]) => void)(...args);
        } catch (e) {
          ctx.log.warn(`bridge:tasks ${event} listener failed`, { error: String(e) });
        }
      }
    };
    const write = (t: Task): void => {
      const d: TaskData = { name: t.name, state: t.state, startedAt: t.startedAt };
      if (t.status !== undefined) d.status = t.status;
      if (t.current !== undefined) d.current = t.current;
      if (t.total !== undefined) d.total = t.total;
      if (t.parent !== undefined) d.parent = t.parent;
      if (!t.pausable) d.pausable = false;
      if (!t.cancellable) d.cancellable = false;
      ctx.objects.put(`task/${t.id}`, 'bridge:task', encodeKind(TasksKinds['bridge:task'], d), { world: 'global' });
    };
    const call = (fn: (() => void) | undefined): void => {
      if (fn === undefined) return;
      try {
        fn();
      } catch (e) {
        ctx.log.warn('bridge:tasks handler failed', { error: String(e) });
      }
    };

    const setState = (t: Task, state: TaskStateName): void => {
      if (ENDED.has(t.state) || t.state === state) return;
      t.state = state;
      write(t);
      if (state === 'paused') {
        call(t.options.onPause);
        emit('pause', snapshot(t));
      } else if (state === 'running') {
        call(t.options.onResume);
        for (const w of t.waiters.splice(0)) w.resolve();
        emit('resume', snapshot(t));
      }
    };

    const end = (t: Task, state: 'done' | 'failed' | 'cancelled', text: string | undefined): void => {
      if (ENDED.has(t.state)) return;
      for (const child of all.values()) if (child.parent === t.id) end(child, state === 'done' ? 'done' : 'cancelled', undefined);
      t.state = state;
      if (text !== undefined) t.status = text;
      write(t);
      if (state === 'cancelled') {
        call(t.options.onCancel);
        t.abort.abort(new BridgeError('cancelled', `task ${t.name} was cancelled`));
      }
      for (const w of t.waiters.splice(0)) w.reject(new BridgeError('cancelled', `task ${t.name} ended`));
      emit('end', snapshot(t), text);
      const drop = (): void => {
        if (all.get(t.id) !== t) return;
        all.delete(t.id);
        ctx.objects.remove(`task/${t.id}`);
      };
      if (options.keepMs === 0) drop();
      else {
        const timer = setTimeout(drop, options.keepMs);
        timer.unref?.();
        ctx.signal.addEventListener('abort', () => clearTimeout(timer), { once: true });
      }
    };

    const create = (name: string, o: TaskOptions, parent: string | undefined): TaskRef => {
      if (typeof name !== 'string' || name.trim() === '') throw new BridgeError('invalid_message', 'task name must be a non-empty string');
      if (o.total !== undefined) checkAmount('total', o.total);
      const t: Task = {
        id: String(next++),
        name: name.trim(),
        state: 'running',
        status: o.status,
        current: undefined,
        total: o.total,
        parent,
        pausable: o.pausable ?? true,
        cancellable: o.cancellable ?? true,
        startedAt: Date.now(),
        options: o,
        abort: new AbortController(),
        waiters: [],
      };
      all.set(t.id, t);
      write(t);
      emit('start', snapshot(t));
      return ref(t);
    };

    const ref = (t: Task): TaskRef => {
      const r: TaskRef = {
        id: t.id,
        name: t.name,
        get state() {
          return t.state;
        },
        get active() {
          return !ENDED.has(t.state);
        },
        signal: t.abort.signal,
        progress(current, total) {
          checkAmount('progress', current);
          if (total !== undefined) checkAmount('total', total);
          if (ENDED.has(t.state)) return this;
          t.current = current;
          if (total !== undefined) t.total = total;
          write(t);
          emit('update', snapshot(t));
          return this;
        },
        status(text) {
          if (typeof text !== 'string') throw new BridgeError('invalid_message', 'task status must be a string');
          if (ENDED.has(t.state)) return this;
          t.status = text;
          write(t);
          emit('update', snapshot(t));
          return this;
        },
        pause() {
          setState(t, 'paused');
          return this;
        },
        resume() {
          setState(t, 'running');
          return this;
        },
        whenRunning() {
          if (t.state === 'running') return Promise.resolve();
          if (ENDED.has(t.state)) return Promise.reject(new BridgeError('cancelled', `task ${t.name} ended`));
          return new Promise((resolve, reject) => t.waiters.push({ resolve, reject }));
        },
        subtask: (name, o = {}) => create(name, o, t.id),
        done: (text) => end(t, 'done', text),
        fail: (text) => end(t, 'failed', text),
        cancel: () => end(t, 'cancelled', undefined),
      };
      return r;
    };

    // The player's pause, resume and cancel from the mod half.
    ctx.message(TasksMessages['bridge:task.control']).on(({ task, action }) => {
      const t = all.get(task);
      if (t === undefined) return;
      if (action === 'cancel') {
        if (t.cancellable) end(t, 'cancelled', 'Cancelled by the player');
      } else if (t.pausable) setState(t, action === 'pause' ? 'paused' : 'running');
    });

    ctx.signal.addEventListener('abort', () => {
      for (const t of all.values()) for (const w of t.waiters.splice(0)) w.reject(new BridgeError('cancelled', 'bridge:tasks unloaded'));
    }, { once: true });

    const active = (): Task[] => [...all.values()].filter((t) => !ENDED.has(t.state));
    return {
      task(name, o = {}) {
        const existing = active().find((t) => t.parent === undefined && t.name === name);
        return existing !== undefined ? ref(existing) : create(name, o, undefined);
      },
      get tasks() {
        return active().map(snapshot);
      },
      pauseAll(paused = true) {
        for (const t of active()) if (t.pausable) setState(t, paused ? 'paused' : 'running');
      },
      cancelAll() {
        for (const t of active()) if (t.parent === undefined) end(t, 'cancelled', undefined);
      },
      on(event, fn) {
        let set = listeners.get(event);
        if (set === undefined) listeners.set(event, (set = new Set()));
        set.add(fn as unknown as (...args: never[]) => void);
        return () => {
          set.delete(fn as unknown as (...args: never[]) => void);
        };
      },
    };
  },
});

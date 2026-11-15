import { decodeKind } from '@mineflayer-bridge/protocol';
import { describe, expect, it } from 'vitest';
import { encodeExtension } from '../src/ext-wire.ts';
import { defineExtension, tasks, TasksKinds, TasksMessages, type BotBridge, type TasksApi } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, FakeBot, fixture, load, OVERWORLD, rejection, sleep, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

const KEY = (id: string): string => `${ALICE_PREFIX.replace('/user:default', '')}/bridge:tasks/task/${id}`;

async function start(extensions: unknown[] = [tasks({ keepMs: 50 })]): Promise<{ b: BotBridge; api: TasksApi }> {
  const b = load(hub().plugin({ extensions: extensions as never }), new FakeBot('Alice'));
  e.mod.setPlayerContext(OVERWORLD);
  await until(() => b.state.attached && b.state.worldMatched);
  return { b, api: b.get<TasksApi>('bridge:tasks')! };
}

function task(id: string): unknown {
  const raw = e.mod.object(KEY(id))?.raw;
  return raw === undefined ? undefined : decodeKind(TasksKinds['bridge:task'], raw);
}

function control(id: string, action: 'pause' | 'resume' | 'cancel'): boolean {
  return e.mod.sendExtension(ALICE, 'bridge:task.control', encodeExtension(TasksMessages['bridge:task.control'], { task: id, action }));
}

describe('bridge:tasks', () => {
  it('declares tasks with progress and subtasks as bridge:task objects', async () => {
    const { api } = await start();
    const t = api.task('Mine iron', { total: 10, status: 'Walking' });
    t.progress(3);
    const sub = t.subtask('Dig');
    await until(() => task(sub.id));
    expect(task(t.id)).toMatchObject({ name: 'Mine iron', state: 'running', current: 3, total: 10, status: 'Walking', pausable: true });
    expect(task(sub.id)).toMatchObject({ name: 'Dig', parent: t.id });
    expect(api.task('Mine iron')).toMatchObject({ id: t.id });
    expect(api.tasks.map((x) => x.name)).toEqual(['Mine iron', 'Dig']);
    t.done('Got 10 iron');
    await until(() => (task(t.id) as { state?: string } | undefined)?.state === 'done');
    expect(task(sub.id)).toMatchObject({ state: 'done' });
    expect(api.tasks).toEqual([]);
    await until(() => task(t.id) === undefined, 2000, 'ended task removed after keepMs');
  });

  it('pauses, resumes and cancels from the mod half', async () => {
    const { api } = await start();
    const seen: string[] = [];
    const t = api.task('Farm', { onPause: () => seen.push('pause'), onResume: () => seen.push('resume'), onCancel: () => seen.push('cancel') });
    const locked = api.task('Guard', { pausable: false, cancellable: false });
    await until(() => task(locked.id));
    expect(control(t.id, 'pause')).toBe(true);
    await until(() => t.state === 'paused');
    const waiting = t.whenRunning();
    control(t.id, 'resume');
    await waiting;
    control(locked.id, 'pause');
    control(locked.id, 'cancel');
    control(t.id, 'cancel');
    await until(() => t.state === 'cancelled');
    expect(t.signal.aborted).toBe(true);
    expect(locked.state).toBe('running');
    expect(seen).toEqual(['pause', 'resume', 'cancel']);
    expect((await rejection(t.whenRunning())).code).toBe('cancelled');
  });

  it('pauses all, reports events, and serves extensions that list it as optional', async () => {
    const events: string[] = [];
    let seenApi: TasksApi | undefined;
    const user = defineExtension({ id: 'acme:builder', version: '1.0.0', requires: { optionalExtensions: ['bridge:tasks'] }, setup: (ctx) => (seenApi = ctx.optional<TasksApi>('bridge:tasks')) });
    const { api } = await start([tasks({ keepMs: 0 }), user()]);
    expect(seenApi).toBe(api);
    api.on('start', (t) => events.push(`start ${t.name}`));
    api.on('end', (t, text) => events.push(`end ${t.name} ${t.state} ${text ?? ''}`.trim()));
    const a = api.task('A');
    const b2 = api.task('B');
    api.pauseAll();
    expect([a.state, b2.state]).toEqual(['paused', 'paused']);
    api.pauseAll(false);
    a.fail('stuck');
    api.cancelAll();
    expect(events).toEqual(['start A', 'start B', 'end A failed stuck', 'end B cancelled']);
    await sleep(20);
    expect(e.mod.objects(KEY(''))).toEqual([]);
  });

  it('rejects bad input', async () => {
    const { api } = await start();
    expect(() => api.task(' ')).toThrow(/non-empty/);
    expect(() => api.task('X').progress(-1)).toThrow(/finite/);
  });
});

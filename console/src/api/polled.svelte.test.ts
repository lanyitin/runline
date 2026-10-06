import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import type { Run } from './model';
import { createPolled, type Polled } from './polled.svelte';

let app: TestApp;
let polled: Polled<Run[]> | null = null;
afterEach(async () => {
  polled?.dispose();
  polled = null;
  await app.dispose();
});

async function begin(options: { intervalMs?: number; auto?: boolean } = {}) {
  app = await createTestApp({ identity: { name: 'ada', role: 'developer' } });
  const clock = new ManualClock();
  const visibility = new ManualVisibility();
  polled = createPolled({
    load: () => app.context.api.runs(),
    clock,
    visibility,
    intervalMs: options.intervalMs ?? 3000,
    auto: options.auto,
  });
  const requests = () => app.engine.log.filter((r) => r.path.startsWith('/api/v1/runs')).length;
  return { clock, visibility, polled, requests };
}

describe('a list that is read again and again', () => {
  test('is read at once, then after each interval, and shows what the Engine has now', async () => {
    const t = await begin();
    expect(t.polled.status).toBe('loading');

    await t.polled.start();
    expect(t.polled.status).toBe('ready');
    expect(t.polled.data).toEqual([]);
    expect(t.polled.updatedAt).toBeGreaterThan(0);

    const run = app.engine.backend.seedRun('ada');
    t.clock.advance(2999);
    await t.polled.settled();
    expect(t.polled.data).toEqual([]);
    t.clock.advance(1);
    await t.polled.settled();
    expect(t.polled.data?.map((r) => r.runId)).toEqual([run.runId]);
    expect(t.clock.untilNext).toBe(3000);
  });

  test('is not read while the page is hidden, and is read at once when it is shown', async () => {
    const t = await begin();
    await t.polled.start();

    t.visibility.set(false);
    expect(t.clock.waiting).toBe(0);
    const before = t.requests();
    t.clock.advance(60_000);
    await t.polled.settled();
    expect(t.requests()).toBe(before);

    app.engine.backend.seedRun('ada');
    t.visibility.set(true);
    await t.polled.settled();
    expect(t.polled.data).toHaveLength(1);
    expect(t.clock.untilNext).toBe(3000);
  });

  test('can be switched off and on: off reads nothing by itself, on reads at once', async () => {
    const t = await begin();
    await t.polled.start();

    t.polled.setAuto(false);
    expect(t.polled.auto).toBe(false);
    expect(t.clock.waiting).toBe(0);
    const before = t.requests();
    t.clock.advance(60_000);
    expect(t.requests()).toBe(before);

    app.engine.backend.seedRun('ada');
    t.polled.setAuto(true);
    await t.polled.settled();
    expect(t.polled.data).toHaveLength(1);
    expect(t.clock.untilNext).toBe(3000);
  });

  test('that starts switched off is read once', async () => {
    const t = await begin({ auto: false });
    await t.polled.start();
    expect(t.polled.status).toBe('ready');
    expect(t.clock.waiting).toBe(0);
  });

  test('reload reads now and keeps the pace', async () => {
    const t = await begin();
    await t.polled.start();
    app.engine.backend.seedRun('ada');

    t.polled.reload();
    await t.polled.settled();

    expect(t.polled.data).toHaveLength(1);
    expect(t.clock.untilNext).toBe(3000);
  });

  test('keeps what it shows when a read fails, says that it failed, and tries again after a longer wait each time', async () => {
    const t = await begin({ intervalMs: 1000 });
    app.engine.backend.seedRun('ada');
    await t.polled.start();
    app.engine.faults.push({ match: /GET \/api\/v1\/runs$/, status: 503, times: 3 });

    const waits: Array<number | null> = [];
    for (let i = 0; i < 3; i += 1) {
      t.clock.advance(t.clock.untilNext!);
      await t.polled.settled();
      expect(t.polled.status).toBe('ready');
      expect(t.polled.error).toMatchObject({ status: 503 });
      expect(t.polled.data).toHaveLength(1);
      waits.push(t.clock.untilNext);
    }
    expect(waits).toEqual([1000, 2000, 4000]);

    t.clock.advance(4000);
    await t.polled.settled();
    expect(t.polled.error).toBeNull();
    expect(t.clock.untilNext).toBe(1000);
  });

  test('a first read that fails is a failure with nothing to show, and reload tries again', async () => {
    const t = await begin();
    app.engine.faults.push({ match: /GET \/api\/v1\/runs$/, status: 500, times: 1 });

    await t.polled.start();
    expect(t.polled.status).toBe('failed');
    expect(t.polled.data).toBeNull();
    expect(t.polled.error).toMatchObject({ status: 500 });

    t.polled.reload();
    await t.polled.settled();
    expect(t.polled.status).toBe('ready');
  });

  test('a 401 ends it: the session is over, nothing more is asked', async () => {
    const t = await begin();
    await t.polled.start();
    app.engine.callers = [];

    t.clock.advance(3000);
    await t.polled.settled();

    expect(t.clock.waiting).toBe(0);
    const before = t.requests();
    t.clock.advance(60_000);
    expect(t.requests()).toBe(before);
  });

  test('after dispose it asks nothing', async () => {
    const t = await begin();
    await t.polled.start();
    t.polled.dispose();
    const before = t.requests();
    t.clock.advance(60_000);
    t.visibility.set(false);
    t.visibility.set(true);
    t.polled.reload();
    await t.polled.settled();
    expect(t.requests()).toBe(before);
    expect(t.clock.waiting).toBe(0);
  });
});

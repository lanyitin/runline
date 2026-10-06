import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import { createRunWatch, type RunWatch } from './run-watch.svelte';

let app: TestApp;
let watch: RunWatch | null = null;
afterEach(async () => {
  watch?.dispose();
  watch = null;
  await app.dispose();
});

const ada = { name: 'ada', role: 'developer' as const };
const logRequests = (runId: string) =>
  app.engine.log.filter((r) => r.path.startsWith(`/api/v1/runs/${runId}/log`)).map((r) => r.path);

async function begin(
  options: {
    state?: 'RUNNING' | 'QUEUED' | 'SUCCEEDED' | 'FAILED';
    lines?: string[];
    pageSize?: number;
    hidden?: boolean;
  } = {},
) {
  app = await createTestApp({ identity: ada });
  const run = app.engine.backend.seedRun('ada', { state: options.state ?? 'RUNNING' });
  if (options.lines) app.engine.backend.write(run.runId, ...options.lines);
  const clock = new ManualClock();
  const visibility = new ManualVisibility();
  if (options.hidden) visibility.set(false);
  watch = createRunWatch({
    runId: run.runId,
    api: app.context.api,
    clock,
    visibility,
    pageSize: options.pageSize,
  });
  const lines = () => watch!.entries.map((e) => e.line);
  return {
    run,
    clock,
    visibility,
    watch,
    lines,
    write: (...l: string[]) => app.engine.backend.write(run.runId, ...l),
  };
}

describe('the poll of a run that is going on', () => {
  test('reads the run and its log at once, and then waits one second before it asks again', async () => {
    const t = await begin({ lines: ['a', 'b'] });
    expect(t.watch.phase).toBe('loading');

    await t.watch.start();

    expect(t.watch.run?.state).toBe('RUNNING');
    expect(t.lines()).toEqual(['a', 'b']);
    expect(t.watch.phase).toBe('live');
    expect(t.clock.untilNext).toBe(1000);
  });

  test('asks again only when the second has passed, from the cursor, and adds only what is new', async () => {
    const t = await begin({ lines: ['a', 'b'] });
    await t.watch.start();
    t.write('c', 'd');

    t.clock.advance(999);
    await t.watch.settled();
    expect(t.lines()).toEqual(['a', 'b']);

    t.clock.advance(1);
    await t.watch.settled();
    expect(t.lines()).toEqual(['a', 'b', 'c', 'd']);
    expect(logRequests(t.run.runId).at(-1)).toBe(
      `/api/v1/runs/${t.run.runId}/log?after=2&limit=500`,
    );
    expect(t.watch.entries.map((e) => e.seq)).toEqual([1, 2, 3, 4]);
  });

  test('keeps the stream of each line: standard output and standard error stay apart', async () => {
    const t = await begin();
    app.engine.backend.write(t.run.runId, 'out', { stream: 'STDERR', line: 'err' });
    await t.watch.start();
    expect(t.watch.entries.map((e) => e.stream)).toEqual(['STDOUT', 'STDERR']);
  });

  test('follows the state of the run, and stops when it has ended and the log is read to the end', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();

    t.write('last words');
    app.engine.backend.setState(t.run.runId, 'SUCCEEDED');
    t.clock.advance(1000);
    await t.watch.settled();

    expect(t.watch.run?.state).toBe('SUCCEEDED');
    expect(t.lines()).toEqual(['a', 'last words']);
    expect(t.watch.phase).toBe('done');
    expect(t.clock.waiting).toBe(0);

    const asked = app.engine.requests;
    t.clock.advance(60_000);
    await t.watch.settled();
    expect(app.engine.requests).toBe(asked);
  });

  test('reads on without waiting while a page is full, so that it catches up', async () => {
    const t = await begin({ pageSize: 2, lines: ['1', '2', '3', '4', '5'] });

    await t.watch.start();

    expect(t.lines()).toEqual(['1', '2', '3', '4', '5']);
    expect(logRequests(t.run.runId)).toEqual([
      `/api/v1/runs/${t.run.runId}/log?after=0&limit=2`,
      `/api/v1/runs/${t.run.runId}/log?after=2&limit=2`,
      `/api/v1/runs/${t.run.runId}/log?after=4&limit=2`,
    ]);
    expect(t.clock.untilNext).toBe(1000);
  });

  test('a queued run is waited for as well: nothing is shown, and it is asked again', async () => {
    const t = await begin({ state: 'QUEUED' });
    await t.watch.start();
    expect(t.watch.entries).toEqual([]);
    expect(t.watch.phase).toBe('live');
    expect(t.clock.untilNext).toBe(1000);
  });

  test('a state it does not know is not an end: it goes on asking', async () => {
    const t = await begin();
    // @ts-expect-error a state of a newer Engine
    app.engine.backend.setState(t.run.runId, 'PAUSED');
    await t.watch.start();
    expect(t.watch.phase).toBe('live');
  });
});

describe('a run that has ended', () => {
  test('is read once, the whole log, and no timer is set', async () => {
    const t = await begin({ state: 'FAILED', lines: ['a', 'b', 'c'] });
    await t.watch.start();

    expect(t.lines()).toEqual(['a', 'b', 'c']);
    expect(t.watch.phase).toBe('done');
    expect(t.clock.waiting).toBe(0);
  });

  test('whose log has been cleaned away has no entries, and says it is done', async () => {
    const t = await begin({ state: 'SUCCEEDED', lines: [] });
    await t.watch.start();
    expect(t.watch.entries).toEqual([]);
    expect(t.watch.phase).toBe('done');
  });

  test('is read to the end however long the log is', async () => {
    const lines = Array.from({ length: 1234 }, (_, i) => `line ${i + 1}`);
    const t = await begin({ state: 'SUCCEEDED', lines });
    await t.watch.start();
    expect(t.watch.entries).toHaveLength(1234);
    expect(t.watch.entries.at(-1)!.line).toBe('line 1234');
  });
});

describe('a page that nobody is looking at', () => {
  test('is not polled at all, until it is shown, and then at once', async () => {
    const t = await begin({ hidden: true, lines: ['a'] });

    await t.watch.start();
    expect(t.watch.phase).toBe('paused');
    expect(app.engine.log.filter((r) => r.path.includes(t.run.runId))).toEqual([]);

    t.visibility.set(true);
    await t.watch.settled();
    expect(t.lines()).toEqual(['a']);
    expect(t.watch.phase).toBe('live');
  });

  test('stops asking when it is hidden, and goes on from the cursor when it is shown again', async () => {
    const t = await begin({ lines: ['a', 'b'] });
    await t.watch.start();

    t.visibility.set(false);
    expect(t.watch.phase).toBe('paused');
    expect(t.clock.waiting).toBe(0);
    t.write('c');
    const before = logRequests(t.run.runId).length;
    t.clock.advance(120_000);
    await t.watch.settled();
    expect(logRequests(t.run.runId)).toHaveLength(before);
    expect(t.lines()).toEqual(['a', 'b']);

    t.visibility.set(true);
    await t.watch.settled();
    expect(t.lines()).toEqual(['a', 'b', 'c']);
    expect(logRequests(t.run.runId).at(-1)).toBe(
      `/api/v1/runs/${t.run.runId}/log?after=2&limit=500`,
    );
    expect(t.watch.phase).toBe('live');
    expect(t.clock.untilNext).toBe(1000);
  });

  test('that is hidden and shown again after the run ended reads the end of the log and is done', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    t.visibility.set(false);
    t.write('b');
    app.engine.backend.setState(t.run.runId, 'SUCCEEDED');

    t.visibility.set(true);
    await t.watch.settled();

    expect(t.lines()).toEqual(['a', 'b']);
    expect(t.watch.phase).toBe('done');
  });

  test('being hidden while an answer is on its way does not lose it, and asks no more', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    t.write('b');

    t.clock.advance(1000); // the poll is on its way
    t.visibility.set(false);
    await t.watch.settled();

    expect(t.lines()).toEqual(['a', 'b']);
    expect(t.watch.phase).toBe('paused');
    expect(t.clock.waiting).toBe(0);
  });

  test('being shown twice, or while it is being read, does not ask twice at once', async () => {
    const t = await begin({ hidden: true });
    await t.watch.start();
    t.visibility.set(true);
    t.visibility.set(true);
    await t.watch.settled();
    expect(app.engine.log.filter((r) => r.path === `/api/v1/runs/${t.run.runId}`)).toHaveLength(1);
  });
});

describe('when the Engine fails', () => {
  test('asks again after a longer wait each time (1, 2, 4, 8, 16 seconds, at most 30), and says so', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    app.engine.faults.push({
      match: /\/log/,
      status: 503,
      body: { error: 'shutting_down' },
      times: 6,
    });

    const waits: Array<number | null> = [];
    for (let i = 0; i < 6; i += 1) {
      t.clock.advance(t.clock.untilNext!);
      await t.watch.settled();
      expect(t.watch.phase).toBe('retrying');
      waits.push(t.clock.untilNext);
    }

    expect(waits).toEqual([1000, 2000, 4000, 8000, 16000, 30000]);
    expect(t.watch.retryInMs).toBe(30000);
    expect(t.watch.failures).toBe(6);
    expect(t.watch.problem).toMatchObject({ status: 503 });
    expect(t.lines()).toEqual(['a']);
  });

  test('the first failure is retried after one second', async () => {
    const t = await begin();
    app.engine.faults.push({ match: /\/log/, status: 500, times: 1 });
    await t.watch.start();
    expect(t.watch.phase).toBe('retrying');
    expect(t.clock.untilNext).toBe(1000);
    expect(t.watch.retryInMs).toBe(1000);
  });

  test('recovers: the entries go on from the cursor, the failures are forgotten, and the pace is one second again', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    app.engine.faults.push({ match: /\/log/, status: 500, times: 2 });
    t.write('b');

    t.clock.advance(1000);
    await t.watch.settled();
    t.clock.advance(2000);
    await t.watch.settled();
    expect(t.watch.phase).toBe('retrying');
    t.clock.advance(4000);
    await t.watch.settled();

    expect(t.lines()).toEqual(['a', 'b']);
    expect(t.watch.phase).toBe('live');
    expect(t.watch.failures).toBe(0);
    expect(t.watch.problem).toBeNull();
    expect(t.clock.untilNext).toBe(1000);
  });

  test('an Engine that cannot be reached is retried the same way', async () => {
    const t = await begin();
    app.engine.faults.push({ match: /\/runs\//, status: 0, drop: true, times: 1 });
    await t.watch.start();
    expect(t.watch.phase).toBe('retrying');
    expect(t.watch.problem).toMatchObject({ status: 0 });
  });

  test('a 401 is the end of the session: it stops, and asks nothing more', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    app.engine.callers = [];

    t.clock.advance(1000);
    await t.watch.settled();

    expect(t.watch.phase).toBe('signed-out');
    expect(t.clock.waiting).toBe(0);
    expect(app.session.state).toMatchObject({ status: 'anonymous', reason: 'expired' });
    const asked = app.engine.requests;
    t.clock.advance(60_000);
    expect(app.engine.requests).toBe(asked);
  });

  test('a 404 for the run says it is gone, keeps what was shown, and does not retry', async () => {
    const t = await begin({ lines: ['a', 'b'] });
    await t.watch.start();
    app.engine.backend.purgeRun(t.run.runId);

    t.clock.advance(1000);
    await t.watch.settled();

    expect(t.watch.phase).toBe('gone');
    expect(t.lines()).toEqual(['a', 'b']);
    expect(t.clock.waiting).toBe(0);
  });

  test('a 404 for the log alone says the same', async () => {
    const t = await begin({ lines: ['a'] });
    app.engine.faults.push({
      match: /\/log/,
      status: 404,
      body: { error: 'run_not_found' },
      times: 1,
    });
    await t.watch.start();
    expect(t.watch.phase).toBe('gone');
    expect(t.clock.waiting).toBe(0);
  });

  test('a run that is not there at the start is gone, with nothing shown', async () => {
    app = await createTestApp({ identity: ada });
    watch = createRunWatch({
      runId: '00000000-0000-4000-8000-000000000000',
      api: app.context.api,
      clock: new ManualClock(),
      visibility: new ManualVisibility(),
    });
    await watch.start();
    expect(watch.phase).toBe('gone');
    expect(watch.run).toBeNull();
  });
});

describe('the sequence numbers set the log right', () => {
  test('entries the log gives again are shown once', async () => {
    const t = await begin({ lines: ['a', 'b', 'c'] });
    await t.watch.start();
    const entry = (seq: number, line: string) => ({
      seq,
      at: '2026-10-05T00:00:00Z',
      stream: 'STDOUT',
      line,
    });
    app.engine.faults.push({
      match: /\/log/,
      status: 200,
      body: { entries: [entry(2, 'b'), entry(3, 'c'), entry(4, 'd')], last: 4 },
      times: 1,
    });

    t.clock.advance(1000);
    await t.watch.settled();

    expect(t.watch.entries.map((e) => e.seq)).toEqual([1, 2, 3, 4]);
    expect(t.watch.missing).toBe(0);
  });

  test('a gap is asked for again, and what fills it is shown in order', async () => {
    const t = await begin({ lines: ['a', 'b', 'c', 'd', 'e'] });
    const entry = (seq: number, line: string) => ({
      seq,
      at: '2026-10-05T00:00:00Z',
      stream: 'STDOUT',
      line,
    });
    app.engine.faults.push({
      match: /\/log/,
      status: 200,
      body: { entries: [entry(3, 'c'), entry(4, 'd')], last: 4 },
      times: 1,
    });
    // the first page starts at 3: 1 and 2 are missing, and the Engine is asked again from 0
    await t.watch.start();

    expect(t.watch.entries.map((e) => e.seq)).toEqual([1, 2, 3, 4, 5]);
    expect(t.watch.missing).toBe(0);
  });

  test('a gap that is still there when it is asked again is shown for what it is: lines missing', async () => {
    const t = await begin({ lines: ['a', 'b', 'c'] });
    const entry = (seq: number, line: string) => ({
      seq,
      at: '2026-10-05T00:00:00Z',
      stream: 'STDOUT',
      line,
    });
    const page = { entries: [entry(1, 'a'), entry(4, 'd')], last: 4 };
    app.engine.faults.push({ match: /\/log/, status: 200, body: page, times: 2 });

    await t.watch.start();

    expect(t.watch.entries.map((e) => e.seq)).toEqual([1, 4]);
    expect(t.watch.missing).toBe(2);
  });
});

describe('what the page asks of it', () => {
  test('refresh reads again now, without waiting for the second', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    t.write('b');

    t.watch.refresh();
    await t.watch.settled();

    expect(t.lines()).toEqual(['a', 'b']);
    expect(t.clock.untilNext).toBe(1000);
  });

  test('refresh of a run that has been cancelled shows the end of it', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    app.engine.backend.setState(t.run.runId, 'CANCELLED');

    t.watch.refresh();
    await t.watch.settled();

    expect(t.watch.run?.state).toBe('CANCELLED');
    expect(t.watch.phase).toBe('done');
  });

  test('refresh while it is reading does not read twice at once, and still reads what came meanwhile', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    t.watch.refresh();
    t.watch.refresh();
    t.write('b');
    await t.watch.settled();
    await vi.waitFor(() => expect(t.lines()).toEqual(['a', 'b']));
  });

  test('after dispose it asks nothing, whatever the clock and the page do', async () => {
    const t = await begin({ lines: ['a'] });
    await t.watch.start();
    t.watch.dispose();
    const asked = app.engine.requests;

    t.clock.advance(60_000);
    t.visibility.set(false);
    t.visibility.set(true);
    t.watch.refresh();
    await t.watch.settled();

    expect(app.engine.requests).toBe(asked);
    expect(t.clock.waiting).toBe(0);
  });

  test('an answer that comes after dispose is not shown', async () => {
    const t = await begin({ lines: ['a'] });
    const starting = t.watch.start();
    t.watch.dispose();
    await starting;
    expect(t.watch.entries).toEqual([]);
  });
});

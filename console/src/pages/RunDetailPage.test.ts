import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import RunDetailPage from './RunDetailPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };
const HASH = 'ab12cd34'.padEnd(64, '5');

type Seed = Parameters<TestApp['engine']['backend']['seedRun']>[1];

async function begin(
  options: { run?: Seed; lines?: string[]; languages?: string[]; runId?: string } = {},
) {
  app = await createTestApp({ identity: ada, languages: options.languages });
  app.engine.backend.autoRun = false;
  const run = app.engine.backend.seedRun('ada', {
    state: 'RUNNING',
    contentHash: HASH,
    pipeline: 'order-sync',
    className: 'com.acme.OrderSync',
    parameters: { region: 'eu', batch: '100' },
    createdAt: '2026-10-05T01:00:00Z',
    startedAt: '2026-10-05T01:00:02Z',
    ...options.run,
  });
  if (options.lines) app.engine.backend.write(run.runId, ...options.lines);
  const clock = new ManualClock();
  const visibility = new ManualVisibility();
  const view = app.mount(RunDetailPage, { runId: options.runId ?? run.runId, clock, visibility });
  const ready = () => vi.waitFor(() => expect(view.querySelector('h1')).not.toBeNull());
  const lines = () => [...view.querySelectorAll('.log .line')] as HTMLElement[];
  const settle = async () => {
    // let what the poll asked for come back and be drawn
    await vi.waitFor(() => expect(view.querySelector('.log-status')).not.toBeNull());
  };
  return { run, clock, visibility, view, ready, lines, settle };
}
const fact = (view: HTMLElement, term: string) =>
  [...view.querySelectorAll('dl.facts dt')].find((dt) => dt.textContent === term)
    ?.nextElementSibling;
const status = (view: HTMLElement) => view.querySelector('.log-status')!.textContent!;

describe('the page of a run', () => {
  test('says what the run is: id, state, pipeline and version, class, source, times, parameters', async () => {
    const t = await begin({ run: { state: 'SUCCEEDED', finishedAt: '2026-10-05T01:01:26Z' } });
    await t.ready();

    expect(t.view.querySelector('h1')!.textContent).toContain(t.run.runId.slice(0, 8));
    expect(t.view.querySelector('.head .badge')!.textContent).toContain('SUCCEEDED');
    expect(fact(t.view, 'Run ID')!.textContent).toContain(t.run.runId);
    expect(fact(t.view, 'Pipeline')!.textContent).toBe('order-sync');
    expect(fact(t.view, 'Pipeline')!.querySelector('a')!.getAttribute('href')).toBe(
      `/pipelines/${HASH}?pipeline=order-sync`,
    );
    expect(fact(t.view, 'Version')!.textContent).toContain('ab12cd3');
    expect(fact(t.view, 'Class')!.textContent).toBe('com.acme.OrderSync');
    expect(fact(t.view, 'Source')!.textContent).toContain('MANUAL');
    expect(fact(t.view, 'Source')!.textContent).toContain('ada');
    expect(fact(t.view, 'Created')!.querySelector('time')!.getAttribute('datetime')).toBe(
      '2026-10-05T01:00:00Z',
    );
    expect(fact(t.view, 'Started')!.querySelector('time')!.getAttribute('datetime')).toBe(
      '2026-10-05T01:00:02Z',
    );
    expect(fact(t.view, 'Finished')!.querySelector('time')!.getAttribute('datetime')).toBe(
      '2026-10-05T01:01:26Z',
    );
    expect(fact(t.view, 'Duration')!.textContent).toBe('1m 24s');
    const params = [...t.view.querySelectorAll('.parameters tbody tr')].map((tr) =>
      [...tr.querySelectorAll('th, td')].map((cell) => cell.textContent),
    );
    expect(params).toEqual([
      ['region', 'eu'],
      ['batch', '100'],
    ]);
  });

  test('says that a run has not started or finished, and that it has no parameters', async () => {
    const t = await begin({ run: { state: 'QUEUED', startedAt: null, parameters: {} } });
    await t.ready();
    expect(fact(t.view, 'Started')!.textContent).toBe('Not started yet');
    expect(fact(t.view, 'Finished')!.textContent).toBe('Not finished');
    expect(fact(t.view, 'Duration')!.textContent).toBe('–');
    expect(t.view.querySelector('.parameters')!.textContent).toContain('No parameters.');
  });

  test('leads to running it again, with the same parameters', async () => {
    const t = await begin({ run: { state: 'FAILED', finishedAt: '2026-10-05T01:01:00Z' } });
    await t.ready();
    const link = [...t.view.querySelectorAll('a')].find(
      (a) => a.textContent!.trim() === 'Run again',
    )!;
    expect(link.getAttribute('href')).toBe(
      `/runs/new?contentHash=${HASH}&pipeline=order-sync&param.region=eu&param.batch=100`,
    );
  });

  test('says in the words of the screen that the run is not there, or is not for the caller to see', async () => {
    const t = await begin({ runId: '00000000-0000-4000-8000-000000000000' });
    await vi.waitFor(() => expect(t.view.textContent).toContain('Run not found'));
    expect(t.view.textContent).toContain('cleaned up after the retention period');
    expect(t.view.querySelector('.log')).toBeNull();
  });

  test('puts what the pipeline and the run call themselves in the page as text', async () => {
    const t = await begin({
      run: {
        pipeline: '<img src=x onerror=alert(1)>',
        className: '<svg onload=1>',
        parameters: { '<b>k</b>': '<i>v</i>' },
      },
      lines: ['<script>alert(3)</script>'],
    });
    await t.ready();
    await vi.waitFor(() => expect(t.lines()).toHaveLength(1));
    expect(t.view.querySelector('img, svg, b, i, script')).toBeNull();
    expect(t.view.textContent).toContain('<script>alert(3)</script>');
    expect(t.view.textContent).toContain('<b>k</b>');
  });

  test('follows the language', async () => {
    const t = await begin({ languages: ['zh-TW'], lines: ['a'] });
    await t.ready();
    expect(t.view.textContent).toContain('再執行一次');
    expect(t.view.textContent).toContain('耗時');
  });
});

describe('what the pipeline reported when it failed', () => {
  test('has the type, the message and the stack trace, as text, with the trace to open', async () => {
    const t = await begin({
      run: {
        state: 'FAILED',
        finishedAt: '2026-10-05T01:00:09Z',
        failure: {
          type: 'java.lang.IllegalStateException',
          message: 'bad <b>state</b>',
          trace: 'java.lang.IllegalStateException: bad\n\tat x.Y(Y.kt:1)',
        },
      },
    });
    await t.ready();

    const panel = t.view.querySelector('.failure')!;
    expect(panel.textContent).toContain('java.lang.IllegalStateException');
    expect(panel.textContent).toContain('bad <b>state</b>');
    expect(panel.querySelector('b')).toBeNull();
    const trace = panel.querySelector('details')!;
    expect(trace.hasAttribute('open')).toBe(false);
    expect(trace.querySelector('pre, .plain')!.textContent).toContain('\tat x.Y(Y.kt:1)');
  });

  test('says when the pipeline gave no message', async () => {
    const t = await begin({
      run: { state: 'FAILED', failure: { type: 'java.lang.Boom', message: null, trace: 't' } },
    });
    await t.ready();
    expect(t.view.querySelector('.failure')!.textContent).toContain('(no message)');
  });

  test('is there for a cancelled run that ended with an error too, and not for a run that did not fail', async () => {
    const cancelled = await begin({
      run: {
        state: 'CANCELLED',
        failure: {
          type: 'java.lang.InterruptedException',
          message: 'sleep interrupted',
          trace: 't',
        },
      },
    });
    await cancelled.ready();
    expect(cancelled.view.querySelector('.failure')).not.toBeNull();
    await app.dispose();

    const fine = await begin({ run: { state: 'SUCCEEDED' } });
    await fine.ready();
    expect(fine.view.querySelector('.failure')).toBeNull();
  });
});

test('says who allowed a run of an UNSAFE pipeline, and when', async () => {
  const t = await begin({
    run: { unsafeExecution: { setBy: 'root', setAt: '2026-10-04T23:00:00Z' } },
  });
  await t.ready();
  const box = t.view.querySelector('.unsafe')!;
  expect(box.textContent).toContain('Run with unsafe execution');
  expect(box.textContent).toContain('root');
  expect(box.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-04T23:00:00Z');
});

describe('the log', () => {
  test('is drawn line by line with its sequence number and its time, standard error told from standard output', async () => {
    const t = await begin();
    app.engine.backend.write(t.run.runId, 'out line', { stream: 'STDERR', line: 'err line' });
    await t.ready();
    await vi.waitFor(() => expect(t.lines()).toHaveLength(2));

    expect(t.lines()[0].querySelector('.seq')!.textContent).toBe('1');
    expect(t.lines()[0].querySelector('.text')!.textContent).toBe('out line');
    expect(t.lines()[0].classList.contains('stderr')).toBe(false);
    expect(t.lines()[1].classList.contains('stderr')).toBe(true);
    expect(t.lines()[1].querySelector('.stream')!.textContent).toBe('STDERR');
    expect(t.lines()[0].querySelector('.stream')!.textContent).toBe('STDOUT');
    expect(t.lines()[0].querySelector('time')!.getAttribute('datetime')).toMatch(/^2026|^20/);
  });

  test('gets the lines that come later, while the run goes on, without drawing the first ones again', async () => {
    const t = await begin({ lines: ['one'] });
    await t.ready();
    await vi.waitFor(() => expect(t.lines()).toHaveLength(1));
    const first = t.lines()[0];

    app.engine.backend.write(t.run.runId, 'two', 'three');
    t.clock.advance(1000);

    await vi.waitFor(() => expect(t.lines()).toHaveLength(3));
    expect(t.lines().map((l) => l.querySelector('.text')!.textContent)).toEqual([
      'one',
      'two',
      'three',
    ]);
    expect(t.lines()[0]).toBe(first);
  });

  test('follows the state of the run, and when it ends says so and stops asking', async () => {
    const t = await begin({ lines: ['one'] });
    await t.ready();
    await vi.waitFor(() => expect(status(t.view)).toContain('Following'));

    app.engine.backend.write(t.run.runId, 'last');
    app.engine.backend.setState(t.run.runId, 'SUCCEEDED');
    t.clock.advance(1000);

    await vi.waitFor(() =>
      expect(t.view.querySelector('.head .badge')!.textContent).toContain('SUCCEEDED'),
    );
    await vi.waitFor(() =>
      expect(status(t.view)).toContain('The run has ended and the whole log was read.'),
    );
    expect(t.lines()).toHaveLength(2);
    expect(t.clock.waiting).toBe(0);
    expect(t.view.querySelector('button.cancel')).toBeNull();
  });

  test('says that it is paused when the tab is hidden, and goes on when it is shown', async () => {
    const t = await begin({ lines: ['one'] });
    await t.ready();
    await vi.waitFor(() => expect(status(t.view)).toContain('Following'));

    t.visibility.set(false);
    await vi.waitFor(() => expect(status(t.view)).toContain('Paused: this tab is hidden'));
    app.engine.backend.write(t.run.runId, 'while away');
    t.visibility.set(true);

    await vi.waitFor(() => expect(t.lines()).toHaveLength(2));
    expect(status(t.view)).toContain('Following');
  });

  test('says when the Engine does not answer, how often it failed and when it will try again, and recovers', async () => {
    const t = await begin({ lines: ['one'] });
    await t.ready();
    await vi.waitFor(() => expect(t.lines()).toHaveLength(1));
    app.engine.faults.push({
      match: /\/log/,
      status: 503,
      body: { error: 'shutting_down', message: 'x' },
      times: 2,
    });

    t.clock.advance(1000);
    await vi.waitFor(() =>
      expect(status(t.view)).toContain('1 failed read). Trying again in 1 s.'),
    );
    t.clock.advance(1000);
    await vi.waitFor(() =>
      expect(status(t.view)).toContain('2 failed reads in a row). Trying again in 2 s.'),
    );
    app.engine.backend.write(t.run.runId, 'back');
    t.clock.advance(2000);

    await vi.waitFor(() => expect(t.lines()).toHaveLength(2));
    expect(status(t.view)).toContain('Following');
  });

  test('says the run or its log is gone when it was cleaned up, and keeps what was read', async () => {
    const t = await begin({ lines: ['one', 'two'] });
    await t.ready();
    await vi.waitFor(() => expect(t.lines()).toHaveLength(2));
    app.engine.backend.purgeRun(t.run.runId);

    t.clock.advance(1000);

    await vi.waitFor(() => expect(status(t.view)).toContain('is gone'));
    expect(status(t.view)).toContain('cleaned up after the retention period');
    expect(t.lines()).toHaveLength(2);
  });

  test('says that there is no output yet while the run goes on, and that there is none (or none left) when it ended', async () => {
    const running = await begin();
    await running.ready();
    await vi.waitFor(() =>
      expect(running.view.querySelector('.log .empty')!.textContent).toContain('No output yet.'),
    );
    await app.dispose();

    const ended = await begin({ run: { state: 'SUCCEEDED', finishedAt: '2026-10-05T01:00:09Z' } });
    await ended.ready();
    await vi.waitFor(() =>
      expect(ended.view.querySelector('.log .empty')!.textContent).toContain(
        'removed after the retention period',
      ),
    );
  });

  test('says how many lines are missing when the Engine skipped sequence numbers', async () => {
    const t = await begin();
    app.engine.faults.push({
      match: /\/log/,
      status: 200,
      body: {
        entries: [
          { seq: 1, at: '2026-10-05T01:00:03Z', stream: 'STDOUT', line: 'a' },
          { seq: 4, at: '2026-10-05T01:00:04Z', stream: 'STDOUT', line: 'd' },
        ],
        last: 4,
      },
      times: 2,
    });
    await t.ready();
    await vi.waitFor(() => expect(t.view.querySelector('.missing')).not.toBeNull());
    expect(t.view.querySelector('.missing')!.textContent).toContain('2 lines are missing');
  });

  test('can show one stream only, and the lines with a word in them, and says how many are shown', async () => {
    const t = await begin();
    app.engine.backend.write(
      t.run.runId,
      'alpha',
      { stream: 'STDERR', line: 'beta error' },
      'gamma',
      { stream: 'STDERR', line: 'alpha error' },
    );
    await t.ready();
    await vi.waitFor(() => expect(t.lines()).toHaveLength(4));

    const stream = t.view.querySelector<HTMLSelectElement>('select.stream-filter')!;
    stream.value = 'STDERR';
    stream.dispatchEvent(new Event('change', { bubbles: true }));
    await vi.waitFor(() => expect(t.lines()).toHaveLength(2));
    expect(t.view.querySelector('.matches')!.textContent).toBe('2 of 4 lines shown');

    const search = t.view.querySelector<HTMLInputElement>('input.search')!;
    search.value = 'ALPHA';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    await vi.waitFor(() => expect(t.lines()).toHaveLength(1));
    expect(t.lines()[0].querySelector('.text')!.textContent).toBe('alpha error');
    expect(t.lines()[0].querySelector('.seq')!.textContent).toBe('4');
  });

  test('draws the newest 5000 lines when there are more, and says so', async () => {
    const t = await begin({ run: { state: 'SUCCEEDED', finishedAt: '2026-10-05T01:00:09Z' } });
    app.engine.backend.write(
      t.run.runId,
      ...Array.from({ length: 5100 }, (_, i) => `line ${i + 1}`),
    );
    await t.ready();
    await vi.waitFor(() => expect(t.view.querySelector('.truncated')).not.toBeNull(), {
      timeout: 10_000,
    });
    await vi.waitFor(
      () => expect(t.view.querySelector('.lines')!.textContent).toBe('5,100 lines'),
      { timeout: 10_000 },
    );
    expect(t.lines()).toHaveLength(5000);
    expect(t.lines()[0].querySelector('.seq')!.textContent).toBe('101');
    expect(t.view.querySelector('.truncated')!.textContent).toContain(
      'Only the newest 5,000 of 5,100 lines',
    );
  }, 20_000);

  test('has a follow switch that is on and a download of the log', async () => {
    const t = await begin({ lines: ['a'] });
    await t.ready();
    expect(t.view.querySelector<HTMLInputElement>('input.follow')!.checked).toBe(true);
    expect(t.view.querySelector('button.download')).not.toBeNull();
  });
});

describe('cancelling a run', () => {
  const cancelButton = (view: HTMLElement) =>
    view.querySelector<HTMLButtonElement>('button.cancel')!;
  const confirm = (view: HTMLElement) =>
    view.querySelector<HTMLButtonElement>('button.confirm-cancel')!;

  test('asks before it does, and keeps the run when the answer is no', async () => {
    const t = await begin();
    await t.ready();

    cancelButton(t.view).click();
    await vi.waitFor(() => expect(confirm(t.view)).not.toBeNull());
    expect(t.view.querySelector('.confirm')!.textContent).toContain('Cancel this run?');
    t.view.querySelector<HTMLButtonElement>('button.keep')!.click();

    await vi.waitFor(() => expect(t.view.querySelector('.confirm')).toBeNull());
    expect(app.engine.backend.runs[0].state).toBe('RUNNING');
  });

  test('202: a run that is going on is asked to stop, and the page says that it ends when the pipeline responds', async () => {
    const t = await begin();
    await t.ready();
    cancelButton(t.view).click();
    await vi.waitFor(() => expect(confirm(t.view)).not.toBeNull());

    confirm(t.view).click();

    await vi.waitFor(() =>
      expect(t.view.querySelector('.cancel-result')!.textContent).toContain('Stop requested.'),
    );
    await vi.waitFor(() =>
      expect(t.view.querySelector('.head .badge')!.textContent).toContain('CANCELLED'),
    );
    expect(t.view.querySelector('button.cancel')).toBeNull();
  });

  test('200: a run that had not started is cancelled, and the page says so', async () => {
    const t = await begin({ run: { state: 'QUEUED', startedAt: null } });
    await t.ready();
    cancelButton(t.view).click();
    await vi.waitFor(() => expect(confirm(t.view)).not.toBeNull());

    confirm(t.view).click();

    await vi.waitFor(() =>
      expect(t.view.querySelector('.cancel-result')!.textContent).toContain(
        'had not started: it is CANCELLED',
      ),
    );
    await vi.waitFor(() =>
      expect(t.view.querySelector('.head .badge')!.textContent).toContain('CANCELLED'),
    );
  });

  test('409: a run that ended meanwhile cannot be cancelled, and the page says so and shows how it ended', async () => {
    const t = await begin();
    await t.ready();
    cancelButton(t.view).click();
    await vi.waitFor(() => expect(confirm(t.view)).not.toBeNull());
    app.engine.backend.setState(t.run.runId, 'SUCCEEDED');

    confirm(t.view).click();

    await vi.waitFor(() =>
      expect(t.view.querySelector('.cancel-result [role="alert"]')!.textContent).toContain(
        'finished already',
      ),
    );
    await vi.waitFor(() =>
      expect(t.view.querySelector('.head .badge')!.textContent).toContain('SUCCEEDED'),
    );
  });

  test('404: a run that was removed meanwhile is said as that', async () => {
    const t = await begin();
    await t.ready();
    cancelButton(t.view).click();
    await vi.waitFor(() => expect(confirm(t.view)).not.toBeNull());
    app.engine.backend.purgeRun(t.run.runId);

    confirm(t.view).click();

    await vi.waitFor(() =>
      expect(t.view.querySelector('.cancel-result [role="alert"]')!.textContent).toContain(
        'No such run',
      ),
    );
  });

  test('says that it is cancelling, and cannot be cancelled twice at once', async () => {
    const t = await begin();
    await t.ready();
    cancelButton(t.view).click();
    await vi.waitFor(() => expect(confirm(t.view)).not.toBeNull());
    app.engine.mode = 'hang';

    confirm(t.view).click();

    await vi.waitFor(() => expect(confirm(t.view).textContent).toContain('Cancelling…'));
    expect(confirm(t.view).disabled).toBe(true);
  });

  test('is not offered for a run that has ended', async () => {
    const t = await begin({ run: { state: 'FAILED', finishedAt: '2026-10-05T01:00:09Z' } });
    await t.ready();
    expect(t.view.querySelector('button.cancel')).toBeNull();
  });
});

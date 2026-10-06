import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import type { RunState } from '../../test-support/fake-backend';
import RunsPage from './RunsPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };

const page = async (
  options: {
    seed?: (backend: TestApp['engine']['backend']) => void;
    query?: string;
    languages?: string[];
    identity?: typeof ada | { name: string; role: 'admin' };
  } = {},
) => {
  app = await createTestApp({ identity: options.identity ?? ada, languages: options.languages });
  app.engine.backend.autoRun = false;
  options.seed?.(app.engine.backend);
  app.context.router.navigate(`/runs${options.query ?? ''}`);
  const clock = new ManualClock();
  const visibility = new ManualVisibility();
  const view = app.mount(RunsPage, { clock, visibility });
  return { view, clock, visibility };
};
const rows = (view: HTMLElement) => [...view.querySelectorAll('tbody tr')] as HTMLElement[];
const pipelinesShown = (view: HTMLElement) =>
  rows(view).map((r) => r.querySelector('.pipeline')!.textContent);
const loaded = (view: HTMLElement) =>
  vi.waitFor(() => expect(view.querySelector('table, .rl-empty')).not.toBeNull());
const choose = (select: HTMLSelectElement, value: string) => {
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
};
const filter = (view: HTMLElement, name: string) =>
  [...view.querySelectorAll('select')].find((s) => s.labels?.[0]?.textContent === name)!;
const seedFew = (b: TestApp['engine']['backend']) => {
  b.seedRun('ada', {
    pipeline: 'first',
    state: 'SUCCEEDED',
    createdAt: '2026-10-05T01:00:00Z',
    startedAt: '2026-10-05T01:00:01Z',
    finishedAt: '2026-10-05T01:01:25Z',
  });
  b.seedRun('ada', { pipeline: 'second', state: 'FAILED', createdAt: '2026-10-05T02:00:00Z' });
  b.seedRun('ada', {
    pipeline: 'third',
    state: 'RUNNING',
    createdAt: '2026-10-05T03:00:00Z',
    startedAt: '2026-10-05T03:00:01Z',
  });
};

describe('the runs list', () => {
  test('has each run with its id, pipeline and version, state, source, duration and when it was made', async () => {
    const { view } = await page({ seed: seedFew });
    await loaded(view);

    expect(view.querySelector('h1')!.textContent).toBe('Runs');
    const first = rows(view).find((r) => r.querySelector('.pipeline')!.textContent === 'first')!;
    expect(first.querySelector('.run-id')!.textContent).toMatch(/^[0-9a-f]{8}$/);
    expect(first.querySelector('.hash')!.textContent).toBe('0000000');
    expect(first.querySelector('.badge')!.textContent).toContain('SUCCEEDED');
    expect(first.querySelector('.source')!.textContent).toContain('MANUAL');
    expect(first.querySelector('.source')!.textContent).toContain('ada');
    expect(first.querySelector('.duration')!.textContent).toBe('1m 24s');
    expect(first.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-05T01:00:00Z');
  });

  test('has the newest first, and each run leads to its page', async () => {
    const { view } = await page({ seed: seedFew });
    await loaded(view);
    expect(pipelinesShown(view)).toEqual(['third', 'second', 'first']);
    const href = rows(view)[0].querySelector('a')!.getAttribute('href')!;
    expect(href).toMatch(/^\/runs\/[0-9a-f-]{36}$/);
  });

  test('has no duration for a run that has not started, and a running one counts up to now', async () => {
    const { view } = await page({ seed: seedFew });
    await loaded(view);
    const durations = Object.fromEntries(
      rows(view).map((r) => [
        r.querySelector('.pipeline')!.textContent,
        r.querySelector('.duration')!.textContent,
      ]),
    );
    expect(durations.second).toBe('–');
    expect(durations.third).toMatch(/\d/);
  });

  test.each([
    'QUEUED',
    'WAITING_FOR_RESOURCES',
    'INITIALIZING',
    'RUNNING',
    'TIMED_OUT_UNFINISHED',
    'SUCCEEDED',
    'FAILED',
    'CANCELLED',
    'INTERRUPTED',
    'TIMED_OUT',
  ] satisfies RunState[])('shows the state %s in words', async (state) => {
    const { view } = await page({ seed: (b) => void b.seedRun('ada', { state }) });
    await loaded(view);
    expect(rows(view)[0].querySelector('.badge')!.textContent).toContain(state);
  });

  test('shows a state it does not know as it came', async () => {
    const { view } = await page({
      seed: (b) => {
        const run = b.seedRun('ada');
        // @ts-expect-error a state of a newer Engine
        b.setState(run.runId, 'PAUSED');
      },
    });
    await loaded(view);
    expect(rows(view)[0].querySelector('.badge')!.textContent).toContain('PAUSED');
  });

  test('can be narrowed to a state, and says how many of the runs read are shown', async () => {
    const { view } = await page({ seed: seedFew });
    await loaded(view);

    choose(filter(view, 'State'), 'FAILED');

    await vi.waitFor(() => expect(pipelinesShown(view)).toEqual(['second']));
    expect(view.querySelector('.count')!.textContent).toBe('1 of 3 runs shown');
    expect(location.search).toBe('?state=FAILED');
  });

  test('says that no run is in the state, when none is', async () => {
    const { view } = await page({ seed: seedFew });
    await loaded(view);
    choose(filter(view, 'State'), 'QUEUED');
    await vi.waitFor(() =>
      expect(view.textContent).toContain('No run in this list is in the state you chose.'),
    );
  });

  test('can be narrowed to a pipeline, which the Engine does: it asks for that pipeline, and the address says it', async () => {
    const { view } = await page({ seed: seedFew });
    await loaded(view);

    choose(filter(view, 'Pipeline'), 'second');
    await vi.waitFor(() => expect(pipelinesShown(view)).toEqual(['second']));
    expect(app.engine.log.at(-1)!.path).toBe('/api/v1/runs?pipeline=second');
    expect(location.search).toBe('?pipeline=second');
    // the pipelines to choose from are still all that were seen
    expect([...filter(view, 'Pipeline').options].map((o) => o.value)).toEqual([
      '',
      'first',
      'second',
      'third',
    ]);
  });

  test('can be limited to the newest 100 or 200, and says when the Engine may have more', async () => {
    const { view } = await page({
      seed: (b) => {
        for (let i = 0; i < 60; i += 1) b.seedRun('ada', { pipeline: `p${i}` });
      },
    });
    await loaded(view);
    expect(rows(view)).toHaveLength(50);
    expect(view.textContent).toContain('The newest 50 runs are listed; older ones are not.');

    choose(filter(view, 'Newest'), '100');

    await vi.waitFor(() => expect(rows(view)).toHaveLength(60));
    expect(app.engine.log.at(-1)!.path).toBe('/api/v1/runs?limit=100');
    expect(view.textContent).not.toContain('older ones are not');
  });

  test('starts with the filters of the address', async () => {
    const { view } = await page({
      seed: seedFew,
      query: '?pipeline=second&state=FAILED&limit=100',
    });
    await loaded(view);
    expect(pipelinesShown(view)).toEqual(['second']);
    expect(filter(view, 'State').value).toBe('FAILED');
    expect(filter(view, 'Pipeline').value).toBe('second');
    expect(filter(view, 'Newest').value).toBe('100');
    expect(app.engine.log.at(-1)!.path).toBe('/api/v1/runs?pipeline=second&limit=100');
  });

  test('says there is no run yet, and how to make one', async () => {
    const { view } = await page();
    await loaded(view);
    expect(view.querySelector('.rl-empty')!.textContent).toContain('No runs yet');
    expect(
      [...view.querySelectorAll('a')].some((a) => a.getAttribute('href') === '/runs/new'),
    ).toBe(true);
  });

  test('puts the names that pipelines and sources have in the page as text', async () => {
    const { view } = await page({
      seed: (b) =>
        void b.seedRun('ada', {
          pipeline: '<img src=x onerror=alert(1)>',
          source: { kind: 'TRIGGER', name: '<b>t</b>' },
        }),
    });
    await loaded(view);
    expect(view.querySelector('img, b')).toBeNull();
    expect(view.textContent).toContain('<b>t</b>');
  });

  test('says it is reading, and shows the failure of a first read with the way to try again', async () => {
    app = await createTestApp({ identity: ada });
    app.engine.faults.push({
      match: /GET \/api\/v1\/runs/,
      status: 500,
      body: { error: 'internal_error', message: 'x', errorId: 'e-3' },
      times: 1,
    });
    const view = app.mount(RunsPage, {
      clock: new ManualClock(),
      visibility: new ManualVisibility(),
    });
    expect(view.textContent).toContain('Loading…');
    await vi.waitFor(() =>
      expect(view.querySelector('[role="alert"]')!.textContent).toContain('e-3'),
    );

    view.querySelector<HTMLButtonElement>('button.retry')!.click();
    await vi.waitFor(() => expect(view.querySelector('.rl-empty')).not.toBeNull());
  });

  test('follows the language', async () => {
    const { view } = await page({ seed: seedFew, languages: ['zh-TW'] });
    await loaded(view);
    expect(view.textContent).toContain('建立 run');
    expect(view.querySelector('th')!.textContent).toBe('Run');
    expect(filter(view, '狀態')).toBeTruthy();
  });
});

describe('the list updating by itself', () => {
  test('reads again every 3 seconds, and shows what is new, and how old what is shown is', async () => {
    const { view, clock } = await page({ seed: seedFew });
    await loaded(view);
    expect(view.querySelector('.updated')!.textContent).toMatch(/^Updated /);

    app.engine.backend.seedRun('ada', { pipeline: 'fourth' });
    clock.advance(3000);

    await vi.waitFor(() => expect(pipelinesShown(view)[0]).toBe('fourth'));
  });

  test('sees a run change state', async () => {
    const { view, clock } = await page({ seed: seedFew });
    await loaded(view);
    const running = app.engine.backend.runs.find((r) => r.pipeline === 'third')!;
    app.engine.backend.setState(running.runId, 'SUCCEEDED');

    clock.advance(3000);

    await vi.waitFor(() =>
      expect(
        rows(view)
          .find((r) => r.querySelector('.pipeline')!.textContent === 'third')!
          .querySelector('.badge')!.textContent,
      ).toContain('SUCCEEDED'),
    );
  });

  test('does not read while the tab is hidden, and reads at once when it is shown', async () => {
    const { view, clock, visibility } = await page({ seed: seedFew });
    await loaded(view);
    visibility.set(false);
    const before = app.engine.requests;
    clock.advance(60_000);
    expect(app.engine.requests).toBe(before);

    app.engine.backend.seedRun('ada', { pipeline: 'fifth' });
    visibility.set(true);
    await vi.waitFor(() => expect(pipelinesShown(view)[0]).toBe('fifth'));
  });

  test('can be switched off, and read by hand', async () => {
    const { view, clock } = await page({ seed: seedFew });
    await loaded(view);

    view.querySelector<HTMLInputElement>('input.auto')!.click();
    await vi.waitFor(() => expect(clock.waiting).toBe(0));
    app.engine.backend.seedRun('ada', { pipeline: 'sixth' });
    clock.advance(60_000);
    expect(pipelinesShown(view)[0]).not.toBe('sixth');

    view.querySelector<HTMLButtonElement>('button.refresh')!.click();
    await vi.waitFor(() => expect(pipelinesShown(view)[0]).toBe('sixth'));
  });

  test('keeps the list when a read fails, says so, and shows the failure', async () => {
    const { view, clock } = await page({ seed: seedFew });
    await loaded(view);
    app.engine.faults.push({
      match: /GET \/api\/v1\/runs/,
      status: 503,
      body: { error: 'shutting_down', message: 'x' },
      times: 1,
    });

    clock.advance(3000);

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    expect(view.textContent).toContain(
      'The list could not be refreshed; what is shown was read earlier.',
    );
    expect(rows(view)).toHaveLength(3);
  });
});

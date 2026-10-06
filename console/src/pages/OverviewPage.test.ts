import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import OverviewPage from './OverviewPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };
const now = () => new Date().toISOString();
const yesterday = () => new Date(Date.now() - 36 * 3600 * 1000).toISOString();

const page = async (
  seed: (b: TestApp['engine']['backend']) => void = () => {},
  options: { languages?: string[] } = {},
) => {
  app = await createTestApp({ identity: ada, languages: options.languages });
  app.engine.backend.autoRun = false;
  seed(app.engine.backend);
  const clock = new ManualClock();
  const view = app.mount(OverviewPage, { clock, visibility: new ManualVisibility() });
  const loaded = () =>
    vi.waitFor(() => expect(view.querySelector('.tiles, .rl-empty')).not.toBeNull());
  const tile = (name: string) =>
    [...view.querySelectorAll('.tile')]
      .find((t) => t.querySelector('.label')!.textContent === name)!
      .querySelector('.value')!.textContent;
  return { view, clock, loaded, tile };
};

const mixed = (b: TestApp['engine']['backend']) => {
  b.seedRun('ada', { state: 'RUNNING', createdAt: now(), pipeline: 'live-1' });
  b.seedRun('ada', { state: 'QUEUED', createdAt: now(), pipeline: 'queued-1' });
  b.seedRun('ada', { state: 'FAILED', createdAt: now(), pipeline: 'broken' });
  b.seedRun('ada', { state: 'TIMED_OUT', createdAt: now(), pipeline: 'slow' });
  b.seedRun('ada', { state: 'SUCCEEDED', createdAt: now(), pipeline: 'fine' });
  b.seedRun('ada', { state: 'FAILED', createdAt: yesterday(), pipeline: 'old-broken' });
  b.seedArtifact('ada', [
    { name: 'safe-one', className: 'x.S' },
    { name: 'risky-one', className: 'x.R', reasons: [{ kind: 'JVM_EXIT', member: 'm' }] },
    { name: 'risky-two', className: 'x.R2', reasons: [{ kind: 'JVM_EXIT', member: 'm' }] },
  ]);
};

describe('the overview', () => {
  test('has the numbers that matter: runs today, active now, failed today, UNSAFE pipelines, pipelines', async () => {
    const t = await page(mixed);
    await t.loaded();

    expect(t.view.querySelector('h1')!.textContent).toBe('Overview');
    expect(t.tile('Runs today')).toBe('5');
    expect(t.tile('Active now')).toBe('2');
    expect(t.tile('Failed today')).toBe('2');
    expect(t.tile('UNSAFE pipelines')).toBe('2');
    expect(t.tile('Pipelines')).toBe('3');
  });

  test('says what the numbers are counted over: the newest runs the Engine gives the caller', async () => {
    const t = await page(mixed);
    await t.loaded();
    expect(t.view.querySelector('.scope')!.textContent).toBe(
      'Counted over the newest 6 runs you may see.',
    );
    expect(app.engine.log.some((r) => r.path === '/api/v1/runs?limit=200')).toBe(true);
  });

  test('lists the newest runs, with their state, and where each one leads', async () => {
    const t = await page(mixed);
    await t.loaded();
    const rows = [...t.view.querySelectorAll('tbody tr')];
    expect(rows.map((r) => r.querySelector('.pipeline')!.textContent)).toEqual([
      'old-broken',
      'fine',
      'slow',
      'broken',
      'queued-1',
      'live-1',
    ]);
    expect(rows[1].querySelector('.badge')!.textContent).toContain('SUCCEEDED');
    expect(rows[0].querySelector('a')!.getAttribute('href')).toMatch(/^\/runs\/[0-9a-f-]{36}$/);
  });

  test('shows at most 8 of the recent runs, and leads to all of them', async () => {
    const t = await page((b) => {
      for (let i = 0; i < 12; i += 1) b.seedRun('ada', { pipeline: `p${i}`, createdAt: now() });
    });
    await t.loaded();
    expect(t.view.querySelectorAll('tbody tr')).toHaveLength(8);
    expect([...t.view.querySelectorAll('a')].some((a) => a.getAttribute('href') === '/runs')).toBe(
      true,
    );
  });

  test('says there is nothing yet, and where to start', async () => {
    const t = await page();
    await t.loaded();
    expect(t.view.querySelector('.rl-empty')!.textContent).toContain('Nothing has run yet');
    expect(
      [...t.view.querySelectorAll('a')].some((a) => a.getAttribute('href') === '/upload'),
    ).toBe(true);
  });

  test('is kept up to date: a run that starts is counted', async () => {
    const t = await page();
    await t.loaded();
    app.engine.backend.seedRun('ada', { state: 'RUNNING', createdAt: now() });
    t.clock.advance(3000);
    await vi.waitFor(() => expect(t.tile('Active now')).toBe('1'));
  });

  test('says when it cannot read, and reads again on request', async () => {
    app = await createTestApp({ identity: ada });
    app.engine.faults.push({
      match: /GET \/api\/v1\/runs/,
      status: 500,
      body: { error: 'internal_error', message: 'x', errorId: 'e-8' },
      times: 1,
    });
    const view = app.mount(OverviewPage, {
      clock: new ManualClock(),
      visibility: new ManualVisibility(),
    });
    await vi.waitFor(() =>
      expect(view.querySelector('[role="alert"]')!.textContent).toContain('e-8'),
    );
    view.querySelector<HTMLButtonElement>('button.retry')!.click();
    await vi.waitFor(() => expect(view.querySelector('.rl-empty')).not.toBeNull());
  });

  test('follows the language', async () => {
    const t = await page(mixed, { languages: ['zh-TW'] });
    await t.loaded();
    expect(t.tile('今日 run')).toBe('5');
    expect(t.view.textContent).toContain('最近的 run');
  });
});

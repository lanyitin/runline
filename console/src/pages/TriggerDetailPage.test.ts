import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import TriggerDetailPage from './TriggerDetailPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const HASH = 'c0ffee12'.padEnd(64, '3');

const page = async (
  options: {
    query?: string;
    languages?: string[];
    seed?: (api: TestApp['context']['api'], backend: TestApp['engine']['backend']) => Promise<void>;
  } = {},
) => {
  app = await createTestApp({ identity: root, languages: options.languages });
  app.engine.backend.seedArtifact(
    'ada',
    [
      {
        name: 'demo-slow',
        className: 'samples.slow.SlowPipeline',
        parameters: [
          { name: 'label', required: false, default: 'demo' },
          { name: 'steps', required: false, default: '30' },
        ],
      },
    ],
    { contentHash: HASH },
  );
  app.engine.backend.autoRun = false;
  await options.seed?.(app.context.api, app.engine.backend);
  app.context.router.navigate(`/triggers/detail${options.query ?? '?name=nightly'}`);
  const clock = new ManualClock();
  const visibility = new ManualVisibility();
  const view = app.mount(TriggerDetailPage, { clock, visibility });
  return { view, clock, visibility };
};
const cron = (api: TestApp['context']['api']) =>
  api.createTrigger({
    name: 'nightly',
    kind: 'cron',
    contentHash: HASH,
    pipeline: 'demo-slow',
    cron: '30 9 * * 1-5',
    timeZone: 'Asia/Taipei',
    parameters: { steps: '3', label: '<img src=x onerror=alert(1)>' },
  });
const hook = (api: TestApp['context']['api'], name = 'on.push') =>
  api.createTrigger({ name, kind: 'webhook', contentHash: HASH, pipeline: 'demo-slow' });
const ready = (view: HTMLElement) => vi.waitFor(() => expect(view.querySelector('h1')).not.toBeNull());
const button = (root: ParentNode, label: string) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === label)!;
const section = (view: HTMLElement, title: string) =>
  [...view.querySelectorAll('section')].find((s) => s.querySelector('h2')?.textContent === title)!;
const firing = (backend: TestApp['engine']['backend'], extra: object) =>
  backend.triggers.firings.push({
    triggerName: 'nightly',
    firedAt: '2026-10-05T01:00:00Z',
    scheduledFor: null,
    deliveryId: null,
    outcome: 'run_created',
    reason: null,
    detail: null,
    runId: null,
    ...extra,
  });

describe('the page of a cron trigger', () => {
  test('says what it is: its name, kind, whether it is on, and who made it and changed it', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);

    expect(view.querySelector('h1')!.textContent).toBe('nightly');
    expect(view.querySelector('.rl-page-head .tag')!.textContent).toBe('Cron');
    expect(view.querySelector<HTMLInputElement>('input[role="switch"]')!.checked).toBe(true);
    const facts = view.querySelector('dl.facts')!.textContent!;
    expect(facts).toContain('root');
  });

  test('says what it runs: the version in full, leading to the pipeline, and the parameters it gives and the ones every run gets', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);

    const binding = section(view, 'Runs');
    expect(binding.textContent).toContain('demo-slow');
    expect(binding.textContent).toContain(HASH);
    expect(binding.textContent).toContain('ada');
    expect(binding.querySelector('a')!.getAttribute('href')).toBe(
      `/pipelines/${HASH}?pipeline=demo-slow&uploader=ada`,
    );
    const params = [...section(view, 'Parameters').querySelectorAll('tbody tr')].map((tr) =>
      [...tr.querySelectorAll('td')].map((td) => td.textContent!.trim()),
    );
    expect(params).toEqual([
      ['label', '<img src=x onerror=alert(1)>', '<img src=x onerror=alert(1)>'],
      ['steps', '3', '3'],
    ]);
    expect(view.querySelector('img')).toBeNull();
  });

  test('marks the parameters that are the default of the pipeline, not given', async () => {
    const { view } = await page({
      seed: async (api) => {
        await api.createTrigger({ name: 'nightly', kind: 'cron', contentHash: HASH, pipeline: 'demo-slow', cron: '* * * * *' });
      },
    });
    await ready(view);
    const rows = [...section(view, 'Parameters').querySelectorAll('tbody tr')];
    expect(rows.map((tr) => tr.querySelectorAll('td')[1].textContent!.trim())).toEqual(['', '']);
    expect(rows.map((tr) => tr.querySelector('td:nth-child(3) .value')!.textContent)).toEqual(['demo', '30']);
    expect(rows[0].querySelector('td:nth-child(3)')!.textContent).toContain('default');
  });

  test('says when it runs: the expression, the time zone, and what it means in words', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);

    const schedule = section(view, 'Schedule');
    expect(schedule.querySelector('.cron')!.textContent).toBe('30 9 * * 1-5');
    expect(schedule.textContent).toContain('Asia/Taipei');
    expect(schedule.querySelector('.words')!.textContent).toBe('At 09:30, Monday to Friday');
  });

  test('has no secret to rotate', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);
    expect(button(view, 'Rotate the secret')).toBeUndefined();
  });

  test('has a switch that turns it on or off on the Engine', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);

    view.querySelector<HTMLInputElement>('input[role="switch"]')!.click();

    await vi.waitFor(() => expect(view.querySelector('.state')!.textContent).toBe('Off'));
    expect((await app.context.api.trigger('nightly')).enabled).toBe(false);
  });

  test('is deleted after a confirmation, and then the list is shown', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);

    button(view, 'Delete').click();
    await tick();
    expect(view.querySelector('[role="dialog"] h2')!.textContent).toBe('Delete the trigger nightly?');
    button(view.querySelector('[role="dialog"]')!, 'Delete the trigger').click();

    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers'));
    expect(await app.context.api.triggers()).toEqual([]);
  });

  test('leads to its change and back to the list', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);
    const hrefs = [...view.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/triggers/edit?name=nightly');
    expect(hrefs).toContain('/triggers');
  });
});

describe('the firings of a trigger', () => {
  test('are listed newest first with the outcome in words, when it was due and when it came', async () => {
    const { view } = await page({
      seed: async (api, backend) => {
        await cron(api);
        firing(backend, {
          firedAt: '2026-10-05T01:00:05Z',
          scheduledFor: '2026-10-05T01:00:00Z',
          runId: '11111111-1111-4111-8111-111111111111',
        });
        firing(backend, {
          firedAt: '2026-10-05T02:00:05Z',
          scheduledFor: '2026-10-05T02:00:00Z',
          outcome: 'refused',
          reason: 'unsafe_not_allowed',
          detail: 'pipeline「demo-slow」被判定為 unsafe',
        });
      },
    });
    await ready(view);
    await vi.waitFor(() => expect(section(view, 'Firings').querySelectorAll('tbody tr')).toHaveLength(2));

    const rows = [...section(view, 'Firings').querySelectorAll('tbody tr')] as HTMLElement[];
    expect(rows[0].querySelector('.outcome')!.textContent).toContain('Refused');
    expect(rows[1].querySelector('.outcome')!.textContent).toContain('Run created');
    expect(rows[1].querySelector('time[datetime="2026-10-05T01:00:05Z"]')).not.toBeNull();
    expect(rows[1].querySelector('.due time')!.getAttribute('datetime')).toBe('2026-10-05T01:00:00Z');
  });

  test('says why a firing was refused in words, with the Engine\'s own words apart, as text', async () => {
    const { view } = await page({
      seed: async (api, backend) => {
        await cron(api);
        firing(backend, {
          outcome: 'refused',
          reason: 'unsafe_not_allowed',
          detail: '<b>pipeline</b> is unsafe',
        });
      },
    });
    await ready(view);
    await vi.waitFor(() => expect(section(view, 'Firings').querySelector('tbody tr')).not.toBeNull());

    const row = section(view, 'Firings').querySelector('tbody tr')!;
    expect(row.querySelector('.reason')!.textContent).toContain('UNSAFE');
    expect(row.querySelector('.detail')!.textContent).toContain('<b>pipeline</b> is unsafe');
    expect(row.querySelector('b')).toBeNull();
  });

  test('says a reason it has no words for as the code it came with', async () => {
    const { view } = await page({
      seed: async (api, backend) => {
        await cron(api);
        firing(backend, { outcome: 'failed', reason: 'something_new', detail: 'IOException' });
      },
    });
    await ready(view);
    await vi.waitFor(() => expect(section(view, 'Firings').querySelector('tbody tr')).not.toBeNull());
    expect(section(view, 'Firings').querySelector('.reason')!.textContent).toContain('something_new');
  });

  test('lead to the run they made, and say when the run is gone', async () => {
    const { view } = await page({
      seed: async (api, backend) => {
        await cron(api);
        firing(backend, { firedAt: '2026-10-05T01:00:00Z', runId: '11111111-1111-4111-8111-111111111111' });
        firing(backend, { firedAt: '2026-10-05T02:00:00Z', runId: null });
      },
    });
    await ready(view);
    await vi.waitFor(() => expect(section(view, 'Firings').querySelectorAll('tbody tr')).toHaveLength(2));

    const rows = [...section(view, 'Firings').querySelectorAll('tbody tr')] as HTMLElement[];
    expect(rows[1].querySelector('a.run')!.getAttribute('href')).toBe('/runs/11111111-1111-4111-8111-111111111111');
    expect(rows[0].querySelector('a.run')).toBeNull();
    expect(rows[0].querySelector('.run')!.textContent).toContain('removed');
  });

  test('says there are none yet', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);
    await vi.waitFor(() => expect(section(view, 'Firings').textContent).toContain('Nothing has fired yet'));
  });

  test('are read again while the page is shown, and a new one appears', async () => {
    const { view, clock } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);
    await vi.waitFor(() => expect(section(view, 'Firings').textContent).toContain('Nothing has fired yet'));

    firing(app.engine.backend, { outcome: 'pending' });
    clock.advance(5000);

    await vi.waitFor(() => expect(section(view, 'Firings').querySelectorAll('tbody tr')).toHaveLength(1));
    expect(section(view, 'Firings').querySelector('.outcome')!.textContent).toContain('Pending');
  });

  test('are not read while the page is hidden', async () => {
    const { view, clock, visibility } = await page({ seed: async (api) => void (await cron(api)) });
    await ready(view);
    await vi.waitFor(() => expect(section(view, 'Firings').textContent).toContain('Nothing has fired yet'));

    visibility.set(false);
    const before = app.engine.requests;
    clock.advance(60_000);
    await tick();
    expect(app.engine.requests).toBe(before);
  });
});

describe('the page of a webhook trigger', () => {
  test('says how to call it, with a place for the secret, and that the secret is not shown again', async () => {
    const { view } = await page({ query: '?name=on.push', seed: async (api) => void (await hook(api)) });
    await ready(view);

    const webhook = section(view, 'Webhook');
    expect(webhook.querySelector('.url')!.textContent).toBe(`${location.origin}/api/v1/webhooks/on.push`);
    expect(webhook.querySelector('pre')!.textContent).toContain('X-Runline-Webhook-Secret: <secret>');
    expect(webhook.textContent).toContain('never shown again');
    expect(webhook.querySelector('time')).not.toBeNull();
  });

  test('has no schedule', async () => {
    const { view } = await page({ query: '?name=on.push', seed: async (api) => void (await hook(api)) });
    await ready(view);
    expect(section(view, 'Schedule')).toBeUndefined();
  });

  test('rotates the secret after a confirmation that says the old one stops at once, and shows the new one once, as the first one was shown', async () => {
    const { view } = await page({ query: '?name=on.push', seed: async (api) => void (await hook(api)) });
    await ready(view);
    const first = app.engine.backend.triggers.triggers.get('on.push')!.secretHash;
    const before = (await app.context.api.trigger('on.push')).secretRotatedAt;

    button(view, 'Rotate the secret').click();
    await tick();
    const ask = view.querySelector('[role="dialog"]')!;
    expect(ask.querySelector('h2')!.textContent).toBe('Rotate the secret of on.push?');
    expect(ask.textContent).toContain('old secret stops working at once');
    button(ask, 'Rotate the secret').click();

    await vi.waitFor(() => expect(view.querySelector('.secret')).not.toBeNull());
    const secret = view.querySelector('.secret')!.textContent!;
    expect(view.querySelector('[role="dialog"] h2')!.textContent).toBe('New webhook secret of on.push');
    expect(app.engine.backend.triggers.triggers.get('on.push')!.secretHash).not.toBe(first);
    expect(app.engine.backend.triggers.webhook('on.push', secret, 'd-1').status).toBe(202);
    expect(location.href).not.toContain(secret);
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(secret);

    view.querySelector<HTMLInputElement>('[role="dialog"] input[type="checkbox"]')!.click();
    await tick();
    button(view.querySelector('[role="dialog"]')!, 'Done').click();
    await tick();

    expect(view.querySelector('[role="dialog"]')).toBeNull();
    expect(view.textContent).not.toContain(secret);
    expect(document.body.textContent).not.toContain(secret);
    expect((await app.context.api.trigger('on.push')).secretRotatedAt).not.toBe(before);
  });

  test('does not rotate when the confirmation is refused', async () => {
    const { view } = await page({ query: '?name=on.push', seed: async (api) => void (await hook(api)) });
    await ready(view);
    const first = app.engine.backend.triggers.triggers.get('on.push')!.secretHash;

    button(view, 'Rotate the secret').click();
    await tick();
    button(view.querySelector('[role="dialog"]')!, 'Cancel').click();
    await tick();

    expect(view.querySelector('[role="dialog"]')).toBeNull();
    expect(app.engine.backend.triggers.triggers.get('on.push')!.secretHash).toBe(first);
  });

  test('says what the Engine refused when rotating, inside the confirmation', async () => {
    const { view } = await page({ query: '?name=on.push', seed: async (api) => void (await hook(api)) });
    await ready(view);
    app.engine.faults.push({ match: /rotate-secret/, status: 409, body: { error: 'not_a_webhook', message: 'x' }, times: 1 });

    button(view, 'Rotate the secret').click();
    await tick();
    button(view.querySelector('[role="dialog"]')!, 'Rotate the secret').click();

    await vi.waitFor(() =>
      expect(view.querySelector('[role="dialog"] [role="alert"]')!.textContent).toContain('not a webhook'),
    );
  });
});

describe('a trigger that is not there', () => {
  test('is said in words, with the way back to the list', async () => {
    const { view } = await page({ query: '?name=nobody' });
    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    expect(view.textContent).toContain('No such trigger');
    expect(view.querySelector('a')!.getAttribute('href')).toBe('/triggers');
  });

  test('is said when there is no name in the address', async () => {
    const { view } = await page({ query: '' });
    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
  });
});

describe('in zh-TW', () => {
  test('speaks the language of the screen', async () => {
    const { view } = await page({ languages: ['zh-TW'], seed: async (api) => void (await cron(api)) });
    await ready(view);
    expect(view.querySelector('.words')!.textContent).toBe('09:30，星期一至星期五');
    expect(view.textContent).toContain('觸發紀錄');
  });
});

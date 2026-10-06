import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import TriggersPage from './TriggersPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const HASH = 'c0ffee12'.padEnd(64, '3');

const page = async (
  options: {
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
        parameters: [{ name: 'steps', required: false, default: '30' }],
      },
      { name: 'other', className: 'x.Other' },
    ],
    { contentHash: HASH },
  );
  await options.seed?.(app.context.api, app.engine.backend);
  app.context.router.navigate('/triggers');
  const view = app.mount(TriggersPage, { clock: new ManualClock(), visibility: new ManualVisibility() });
  return { view };
};
const cron = (api: TestApp['context']['api'], name: string, extra: object = {}) =>
  api.createTrigger({
    name,
    kind: 'cron',
    contentHash: HASH,
    pipeline: 'demo-slow',
    cron: '30 9 * * 1-5',
    timeZone: 'Asia/Taipei',
    ...extra,
  });
const hook = (api: TestApp['context']['api'], name: string, extra: object = {}) =>
  api.createTrigger({ name, kind: 'webhook', contentHash: HASH, pipeline: 'demo-slow', ...extra });
const rows = (view: HTMLElement) => [...view.querySelectorAll('tbody tr')] as HTMLElement[];
const loaded = (view: HTMLElement) =>
  vi.waitFor(() => expect(view.querySelector('table, .rl-empty')).not.toBeNull());
const button = (root: ParentNode, label: string) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === label)!;

describe('the triggers list', () => {
  test('has each trigger: its name, kind, what it runs and in which version, and who changed it last', async () => {
    const { view } = await page({
      seed: async (api) => {
        await cron(api, 'nightly');
        await hook(api, 'on-push');
      },
    });
    await loaded(view);

    expect(view.querySelector('h1')!.textContent).toBe('Triggers');
    expect(rows(view).map((r) => r.querySelector('.name')!.textContent!.trim())).toEqual(['nightly', 'on-push']);
    const nightly = rows(view)[0];
    expect(nightly.querySelector('.kind')!.textContent).toContain('Cron');
    expect(nightly.querySelector('.target')!.textContent).toContain('demo-slow');
    expect(nightly.querySelector('.target .hash')!.textContent).toBe('c0ffee1');
    expect(nightly.querySelector('.updated')!.textContent).toContain('root');
    expect(rows(view)[1].querySelector('.kind')!.textContent).toContain('Webhook');
  });

  test('says when a cron trigger runs: the expression, its time zone and what it means in words; a webhook is called from outside', async () => {
    const { view } = await page({
      seed: async (api) => {
        await cron(api, 'nightly');
        await hook(api, 'on-push');
      },
    });
    await loaded(view);

    const nightly = rows(view)[0].querySelector('.schedule')!;
    expect(nightly.querySelector('.cron')!.textContent).toBe('30 9 * * 1-5');
    expect(nightly.textContent).toContain('Asia/Taipei');
    expect(nightly.querySelector('.words')!.textContent).toBe('At 09:30, Monday to Friday');
    expect(rows(view)[1].querySelector('.schedule')!.textContent).toContain('Called from outside');
  });

  test('says nothing of an expression it cannot read, and does not call it wrong', async () => {
    const { view } = await page({
      seed: async (api, backend) => {
        await cron(api, 'odd');
        // What the Engine accepts may be more than the five standard fields the page reads.
        backend.triggers.triggers.get('odd')!.cron = '0 0 L * *';
      },
    });
    await loaded(view);

    expect(view.querySelector('.cron')!.textContent).toBe('0 0 L * *');
    expect(view.querySelector('.words')).toBeNull();
    expect(view.querySelector('[role="alert"]')).toBeNull();
  });

  test('has a switch for enabled that is on or off in words as well, and turning it off disables the trigger on the Engine', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api, 'nightly')) });
    await loaded(view);
    const toggle = () => rows(view)[0].querySelector<HTMLInputElement>('input[role="switch"]')!;

    expect(toggle().checked).toBe(true);
    expect(toggle().getAttribute('aria-label')).toBe('Enabled: nightly');
    expect(rows(view)[0].querySelector('.state')!.textContent).toBe('On');

    toggle().click();
    await vi.waitFor(() => expect(rows(view)[0].querySelector('.state')!.textContent).toBe('Off'));
    expect((await app.context.api.trigger('nightly')).enabled).toBe(false);
    expect(toggle().checked).toBe(false);

    toggle().click();
    await vi.waitFor(() => expect(rows(view)[0].querySelector('.state')!.textContent).toBe('On'));
    expect((await app.context.api.trigger('nightly')).enabled).toBe(true);
  });

  test('says when the switch could not be turned, and leaves it as it was', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api, 'nightly')) });
    await loaded(view);
    app.engine.faults.push({
      match: /PATCH \/api\/v1\/triggers\/nightly/,
      status: 404,
      body: { error: 'trigger_not_found', message: 'gone' },
      times: 1,
    });

    rows(view)[0].querySelector<HTMLInputElement>('input[role="switch"]')!.click();

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')!.textContent).toContain('No such trigger'));
    expect(rows(view)[0].querySelector<HTMLInputElement>('input[role="switch"]')!.checked).toBe(true);
  });

  test('deleting asks first, and says what follows; Cancel deletes nothing, confirming deletes the trigger', async () => {
    const { view } = await page({
      seed: async (api) => {
        await cron(api, 'nightly');
        await hook(api, 'on-push');
      },
    });
    await loaded(view);

    button(rows(view)[0], 'Delete').click();
    await tick();
    const dialog = view.querySelector('[role="dialog"]')!;
    expect(dialog.querySelector('h2')!.textContent).toBe('Delete the trigger nightly?');
    expect(dialog.textContent).toContain('firing records are deleted with it');
    button(dialog, 'Cancel').click();
    await tick();
    expect(view.querySelector('[role="dialog"]')).toBeNull();
    expect(rows(view)).toHaveLength(2);

    button(rows(view)[0], 'Delete').click();
    await tick();
    button(view.querySelector('[role="dialog"]')!, 'Delete the trigger').click();
    await vi.waitFor(() => expect(rows(view)).toHaveLength(1));
    expect(rows(view)[0].querySelector('.name')!.textContent!.trim()).toBe('on-push');
    expect((await app.context.api.triggers()).map((t) => t.name)).toEqual(['on-push']);
  });

  test('says what the Engine refused when deleting, inside the confirmation', async () => {
    const { view } = await page({ seed: async (api) => void (await cron(api, 'nightly')) });
    await loaded(view);
    app.engine.faults.push({
      match: /DELETE \/api\/v1\/triggers\/nightly/,
      status: 404,
      body: { error: 'trigger_not_found', message: 'gone' },
      times: 1,
    });

    button(rows(view)[0], 'Delete').click();
    await tick();
    button(view.querySelector('[role="dialog"]')!, 'Delete the trigger').click();

    await vi.waitFor(() =>
      expect(view.querySelector('[role="dialog"] [role="alert"]')!.textContent).toContain('No such trigger'),
    );
  });

  test('leads to the page of a trigger, to its change, and to making a new one', async () => {
    const { view } = await page({ seed: async (api) => void (await hook(api, 'on.push')) });
    await loaded(view);

    expect(rows(view)[0].querySelector('.name a')!.getAttribute('href')).toBe('/triggers/detail?name=on.push');
    expect(
      [...rows(view)[0].querySelectorAll('a')].map((a) => a.getAttribute('href')),
    ).toContain('/triggers/edit?name=on.push');
    expect(view.querySelector('.rl-page-head a')!.getAttribute('href')).toBe('/triggers/new');
  });

  test('says there are none yet, and how to make one', async () => {
    const { view } = await page();
    await loaded(view);
    expect(view.querySelector('.rl-empty')!.textContent).toContain('No triggers yet');
    expect(view.querySelector('.rl-empty a')!.getAttribute('href')).toBe('/triggers/new');
  });

  test('says when the Engine refuses the list, with a way to try again', async () => {
    app = await createTestApp({ identity: root });
    app.engine.faults.push({ match: /GET \/api\/v1\/triggers/, status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-1' }, times: 1 });
    const view = app.mount(TriggersPage, { clock: new ManualClock(), visibility: new ManualVisibility() });
    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());

    button(view, 'Retry').click();
    await loaded(view);
  });

  test('speaks the language of the screen', async () => {
    const { view } = await page({ languages: ['zh-TW'], seed: async (api) => void (await cron(api, 'nightly')) });
    await loaded(view);
    expect(view.querySelector('h1')!.textContent).toContain('觸發器');
    expect(rows(view)[0].querySelector('.kind')!.textContent).toContain('Cron');
    expect(rows(view)[0].querySelector('.words')!.textContent).toBe('09:30，星期一至星期五');
    expect(view.textContent).toContain('建立 trigger');
  });
});

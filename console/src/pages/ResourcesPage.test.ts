import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import { demoJars } from '../../test-support/fake-jars';
import ResourcesPage from './ResourcesPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };

const page = async (
  options: {
    languages?: string[];
    seed?: (backend: TestApp['engine']['backend']) => void;
    /** What the Engine answers to `GET /api/v1/resource-types` instead of its own catalog. */
    catalog?: { status: number; body: unknown };
  } = {},
) => {
  app = await createTestApp({ identity: root, languages: options.languages });
  app.engine.backend.autoRun = false;
  options.seed?.(app.engine.backend);
  if (options.catalog) app.engine.faults.push({ match: /GET \/api\/v1\/resource-types/, ...options.catalog, times: Infinity });
  app.context.router.navigate('/resources');
  const clock = new ManualClock();
  const visibility = new ManualVisibility();
  const view = app.mount(ResourcesPage, { clock, visibility });
  return { view, clock, visibility };
};
const loaded = (view: HTMLElement) =>
  vi.waitFor(() => expect(view.querySelector('.resource, .rl-empty')).not.toBeNull());
const cards = (view: HTMLElement) => [...view.querySelectorAll<HTMLElement>('.resource')];
const card = (view: HTMLElement, name: string) =>
  cards(view).find((c) => c.querySelector('.name')!.textContent!.trim() === name)!;
const button = (root: ParentNode, label: string) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === label)!;
const dialog = (view: HTMLElement) => view.querySelector<HTMLElement>('[role="dialog"]')!;
const type = (input: HTMLInputElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
};
const choose = (select: HTMLSelectElement, value: string) => {
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
};
/** The error said at the field (or group of fields) of this id. */
const errorAt = (view: HTMLElement, id: string) =>
  dialog(view).querySelector(`#${id}`)!.closest('.rl-field, fieldset')!.querySelector('.rl-field-error')?.textContent ?? null;
const RUN_A = '11111111-1111-4111-8111-111111111111';
const RUN_B = '22222222-2222-4222-8222-222222222222';

/** `printer` with capacity 1: one run holds it, one waits; `scanner` is disabled and idle. */
const busy = (backend: TestApp['engine']['backend']) => {
  backend.defineResource('printer', true, 1);
  backend.defineResource('scanner', false, 3);
  backend.seedRun('ada', { runId: RUN_A, state: 'RUNNING', pipeline: 'demo-resource' });
  backend.seedRun('ada', { runId: RUN_B, state: 'WAITING_FOR_RESOURCES', pipeline: 'demo-other' });
  backend.seedHolder('printer', RUN_A, 'demo-resource');
  backend.seedWaiter('printer', RUN_B, 'demo-other');
};

describe('the shared resources', () => {
  test('says there are none, and how to define one', async () => {
    const { view } = await page();
    await loaded(view);
    expect(view.querySelector('h1')!.textContent).toBe('Resources');
    expect(view.querySelector('.rl-empty')!.textContent).toContain('No shared resources');
    expect(button(view.querySelector('.rl-empty')!, 'Define a resource')).toBeDefined();
  });

  test('has a card for each: its name, whether it is on, its capacity and how much of it is held', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);

    expect(cards(view).map((c) => c.querySelector('.name')!.textContent!.trim())).toEqual(['printer', 'scanner']);
    const printer = card(view, 'printer');
    expect(printer.querySelector('.state')!.textContent).toContain('Enabled');
    expect(printer.querySelector('.usage')!.textContent).toContain('1 of 1');
    expect(printer.querySelector('progress')!.getAttribute('value')).toBe('1');
    expect(printer.querySelector('progress')!.getAttribute('max')).toBe('1');
    const scanner = card(view, 'scanner');
    expect(scanner.querySelector('.state')!.textContent).toContain('Disabled');
    expect(scanner.querySelector('.usage')!.textContent).toContain('0 of 3');
  });

  test('says who holds it and who waits, with the run, the pipeline and for how long', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    const printer = card(view, 'printer');

    const holder = printer.querySelector('.holders li')!;
    expect(holder.querySelector('a')!.getAttribute('href')).toBe(`/runs/${RUN_A}`);
    expect(holder.querySelector('.pipeline')!.textContent).toBe('demo-resource');
    expect(holder.querySelector('time')).not.toBeNull();
    const waiter = printer.querySelector('.waiters li')!;
    expect(waiter.querySelector('a')!.getAttribute('href')).toBe(`/runs/${RUN_B}`);
    expect(waiter.querySelector('.pipeline')!.textContent).toBe('demo-other');
    expect(waiter.querySelector('.position')!.textContent).toBe('1');
    expect(waiter.textContent).toContain('printer');
  });

  test('says when nobody holds it or waits for it', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    const scanner = card(view, 'scanner');
    expect(scanner.querySelector('.holders')!.textContent).toContain('Nobody holds it');
    expect(scanner.querySelector('.waiters')!.textContent).toContain('Nobody waits');
  });

  test('says when more hold it than its capacity, because the capacity was lowered', async () => {
    const { view } = await page({
      seed: (backend) => {
        backend.defineResource('printer', true, 1);
        for (const id of [RUN_A, RUN_B]) {
          backend.seedRun('ada', { runId: id, state: 'RUNNING', pipeline: 'p' });
          backend.seedHolder('printer', id, 'p');
        }
      },
    });
    await loaded(view);
    expect(card(view, 'printer').querySelector('.usage')!.textContent).toContain('2 of 1');
    expect(card(view, 'printer').textContent).toContain('capacity was lowered');
  });

  test('is read again while the page is shown, and a new holder appears', async () => {
    const { view, clock } = await page({ seed: (b) => b.defineResource('printer', true, 2) });
    await loaded(view);
    expect(card(view, 'printer').querySelectorAll('.holders li')).toHaveLength(0);

    app.engine.backend.seedRun('ada', { runId: RUN_A, state: 'RUNNING', pipeline: 'demo-resource' });
    app.engine.backend.seedHolder('printer', RUN_A, 'demo-resource');
    clock.advance(3000);

    await vi.waitFor(() => expect(card(view, 'printer').querySelectorAll('.holders li')).toHaveLength(1));
  });

  test('is not read while the page is hidden', async () => {
    const { view, clock, visibility } = await page({ seed: busy });
    await loaded(view);
    visibility.set(false);
    const before = app.engine.requests;
    clock.advance(60_000);
    await tick();
    expect(app.engine.requests).toBe(before);
  });

  test('says in words when the Engine refuses the list, with a way to try again', async () => {
    app = await createTestApp({ identity: root });
    app.engine.faults.push({ match: /GET \/api\/v1\/resources/, status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-4' }, times: 1 });
    const view = app.mount(ResourcesPage, { clock: new ManualClock(), visibility: new ManualVisibility() });
    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    button(view, 'Retry').click();
    await loaded(view);
  });
});

describe('defining a resource', () => {
  test('makes it with its name and capacity, and it is on the page, enabled, by the admin', async () => {
    const { view } = await page();
    await loaded(view);

    button(view, 'Define a resource').click();
    await tick();
    expect(dialog(view).querySelector('h2')!.textContent).toBe('Define a shared resource');
    choose(dialog(view).querySelector('#resource-type')!, 'counter');
    await tick();
    type(dialog(view).querySelector('#resource-name')!, 'demo-printer');
    type(dialog(view).querySelector('#resource-capacity')!, '2');
    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(cards(view)).toHaveLength(1));
    expect(dialog(view)).toBeNull();
    expect(card(view, 'demo-printer').querySelector('.usage')!.textContent).toContain('0 of 2');
    expect(card(view, 'demo-printer').textContent).toContain('root');
  });

  test('asks for a name and a capacity of one or more before it asks the Engine', async () => {
    const { view } = await page();
    await loaded(view);
    button(view, 'Define a resource').click();
    await tick();
    choose(dialog(view).querySelector('#resource-type')!, 'counter');
    await tick();

    button(dialog(view), 'Define').click();
    await tick();
    expect(dialog(view).querySelector('#resource-name')!.closest('.rl-field')!.querySelector('.rl-field-error')!.textContent).toBe('Enter a name.');

    type(dialog(view).querySelector('#resource-name')!, 'x');
    type(dialog(view).querySelector('#resource-capacity')!, '0');
    button(dialog(view), 'Define').click();
    await tick();
    expect(dialog(view).querySelector('#resource-capacity')!.closest('.rl-field')!.querySelector('.rl-field-error')!.textContent).toBe('The capacity must be a whole number of 1 or more.');
    expect(app.engine.backend.resources.enabledOf('x')).toBeUndefined();
  });

  test('says at the name that it exists, and what else the Engine found wrong', async () => {
    const { view } = await page({ seed: (b) => b.defineResource('printer') });
    await loaded(view);
    button(view, 'Define a resource').click();
    await tick();
    choose(dialog(view).querySelector('#resource-type')!, 'counter');
    await tick();

    type(dialog(view).querySelector('#resource-name')!, 'printer');
    button(dialog(view), 'Define').click();
    await vi.waitFor(() =>
      expect(dialog(view).querySelector('#resource-name')!.closest('.rl-field')!.querySelector('.rl-field-error')!.textContent).toBe(
        'A shared resource of this name exists already.',
      ),
    );

    type(dialog(view).querySelector('#resource-name')!, '-bad');
    button(dialog(view), 'Define').click();
    await vi.waitFor(() => expect(errorAt(view, 'resource-name')).toContain('not valid'));
    expect(dialog(view)).not.toBeNull();
  });
});

describe('the type of a resource in its form', () => {
  test('is chosen first, among the types the Console has a form for; the fields of the type come after', async () => {
    const { view } = await page();
    await loaded(view);
    button(view, 'Define a resource').click();
    await tick();

    const select = dialog(view).querySelector<HTMLSelectElement>('#resource-type')!;
    expect([...select.options].map((o) => [o.value, o.textContent!.trim()])).toEqual([
      ['', 'Choose a type'],
      ['counter', 'Counter'],
      ['file', 'File'],
      ['jdbc-pool', 'Database pool (JDBC)'],
      ['openai-compatible', 'OpenAI-compatible service'],
    ]);
    expect(dialog(view).querySelector('#resource-name')).toBeNull();
    expect(dialog(view).querySelector('#resource-capacity')).toBeNull();

    choose(select, 'counter');
    await tick();

    expect(dialog(view).querySelector('#resource-name')).not.toBeNull();
    expect(dialog(view).querySelector('#resource-capacity')).not.toBeNull();
    expect(dialog(view).textContent).toContain('cannot be changed');
  });

  test('asks for a type when none was chosen, before it asks the Engine', async () => {
    const { view } = await page();
    await loaded(view);
    button(view, 'Define a resource').click();
    await tick();

    button(dialog(view), 'Define').click();
    await tick();

    expect(dialog(view).querySelector('#resource-type')!.closest('.rl-field')!.querySelector('.rl-field-error')!.textContent).toBe('Choose a type.');
    expect((await app.context.api.resources()).length).toBe(0);
  });

  test('a resource is defined with the type that was chosen', async () => {
    const { view } = await page();
    await loaded(view);
    button(view, 'Define a resource').click();
    await tick();
    choose(dialog(view).querySelector('#resource-type')!, 'counter');
    await tick();
    type(dialog(view).querySelector('#resource-name')!, 'printer');
    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(cards(view)).toHaveLength(1));
    expect(card(view, 'printer').querySelector('.type')!.textContent!.trim()).toBe('Counter');
  });

  test('says the name and the type of a resource that is changed, which cannot be changed', async () => {
    const { view } = await page({ seed: typed });
    await loaded(view);
    button(card(view, 'llm'), 'Change').click();
    await tick();

    const fixed = dialog(view).querySelector('.fixed')!;
    expect([...fixed.querySelectorAll('dd')].map((d) => d.textContent!.trim())).toEqual(['llm', 'OpenAI-compatible service']);
    expect(dialog(view).querySelector('#resource-name')).toBeNull();
    expect(dialog(view).querySelector('#resource-type')).toBeNull();
  });

  test('never asks for a secret value: of any type, a secret is chosen by its alias, and nothing else is about a secret', async () => {
    const { view } = await page({ seed: typed });
    await loaded(view);
    for (const option of ['counter', 'file', 'jdbc-pool', 'openai-compatible']) {
      button(view, 'Define a resource').click();
      await tick();
      choose(dialog(view).querySelector('#resource-type')!, option);
      await tick();
      expect([option, dialog(view).querySelectorAll('input[type="password"], textarea')]).toEqual([option, expect.objectContaining({ length: 0 })]);
      const secretFields = [...dialog(view).querySelectorAll<HTMLElement>('input, select')].filter((e) => /secret|key|password/i.test(e.id));
      expect([option, secretFields.map((e) => `${e.tagName}#${e.id}`)]).toEqual([
        option,
        option === 'jdbc-pool' || option === 'openai-compatible' ? ['SELECT#resource-secret'] : [],
      ]);
      button(dialog(view), 'Cancel').click();
      await tick();
    }

    button(card(view, 'llm'), 'Change').click();
    await tick();
    expect(dialog(view).querySelectorAll('input[type="password"], textarea')).toHaveLength(0);
  });
});

describe('changing a resource', () => {
  test('starts from what it is, and says what lowering the capacity and disabling do', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);

    button(card(view, 'printer'), 'Change').click();
    await tick();

    expect(dialog(view).querySelector('h2')!.textContent).toBe('Change printer');
    expect(dialog(view).querySelector<HTMLInputElement>('#resource-capacity')!.value).toBe('1');
    expect(dialog(view).querySelector<HTMLInputElement>('#resource-enabled')!.checked).toBe(true);
    expect(dialog(view).textContent).toContain('does not take the resource back');
    expect(dialog(view).textContent).toContain('fail');
  });

  test('saves the capacity and whether it is on', async () => {
    const { view } = await page({ seed: (b) => b.defineResource('printer', true, 1) });
    await loaded(view);
    button(card(view, 'printer'), 'Change').click();
    await tick();

    type(dialog(view).querySelector('#resource-capacity')!, '4');
    button(dialog(view), 'Save').click();

    await vi.waitFor(() => expect(card(view, 'printer').querySelector('.usage')!.textContent).toContain('0 of 4'));
    expect(dialog(view)).toBeNull();
  });

  test('warns when turning it off would fail the runs that wait for it, and says how many', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    button(card(view, 'printer'), 'Change').click();
    await tick();
    expect(dialog(view).querySelector('.warning')).toBeNull();

    dialog(view).querySelector<HTMLInputElement>('#resource-enabled')!.click();
    await tick();

    expect(dialog(view).querySelector('.warning')!.textContent).toContain('1 run');
    expect(dialog(view).querySelector('.warning')!.textContent).toContain('fail');
    button(dialog(view), 'Save and fail the waiting runs').click();
    await vi.waitFor(() => expect(card(view, 'printer').querySelector('.state')!.textContent).toContain('Disabled'));
  });

  test('says there is nothing to change when nothing is, and a capacity that is not a whole number', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    button(card(view, 'printer'), 'Change').click();
    await tick();

    button(dialog(view), 'Save').click();
    await tick();
    expect(dialog(view).querySelector('.rl-field-error')!.textContent).toBe('There is nothing to change.');

    type(dialog(view).querySelector('#resource-capacity')!, '1.5');
    button(dialog(view), 'Save').click();
    await tick();
    expect(dialog(view).querySelector('#resource-capacity')!.closest('.rl-field')!.querySelector('.rl-field-error')!.textContent).toBe('The capacity must be a whole number of 1 or more.');
  });

  test('says what the Engine refused, inside the dialog', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    button(card(view, 'printer'), 'Change').click();
    await tick();
    app.engine.faults.push({ match: /PATCH \/api\/v1\/resources\/printer/, status: 404, body: { error: 'resource_not_found', message: 'x' }, times: 1 });
    type(dialog(view).querySelector('#resource-capacity')!, '3');

    button(dialog(view), 'Save').click();

    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('No such shared resource'));
  });
});

describe('making a holder let go', () => {
  test('asks first, and says that the run is not stopped and that another run may then use the resource', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);

    button(card(view, 'printer'), 'Release').click();
    await tick();

    const ask = dialog(view);
    expect(ask.querySelector('h2')!.textContent).toBe('Make the run let go of printer?');
    expect(ask.textContent).toContain('not stopped');
    expect(ask.textContent).toContain('at the same time');
    expect(ask.textContent).toContain('demo-resource');
    expect(ask.textContent).toContain(RUN_A.slice(0, 8));
  });

  test('does it when confirmed: the holder is gone, the first waiter holds the resource, the run goes on', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    button(card(view, 'printer'), 'Release').click();
    await tick();

    button(dialog(view), 'Release the resource').click();

    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    await vi.waitFor(() =>
      expect(card(view, 'printer').querySelector('.holders li a')!.getAttribute('href')).toBe(`/runs/${RUN_B}`),
    );
    expect(card(view, 'printer').querySelectorAll('.waiters li')).toHaveLength(0);
    expect(app.engine.backend.runs.find((r) => r.runId === RUN_A)!.state).toBe('RUNNING');
  });

  test('does nothing when it is not confirmed', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    button(card(view, 'printer'), 'Release').click();
    await tick();

    button(dialog(view), 'Cancel').click();
    await tick();

    expect(dialog(view)).toBeNull();
    expect(card(view, 'printer').querySelector('.holders li a')!.getAttribute('href')).toBe(`/runs/${RUN_A}`);
  });

  test('says what the Engine refused: the run does not hold it any more', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    button(card(view, 'printer'), 'Release').click();
    await tick();
    app.engine.faults.push({ match: /release/, status: 404, body: { error: 'not_a_holder', message: 'x' }, times: 1 });

    button(dialog(view), 'Release the resource').click();

    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('does not hold'));
  });
});

describe('in zh-TW', () => {
  test('speaks the language of the screen', async () => {
    const { view } = await page({ languages: ['zh-TW'], seed: busy });
    await loaded(view);
    expect(view.querySelector('h1')!.textContent).toContain('共享資源');
    expect(card(view, 'printer').querySelector('.state')!.textContent).toContain('啟用');
    expect(card(view, 'printer').querySelector('.usage')!.textContent).toContain('1 / 1');
    expect(card(view, 'printer').querySelector('.type')!.textContent).toContain('計數');
    expect(button(card(view, 'printer'), '檢查')).toBeDefined();
    expect(button(card(view, 'printer'), '刪除')).toBeDefined();
    expect(view.querySelector('section.secrets h2')!.textContent).toBe('金鑰庫機密');
  });
});

/** A resource of each kind the cards must tell apart, with the keystore that the aliases are in. */
const typed = (backend: TestApp['engine']['backend']) => {
  backend.secrets.configure([
    { alias: 'llm-key', type: 'secret', status: 'found', fingerprint: 'a' },
    { alias: 'db-pass', type: 'secret', status: 'invalid_secret', fingerprint: 'b' },
  ]);
  backend.resources.define('llm', {
    type: 'openai-compatible',
    settings: { baseUrl: 'http://llm.internal:8000/v1', endpoints: ['models.list'] },
    secretAlias: 'llm-key',
  });
  backend.resources.define('orders-db', {
    type: 'jdbc-pool',
    settings: { kind: 'postgresql', host: 'db.internal', port: 5432, database: 'orders', username: 'reader' },
    secretAlias: 'db-pass',
  });
  backend.resources.define('local-llm', {
    type: 'openai-compatible',
    settings: { baseUrl: 'http://localhost:8000/v1' },
  });
  backend.resources.define('gone-key', {
    type: 'openai-compatible',
    settings: { baseUrl: 'http://x/v1' },
    secretAlias: 'not-there',
  });
  backend.resources.define('report', { type: 'file', settings: { path: 'reports/today.csv' } });
  backend.defineResource('printer');
};

describe('what a card says of its type', () => {
  test('a label of the type of each resource', async () => {
    const { view } = await page({ seed: typed });
    await loaded(view);
    const typeOf = (name: string) => card(view, name).querySelector('.type')!.textContent!.trim();
    expect(typeOf('llm')).toBe('OpenAI-compatible service');
    expect(typeOf('orders-db')).toBe('Database pool (JDBC)');
    expect(typeOf('report')).toBe('File');
    expect(typeOf('printer')).toBe('Counter');
  });

  test('the settings that are not secret, as a summary; a counter has none', async () => {
    const { view } = await page({ seed: typed });
    await loaded(view);
    const lines = (name: string) =>
      [...card(view, name).querySelectorAll('.settings div')].map((d) => [
        d.querySelector('dt')!.textContent!.trim(),
        d.querySelector('dd')!.textContent!.trim(),
      ]);
    expect(lines('llm')).toEqual([['Address', 'http://llm.internal:8000/v1']]);
    expect(lines('orders-db')).toEqual([
      ['Database', 'postgresql'],
      ['Host', 'db.internal'],
      ['Port', '5432'],
      ['Database name', 'orders'],
      ['Account', 'reader'],
    ]);
    expect(lines('report')).toEqual([['Path', 'reports/today.csv']]);
    expect(card(view, 'printer').querySelector('.settings')).toBeNull();
  });

  test('the alias of its secret and whether the keystore has it, never a value; nothing of secrets for the types that have none', async () => {
    const { view } = await page({ seed: typed });
    await loaded(view);
    const secret = (name: string) => {
      const row = card(view, name).querySelector('.secret');
      return row === null
        ? null
        : [row.querySelector('.alias')?.textContent!.trim() ?? null, row.querySelector('.secret-status')!.textContent!.trim()];
    };
    expect(secret('llm')).toEqual(['llm-key', 'Found in the keystore']);
    expect(secret('orders-db')).toEqual(['db-pass', 'In the keystore, but not usable: not printable ASCII']);
    expect(secret('gone-key')).toEqual(['not-there', 'Not in the keystore']);
    expect(secret('local-llm')).toEqual([null, 'No secret set']);
    expect(secret('report')).toBeNull();
    expect(secret('printer')).toBeNull();
  });

  test('the result of its last check and when it was, or that it was never checked', async () => {
    const { view } = await page({
      seed: (backend) => {
        backend.resources.define('llm', {
          type: 'openai-compatible',
          settings: { baseUrl: 'http://x/v1' },
          lastCheck: { ok: false, failure: 'connection_failed', checkedAt: '2026-10-07T01:02:03Z' },
        });
        backend.resources.define('report', {
          type: 'file',
          settings: { path: 'a.txt' },
          lastCheck: { ok: true, failure: null, checkedAt: '2026-10-07T04:05:06Z' },
        });
        backend.defineResource('printer');
      },
    });
    await loaded(view);
    const last = (name: string) => card(view, name).querySelector('.last-check')!;
    expect(last('llm').querySelector('.outcome')!.textContent!.trim()).toBe('Failed: the service could not be reached');
    expect(last('llm').querySelector('time')!.getAttribute('datetime')).toBe('2026-10-07T01:02:03Z');
    expect(last('report').querySelector('.outcome')!.textContent!.trim()).toBe('Passed');
    expect(last('report').querySelector('time')).not.toBeNull();
    expect(last('printer').textContent).toContain('Never checked');
    expect(last('printer').querySelector('time')).toBeNull();
  });

  test('the pipelines that declare it, each a link to its page with the version, the type it expects and its triggers; or that none does', async () => {
    const { view, clock } = await page({
      seed: (b) => {
        b.defineResource('demo-printer');
        b.defineResource('scanner');
      },
    });
    await loaded(view);
    const upload = (bytes: Uint8Array) =>
      app.context.api.uploadJar(new File([bytes as BlobPart], 'pipeline.jar')).then((u) => u.artifact.contentHash);
    const plain = await upload(demoJars().resource);
    const typedHash = await upload(demoJars().typed);
    clock.advance(3000);
    await vi.waitFor(() => expect(card(view, 'demo-printer').querySelectorAll('.declared li')).toHaveLength(2));

    const declared = card(view, 'demo-printer').querySelector('.declared')!;
    expect(declared.querySelector('.summary')!.textContent!.trim()).toBe('Declared by 2 pipeline definitions, with 0 triggers bound to them');
    const rows = [...declared.querySelectorAll('li')].map((li) => ({
      href: li.querySelector('a')!.getAttribute('href'),
      text: li.textContent!.replace(/\s+/g, ' ').trim(),
    }));
    expect(rows).toContainEqual({
      href: `/pipelines/${plain}?pipeline=demo-resource&uploader=root`,
      text: expect.stringContaining('any type'),
    });
    expect(rows).toContainEqual({
      href: `/pipelines/${typedHash}?pipeline=demo-typed&uploader=root`,
      text: expect.stringContaining('expects File'),
    });
    expect(rows.every((r) => r.text.includes('root') && r.text.includes('0 triggers'))).toBe(true);
    expect(card(view, 'scanner').querySelector('.declared')!.textContent).toContain('No pipeline declares it.');
  });
});

describe('checking a resource', () => {
  test('is done when the admin asks, and the card shows the result as the last check', async () => {
    const { view } = await page({
      seed: (b) => b.resources.define('report', { type: 'file', settings: { path: 'a.txt' }, entityFailure: 'not_readable_writable' }),
    });
    await loaded(view);
    expect(card(view, 'report').querySelector('.last-check')!.textContent).toContain('Never checked');

    button(card(view, 'report'), 'Check').click();

    await vi.waitFor(() =>
      expect(card(view, 'report').querySelector('.last-check .outcome')!.textContent!.trim()).toBe(
        'Failed: the file cannot be read and written',
      ),
    );
    expect(app.engine.log.filter((entry) => entry.path.endsWith('/check'))).toHaveLength(1);
  });

  test('is never done by the page reading itself again', async () => {
    const { view, clock } = await page({ seed: busy });
    await loaded(view);
    const reads = () => app.engine.log.filter((e) => e.path === '/api/v1/resources').length;
    for (let i = 0; i < 3; i++) {
      const before = reads();
      clock.advance(3000);
      await vi.waitFor(() => expect(reads()).toBeGreaterThan(before));
    }
    expect(app.engine.log.some((entry) => entry.path.includes('/check'))).toBe(false);
  });

  test('says on the card what the Engine refused', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    app.engine.faults.push({ match: /POST \/api\/v1\/resources\/printer\/check/, status: 404, body: { error: 'resource_not_found', message: 'x' }, times: 1 });

    button(card(view, 'printer'), 'Check').click();

    await vi.waitFor(() => expect(card(view, 'printer').querySelector('[role="alert"]')!.textContent).toContain('No such shared resource'));
  });
});

describe('deleting a resource', () => {
  test('first shows what declares it and that nobody uses it, and deletes it only when confirmed', async () => {
    const { view } = await page({ seed: (b) => b.defineResource('demo-printer') });
    await loaded(view);
    await app.context.api.uploadJar(new File([demoJars().resource as BlobPart], 'pipeline.jar'));

    button(card(view, 'demo-printer'), 'Delete').click();

    await vi.waitFor(() => expect(dialog(view).querySelector('.preview')).not.toBeNull());
    const ask = dialog(view);
    expect(ask.querySelector('h2')!.textContent).toBe('Delete demo-printer?');
    expect(ask.querySelector('.preview')!.textContent).toContain('1 pipeline definition declares it, with 0 triggers bound to it');
    expect(ask.querySelector('.preview')!.textContent).toContain('No run holds it or waits for it');
    expect(ask.textContent).toContain('refused');
    expect(app.engine.backend.resources.typeOf('demo-printer')).toBe('counter');

    button(ask, 'Delete the resource').click();

    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    await vi.waitFor(() => expect(view.querySelector('.rl-empty')).not.toBeNull());
    expect(app.engine.backend.resources.typeOf('demo-printer')).toBeUndefined();
  });

  test('does nothing when it is not confirmed', async () => {
    const { view } = await page({ seed: (b) => b.defineResource('printer') });
    await loaded(view);
    button(card(view, 'printer'), 'Delete').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('.preview')).not.toBeNull());

    button(dialog(view), 'Cancel').click();
    await tick();

    expect(dialog(view)).toBeNull();
    expect(app.engine.backend.resources.typeOf('printer')).toBe('counter');
  });

  test('in use, says who holds it and waits, that it cannot be deleted now, and how to free it; it cannot be confirmed', async () => {
    const { view } = await page({ seed: busy });
    await loaded(view);
    button(card(view, 'printer'), 'Delete').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('.preview')).not.toBeNull());

    const ask = dialog(view);
    expect(ask.querySelector('.preview')!.textContent).toContain('1 run holds it and 1 run waits for it');
    expect(ask.querySelector('.in-use')!.textContent).toContain('disable');
    expect(ask.querySelector('.in-use')!.textContent).toContain('release');
    expect(button(ask, 'Delete the resource').disabled).toBe(true);
  });

  test('when a run took it after the preview, says the Engine refused because it is in use, and how to free it', async () => {
    const { view } = await page({ seed: (b) => b.defineResource('printer') });
    await loaded(view);
    button(card(view, 'printer'), 'Delete').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('.preview')).not.toBeNull());
    app.engine.backend.seedRun('ada', { runId: RUN_A, state: 'RUNNING', pipeline: 'p' });
    app.engine.backend.seedHolder('printer', RUN_A, 'p');

    button(dialog(view), 'Delete the resource').click();

    await vi.waitFor(() => expect(dialog(view).querySelector('.in-use')).not.toBeNull());
    expect(dialog(view).querySelector('.in-use')!.textContent).toContain('1 run holds it and 0 runs wait for it');
    expect(app.engine.backend.resources.typeOf('printer')).toBe('counter');
  });
});

describe('the secrets of the keystore', () => {
  const keystore = (backend: TestApp['engine']['backend']) => {
    backend.secrets.configure([
      { alias: 'llm-key', type: 'secret', status: 'found', fingerprint: 'a' },
      { alias: 'corporate-ca', type: 'trusted_certificate', status: 'found', fingerprint: 'b' },
      { alias: 'broken', type: 'secret', status: 'invalid_secret', fingerprint: 'c' },
    ]);
    backend.resources.define('llm', { type: 'openai-compatible', settings: { baseUrl: 'http://x/v1' }, secretAlias: 'llm-key' });
    backend.resources.define('llm-2', { type: 'openai-compatible', settings: { baseUrl: 'http://y/v1' }, secretAlias: 'llm-key' });
  };
  const section = (view: HTMLElement) => view.querySelector<HTMLElement>('section.secrets')!;
  const secretsLoaded = (view: HTMLElement) =>
    vi.waitFor(() => expect(section(view).querySelector('table, .not-configured')).not.toBeNull());
  const rows = (view: HTMLElement) =>
    [...section(view).querySelectorAll('tbody tr')].map((tr) =>
      [...tr.querySelectorAll('td')].map((td) => td.textContent!.replace(/\s+/g, ' ').trim()),
    );

  test('lists each alias with the kind of entry, whether it can be used and the resources that refer to it', async () => {
    const { view } = await page({ seed: keystore });
    await secretsLoaded(view);
    expect(rows(view)).toEqual([
      ['broken', 'Secret', 'Not usable: not printable ASCII', '—'],
      ['corporate-ca', 'Trusted certificate', 'Found', '—'],
      ['llm-key', 'Secret', 'Found', 'llm, llm-2'],
    ]);
  });

  test('says that the Console takes no secret, that operators manage the keystore with keytool, and that this is not the secret of a webhook', async () => {
    const { view } = await page({ seed: keystore });
    await secretsLoaded(view);
    const text = section(view).textContent!;
    expect(text).toContain('keytool');
    expect(text).toContain('never');
    expect(text).toContain('webhook');
    expect(section(view).querySelectorAll('input, textarea')).toHaveLength(0);
  });

  test('reloads the keystore when asked and says how many aliases there are and which changed, with their resources', async () => {
    const { view } = await page({ seed: keystore });
    await secretsLoaded(view);
    app.engine.backend.secrets.writeFile({
      entries: [
        { alias: 'llm-key', type: 'secret', status: 'found', fingerprint: 'a2' },
        { alias: 'corporate-ca', type: 'trusted_certificate', status: 'found', fingerprint: 'b' },
      ],
    });

    button(section(view), 'Reload the keystore').click();

    await vi.waitFor(() => expect(section(view).querySelector('.reloaded')).not.toBeNull());
    const reloaded = section(view).querySelector('.reloaded')!;
    expect(reloaded.textContent).toContain('2 aliases');
    expect([...reloaded.querySelectorAll('li')].map((li) => li.textContent!.replace(/\s+/g, ' ').trim())).toEqual([
      'broken (used by no resource)',
      'llm-key (used by llm, llm-2)',
    ]);
    await vi.waitFor(() => expect(rows(view).map((r) => r[0])).toEqual(['corporate-ca', 'llm-key']));
  });

  test('says that nothing changed when nothing did', async () => {
    const { view } = await page({ seed: keystore });
    await secretsLoaded(view);
    button(section(view), 'Reload the keystore').click();
    await vi.waitFor(() => expect(section(view).querySelector('.reloaded')!.textContent).toContain('No alias changed'));
  });

  test('says in words that the Engine has no keystore, and offers no reload', async () => {
    const { view } = await page();
    await secretsLoaded(view);
    expect(section(view).querySelector('.not-configured')!.textContent).toContain('no keystore');
    expect(section(view).querySelector('[role="alert"]')).toBeNull();
    expect(button(section(view), 'Reload the keystore')).toBeUndefined();
  });

  test('says why a keystore that cannot be read was not reloaded, and that the secrets in use are unchanged', async () => {
    const { view } = await page({ seed: keystore });
    await secretsLoaded(view);
    app.engine.backend.secrets.writeFile({ problem: 'wrong_password' });

    button(section(view), 'Reload the keystore').click();

    await vi.waitFor(() => expect(section(view).querySelector('[role="alert"]')).not.toBeNull());
    const alert = section(view).querySelector('[role="alert"]')!.textContent!;
    expect(alert).toContain('unchanged');
    expect(alert).toContain('password');
    expect(rows(view).map((r) => r[0])).toEqual(['broken', 'corporate-ca', 'llm-key']);
  });
});

/** Opens the form to define a resource of [option], with a name. */
const defineForm = async (view: HTMLElement, option: string, name: string) => {
  button(view, 'Define a resource').click();
  await tick();
  choose(dialog(view).querySelector('#resource-type')!, option);
  await tick();
  type(dialog(view).querySelector('#resource-name')!, name);
};
const field = (view: HTMLElement, id: string) => dialog(view).querySelector<HTMLInputElement>(`#${id}`)!;
/** The settings that the Engine was last sent, by the method of the call. */
const sent = (method: string) => {
  const call = [...app.engine.received].reverse().find((entry) => entry.method === method && entry.path.startsWith('/api/v1/resources'));
  return call === undefined ? undefined : JSON.parse(call.body);
};
const keystore = (backend: TestApp['engine']['backend']) =>
  backend.secrets.configure([
    { alias: 'orders-pass', type: 'secret', status: 'found', fingerprint: 'a' },
    { alias: 'llm-key', type: 'secret', status: 'found', fingerprint: 'b' },
    { alias: 'broken', type: 'secret', status: 'invalid_secret', fingerprint: 'c' },
    { alias: 'corporate-ca', type: 'trusted_certificate', status: 'found', fingerprint: 'd' },
  ]);

describe('the form of a file', () => {
  test('defines it with its path under the resource root, and its card shows the path', async () => {
    const { view } = await page();
    await loaded(view);
    await defineForm(view, 'file', 'report');
    expect(dialog(view).textContent).toContain('resource root');
    type(field(view, 'resource-path'), 'reports/today.csv');

    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(cards(view)).toHaveLength(1));
    expect(sent('POST')).toEqual({ name: 'report', type: 'file', capacity: 1, settings: { path: 'reports/today.csv' } });
    expect(card(view, 'report').querySelector('.settings dd')!.textContent).toBe('reports/today.csv');
  });

  test('says at the path that it is needed, and that the Engine refused one out of the resource root', async () => {
    const { view } = await page();
    await loaded(view);
    await defineForm(view, 'file', 'report');
    button(dialog(view), 'Define').click();
    await tick();
    expect(errorAt(view, 'resource-path')).toBe('Fill this in.');

    type(field(view, 'resource-path'), '../etc/passwd');
    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(errorAt(view, 'resource-path')).toContain('outside the resource root'));
    expect(dialog(view).querySelector('[role="alert"]')).toBeNull();
  });
});

describe('the form of a jdbc-pool', () => {
  test('defines it with the database, the account and the alias of its password, its properties, connections per run and timeouts', async () => {
    const { view } = await page({ seed: keystore });
    await loaded(view);
    await defineForm(view, 'jdbc-pool', 'orders-db');
    const kinds = dialog(view).querySelector<HTMLSelectElement>('#jdbc-kind')!;
    // The kinds the Engine tells, each as it names it (WI-55: the Console has no list or label of its own).
    expect([...kinds.options].map((o) => [o.value, o.textContent!.trim()])).toEqual([['postgresql', 'postgresql']]);
    type(field(view, 'jdbc-host'), 'db.internal');
    type(field(view, 'jdbc-port'), '6543');
    type(field(view, 'jdbc-database'), 'orders');
    type(field(view, 'jdbc-username'), 'reader');
    await vi.waitFor(() => expect(dialog(view).querySelectorAll('#resource-secret option').length).toBeGreaterThan(1));
    choose(dialog(view).querySelector('#resource-secret')!, 'orders-pass');
    button(dialog(view), 'Add a property').click();
    await tick();
    const pair = dialog(view).querySelector('#jdbc-properties .pair')!;
    type(pair.querySelector<HTMLInputElement>('input.name')!, 'ApplicationName');
    type(pair.querySelector<HTMLInputElement>('input.value')!, 'reports');
    type(field(view, 'jdbc-per-run'), '3');
    type(field(view, 'jdbc-statement-ms'), '30000');
    type(field(view, 'resource-capacity'), '2');

    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(cards(view)).toHaveLength(1));
    expect(sent('POST')).toEqual({
      name: 'orders-db',
      type: 'jdbc-pool',
      capacity: 2,
      settings: {
        kind: 'postgresql',
        host: 'db.internal',
        port: 6543,
        database: 'orders',
        username: 'reader',
        connectionsPerRun: 3,
        timeouts: { statementMs: 30000 },
        properties: { ApplicationName: 'reports' },
      },
      secretAlias: 'orders-pass',
    });
    expect(card(view, 'orders-db').querySelector('.secret .alias')!.textContent).toBe('orders-pass');
  });

  test('says that the pool is the capacity times the connections per run, as they are typed', async () => {
    const { view } = await page();
    await loaded(view);
    await defineForm(view, 'jdbc-pool', 'orders-db');
    const pool = () => dialog(view).querySelector('.pool-size')!.textContent!.replace(/\s+/g, ' ').trim();
    expect(pool()).toContain('capacity × connections per run');
    expect(pool()).toContain('1 × 1 = 1');

    type(field(view, 'resource-capacity'), '4');
    type(field(view, 'jdbc-per-run'), '2');
    await tick();

    expect(pool()).toContain('4 × 2 = 8');
  });

  test('asks for what it needs before it asks the Engine, and says what the Engine refused at its field', async () => {
    const { view } = await page();
    await loaded(view);
    await defineForm(view, 'jdbc-pool', 'orders-db');
    type(field(view, 'jdbc-port'), 'x');
    button(dialog(view), 'Define').click();
    await tick();
    expect(errorAt(view, 'jdbc-host')).toBe('Fill this in.');
    expect(errorAt(view, 'jdbc-database')).toBe('Fill this in.');
    expect(errorAt(view, 'jdbc-username')).toBe('Fill this in.');
    expect(errorAt(view, 'jdbc-port')).toBe('Enter a whole number.');

    type(field(view, 'jdbc-port'), '');
    type(field(view, 'jdbc-host'), 'db');
    type(field(view, 'jdbc-database'), 'orders');
    type(field(view, 'jdbc-username'), 'reader');
    button(dialog(view), 'Add a property').click();
    await tick();
    type(dialog(view).querySelector<HTMLInputElement>('#jdbc-properties .pair input.name')!, 'password');
    type(dialog(view).querySelector<HTMLInputElement>('#jdbc-properties .pair input.value')!, 'x');
    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(errorAt(view, 'jdbc-properties')).toContain('not allowed'));
    expect(cards(view)).toHaveLength(0);
  });
});

describe('the form of an openai-compatible service', () => {
  test('defines it with its address, the endpoints that are enabled, the request parameters and the alias of its key', async () => {
    const { view } = await page({ seed: keystore });
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');
    type(field(view, 'openai-base-url'), 'http://llm.internal:8000/v1');
    type(field(view, 'openai-organization'), 'org-1');
    const endpoint = (id: string) => dialog(view).querySelector<HTMLInputElement>(`#openai-endpoints input[value="${id}"]`)!;
    expect(
      [...dialog(view).querySelectorAll<HTMLInputElement>('#openai-endpoints input:checked')].map((i) => i.value),
    ).toEqual(['chat.completions', 'completions', 'embeddings', 'models.list', 'models.retrieve']);
    expect(endpoint('audio.speech').closest('label')!.textContent).toContain('POST /audio/speech');
    endpoint('completions').click();
    endpoint('audio.speech').click();
    type(field(view, 'openai-parameter-model'), 'small');
    type(field(view, 'openai-allowed-models'), 'small, large');
    field(view, 'openai-locked-temperature').click();
    type(field(view, 'openai-max-max_tokens'), '4096');
    type(field(view, 'openai-first-byte-ms'), '600000');
    type(field(view, 'openai-per-run'), '2');
    await vi.waitFor(() => expect(dialog(view).querySelectorAll('#resource-secret option').length).toBeGreaterThan(1));
    choose(dialog(view).querySelector('#resource-secret')!, 'llm-key');

    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(cards(view)).toHaveLength(1));
    expect(sent('POST')).toEqual({
      name: 'llm',
      type: 'openai-compatible',
      capacity: 1,
      settings: {
        baseUrl: 'http://llm.internal:8000/v1',
        organization: 'org-1',
        endpoints: ['chat.completions', 'embeddings', 'models.list', 'models.retrieve', 'audio.speech'],
        timeouts: { firstByteMs: 600000 },
        requestsPerRun: 2,
        defaults: { model: 'small' },
        allowedModels: ['small', 'large'],
        lockedParameters: ['temperature'],
        maxValues: { max_tokens: 4096 },
      },
      secretAlias: 'llm-key',
    });
  });

  test('says what locking a parameter and a ceiling mean, and that the limit of requests is the capacity times the requests per run', async () => {
    const { view } = await page();
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');
    const parameters = dialog(view).querySelector('#openai-parameters')!.textContent!;
    expect(parameters).toContain('A locked parameter');
    expect(parameters).toContain('refused');
    expect(parameters).toContain('ceiling');
    expect(dialog(view).querySelector('.request-limit')!.textContent).toContain('1 × 1 = 1');
    expect(field(view, 'openai-max-model')).toBeNull();
  });

  test('says what the Engine refused at the field it is about: a header that looks like a credential', async () => {
    const { view } = await page();
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');
    type(field(view, 'openai-base-url'), 'http://llm/v1');
    button(dialog(view), 'Add a header').click();
    await tick();
    type(dialog(view).querySelector<HTMLInputElement>('#openai-headers .pair input.name')!, 'X-Api-Key');
    type(dialog(view).querySelector<HTMLInputElement>('#openai-headers .pair input.value')!, 'sk-123');

    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(errorAt(view, 'openai-headers')).toContain('credential'));
    expect(errorAt(view, 'openai-base-url')).toBeNull();
  });

  test('asks for one endpoint at least, and for numbers where they are, before it asks the Engine', async () => {
    const { view } = await page();
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');
    type(field(view, 'openai-base-url'), 'http://llm/v1');
    for (const box of dialog(view).querySelectorAll<HTMLInputElement>('#openai-endpoints input:checked')) box.click();
    type(field(view, 'openai-parameter-temperature'), 'hot');
    button(dialog(view), 'Define').click();
    await tick();
    expect(errorAt(view, 'openai-endpoints')).toBe('Enable one endpoint at least.');
    expect(errorAt(view, 'openai-parameters')).toContain('temperature');
    expect(app.engine.received.some((entry) => entry.method === 'POST' && entry.path === '/api/v1/resources')).toBe(false);
  });
});

describe('the alias of a secret in the form', () => {
  test('is chosen among the secrets of the keystore, with whether each can be used; certificates are not offered; none is the default', async () => {
    const { view } = await page({ seed: keystore });
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');
    const select = dialog(view).querySelector<HTMLSelectElement>('#resource-secret')!;
    await vi.waitFor(() => expect(select.options.length).toBe(4));
    expect([...select.options].map((o) => [o.value, o.textContent!.trim()])).toEqual([
      ['', 'No secret'],
      ['broken', 'broken (not usable: not printable ASCII)'],
      ['llm-key', 'llm-key'],
      ['orders-pass', 'orders-pass'],
    ]);
    expect(select.value).toBe('');
    expect(dialog(view).textContent).toContain('keytool');
  });

  test('says that the Engine has no keystore, and offers none to choose', async () => {
    const { view } = await page();
    await loaded(view);
    await defineForm(view, 'jdbc-pool', 'orders-db');
    await vi.waitFor(() => expect(dialog(view).querySelector('.secret-note')!.textContent).toContain('no keystore'));
    expect([...dialog(view).querySelector<HTMLSelectElement>('#resource-secret')!.options].map((o) => o.value)).toEqual(['']);
  });

  test('says that the keystore has no secret to choose', async () => {
    const { view } = await page({ seed: (b) => b.secrets.configure([{ alias: 'corporate-ca', type: 'trusted_certificate', status: 'found', fingerprint: 'd' }]) });
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');
    await vi.waitFor(() => expect(dialog(view).querySelector('.secret-note')!.textContent).toContain('no secret'));
  });
});

describe('changing a resource of a type', () => {
  const seeded = (backend: TestApp['engine']['backend']) => {
    keystore(backend);
    backend.resources.define('orders-db', {
      type: 'jdbc-pool',
      capacity: 2,
      settings: {
        kind: 'postgresql',
        host: 'db.internal',
        port: 5432,
        database: 'orders',
        username: 'reader',
        connectionsPerRun: 1,
        timeouts: { connectMs: 10000, statementMs: 300000, quotaWaitMs: 60000 },
        maxRows: 500,
        maxResponseBytes: 8388608,
      },
      secretAlias: 'orders-pass',
      lastCheck: { ok: true, failure: null, checkedAt: '2026-10-07T01:02:03Z' },
    });
  };

  test('starts from its settings, with the name and the type fixed, and its alias chosen', async () => {
    const { view } = await page({ seed: seeded });
    await loaded(view);
    button(card(view, 'orders-db'), 'Change').click();
    await tick();
    expect([...dialog(view).querySelectorAll('.fixed dd')].map((d) => d.textContent!.trim())).toEqual(['orders-db', 'Database pool (JDBC)']);
    expect(field(view, 'jdbc-host').value).toBe('db.internal');
    expect(field(view, 'jdbc-port').value).toBe('5432');
    expect(field(view, 'jdbc-statement-ms').value).toBe('300000');
    await vi.waitFor(() => expect(dialog(view).querySelector<HTMLSelectElement>('#resource-secret')!.value).toBe('orders-pass'));
    expect([...dialog(view).querySelector<HTMLSelectElement>('#resource-secret')!.options].map((o) => o.value)).not.toContain('');
  });

  test('sends the settings as a whole when they change, keeping those without a field; the last check is then forgotten', async () => {
    const { view } = await page({ seed: seeded });
    await loaded(view);
    button(card(view, 'orders-db'), 'Change').click();
    await tick();
    type(field(view, 'jdbc-host'), 'db2.internal');

    button(dialog(view), 'Save').click();

    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    expect(sent('PATCH')).toEqual({
      settings: {
        kind: 'postgresql',
        host: 'db2.internal',
        port: 5432,
        database: 'orders',
        username: 'reader',
        connectionsPerRun: 1,
        timeouts: { connectMs: 10000, statementMs: 300000, quotaWaitMs: 60000 },
        maxRows: 500,
        maxResponseBytes: 8388608,
      },
    });
    await vi.waitFor(() => expect(card(view, 'orders-db').querySelector('.last-check')!.textContent).toContain('Never checked'));
  });

  test('sends only the capacity when only it changes, and the alias when only it changes: the last check stays for a capacity', async () => {
    const { view } = await page({ seed: seeded });
    await loaded(view);
    button(card(view, 'orders-db'), 'Change').click();
    await tick();
    type(field(view, 'resource-capacity'), '3');
    button(dialog(view), 'Save').click();
    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    expect(sent('PATCH')).toEqual({ capacity: 3 });
    expect(card(view, 'orders-db').querySelector('.last-check')!.textContent).toContain('Passed');

    button(card(view, 'orders-db'), 'Change').click();
    await tick();
    await vi.waitFor(() => expect(dialog(view).querySelectorAll('#resource-secret option').length).toBeGreaterThan(1));
    choose(dialog(view).querySelector('#resource-secret')!, 'llm-key');
    button(dialog(view), 'Save').click();
    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    expect(sent('PATCH')).toEqual({ secretAlias: 'llm-key' });
  });

  test('says there is nothing to change when the fields are as they were', async () => {
    const { view } = await page({ seed: seeded });
    await loaded(view);
    button(card(view, 'orders-db'), 'Change').click();
    await tick();
    button(dialog(view), 'Save').click();
    await tick();
    expect(dialog(view).querySelector('.rl-field-error')!.textContent).toBe('There is nothing to change.');
    expect(sent('PATCH')).toBeUndefined();
  });
});

describe('the use of a resource of its own type, on its card', () => {
  const used = (backend: TestApp['engine']['backend']) => {
    backend.resources.define('orders-db', {
      type: 'jdbc-pool',
      capacity: 2,
      settings: { kind: 'postgresql', host: 'db', port: 5432, database: 'orders', username: 'reader', connectionsPerRun: 2 },
      usage: { activeConnections: 1 },
    });
    backend.resources.define('llm', {
      type: 'openai-compatible',
      capacity: 1,
      settings: { baseUrl: 'http://llm/v1', requestsPerRun: 1 },
      usage: { inFlightRequests: 0 },
    });
    backend.defineResource('printer');
  };

  test('the connections that runs hold of a jdbc-pool and the requests in flight to a service, out of their limit; nothing for a counter', async () => {
    const { view } = await page({ seed: used });
    await loaded(view);
    expect(card(view, 'orders-db').querySelector('.type-usage')!.textContent!.trim()).toBe('Connections in use: 1 of 4');
    expect(card(view, 'llm').querySelector('.type-usage')!.textContent!.trim()).toBe('Requests in flight: 0 of 1');
    expect(card(view, 'printer').querySelector('.type-usage')).toBeNull();
  });

  test('is read again with the page, and no check is made for it', async () => {
    const { view, clock } = await page({ seed: used });
    await loaded(view);
    app.engine.backend.resources.setUsage('llm', { inFlightRequests: 1 });
    clock.advance(3000);
    await vi.waitFor(() => expect(card(view, 'llm').querySelector('.type-usage')!.textContent!.trim()).toBe('Requests in flight: 1 of 1'));
    expect(app.engine.log.some((entry) => entry.path.includes('/check'))).toBe(false);
  });

  test('in zh-TW', async () => {
    const { view } = await page({ languages: ['zh-TW'], seed: used });
    await loaded(view);
    expect(card(view, 'orders-db').querySelector('.type-usage')!.textContent!.trim()).toBe('使用中的連線：1 / 4');
    expect(card(view, 'llm').querySelector('.type-usage')!.textContent!.trim()).toBe('進行中的請求：0 / 1');
  });
});

describe('the forms in zh-TW', () => {
  test('speak the language of the screen', async () => {
    const { view } = await page({ languages: ['zh-TW'] });
    await loaded(view);
    button(view, '定義資源').click();
    await tick();
    choose(dialog(view).querySelector('#resource-type')!, 'jdbc-pool');
    await tick();
    expect(dialog(view).querySelector('.pool-size')!.textContent).toContain('連線池大小');
    choose(dialog(view).querySelector('#resource-type')!, 'openai-compatible');
    await tick();
    expect(dialog(view).querySelector('#openai-parameters')!.textContent).toContain('鎖定');
    choose(dialog(view).querySelector('#resource-type')!, 'file');
    await tick();
    expect(dialog(view).textContent).toContain('資源根目錄');
  });
});

describe('the certificates of a resource (WI-52)', () => {
  const certificate = (subject: string, daysLeft: number, expiry = 'valid') => ({
    subject,
    notAfter: '2027-10-08T00:00:00Z',
    daysLeft,
    fingerprint: 'AB:CD:' + '00:'.repeat(29) + 'EF',
    expiry,
  });
  const withCertificates = (backend: TestApp['engine']['backend']) =>
    backend.secrets.configure([
      { alias: 'orders-pass', type: 'secret', status: 'found', fingerprint: 'a' },
      { alias: 'corporate-ca', type: 'trusted_certificate', status: 'found', fingerprint: 'd', certificates: [certificate('CN=Corporate CA', 300)] },
      { alias: 'backup-ca', type: 'trusted_certificate', status: 'found', fingerprint: 'e', certificates: [certificate('CN=Backup CA', 12, 'expiring')] },
      {
        alias: 'app-client',
        type: 'private_key',
        status: 'found',
        fingerprint: 'f',
        certificates: [certificate('CN=app-client', 20, 'expiring'), certificate('CN=Corporate CA', 300)],
      },
    ]);
  const trustBoxes = (view: HTMLElement) =>
    [...dialog(view).querySelectorAll<HTMLInputElement>('#resource-certificates input[type="checkbox"]')];
  const clientSelect = (view: HTMLElement) => dialog(view).querySelector<HTMLSelectElement>('#resource-client-cert')!;

  test('are chosen among the keystore\'s by kind: trusted certificates to tick, a private key to choose, nothing of a secret', async () => {
    const { view } = await page({ seed: withCertificates });
    await loaded(view);
    await defineForm(view, 'jdbc-pool', 'orders-db');
    await vi.waitFor(() => expect(trustBoxes(view)).toHaveLength(2));

    expect(trustBoxes(view).map((box) => box.value)).toEqual(['backup-ca', 'corporate-ca']);
    expect(dialog(view).querySelector('#resource-certificates')!.textContent).toContain('CN=Backup CA');
    expect(dialog(view).querySelector('#resource-certificates')!.textContent).toContain('12');
    expect([...clientSelect(view).options].map((o) => o.value)).toEqual(['', 'app-client']);
    expect(dialog(view).querySelector('#resource-certificates')!.textContent).toContain('verify-full');
  });

  test('are sent in the settings by alias, and the card is defined with them', async () => {
    const { view } = await page({ seed: withCertificates });
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');
    type(field(view, 'openai-base-url'), 'https://llm.internal/v1');
    await vi.waitFor(() => expect(trustBoxes(view)).toHaveLength(2));
    const corporate = trustBoxes(view).find((box) => box.value === 'corporate-ca')!;
    corporate.click();
    choose(clientSelect(view), 'app-client');

    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(cards(view)).toHaveLength(1));
    expect(sent('POST').settings).toMatchObject({ trustAliases: ['corporate-ca'], clientCertAlias: 'app-client' });
    expect(app.engine.backend.resources.usersOf('corporate-ca')).toEqual(['llm']);
  });

  test('of an existing resource start as they are, one the keystore lost included, and can be taken away', async () => {
    const { view } = await page({
      seed: (backend) => {
        withCertificates(backend);
        backend.resources.define('orders-db', {
          type: 'jdbc-pool',
          settings: { kind: 'postgresql', host: 'db', port: 5432, database: 'orders', username: 'reader', trustAliases: ['corporate-ca', 'gone-ca'], clientCertAlias: 'app-client' },
        });
      },
    });
    await loaded(view);
    button(card(view, 'orders-db'), 'Change').click();
    await vi.waitFor(() => expect(trustBoxes(view).length).toBe(3));
    expect(trustBoxes(view).filter((box) => box.checked).map((box) => box.value)).toEqual(['corporate-ca', 'gone-ca']);
    expect(dialog(view).querySelector('#resource-certificates')!.textContent).toContain('gone-ca');
    expect(clientSelect(view).value).toBe('app-client');

    trustBoxes(view).find((box) => box.value === 'gone-ca')!.click();
    choose(clientSelect(view), '');
    button(dialog(view), 'Save').click();

    await vi.waitFor(() => expect(sent('PATCH')).toBeDefined());
    expect(sent('PATCH').settings.trustAliases).toEqual(['corporate-ca']);
    expect(sent('PATCH').settings).not.toHaveProperty('clientCertAlias');
  });

  test('say at the field that the Engine refused an alias of another kind', async () => {
    const { view } = await page({ seed: withCertificates });
    await loaded(view);
    await defineForm(view, 'jdbc-pool', 'orders-db');
    type(field(view, 'jdbc-host'), 'db');
    type(field(view, 'jdbc-database'), 'orders');
    type(field(view, 'jdbc-username'), 'reader');
    await vi.waitFor(() => expect(trustBoxes(view)).toHaveLength(2));
    // A reload made `corporate-ca` a secret while the form was open.
    app.engine.backend.secrets.writeFile({ entries: [{ alias: 'corporate-ca', type: 'secret', status: 'found', fingerprint: 'z' }] });
    await app.context.api.reloadSecrets();
    trustBoxes(view).find((box) => box.value === 'corporate-ca')!.click();

    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(errorAt(view, 'resource-certificates')).not.toBeNull());
    expect(errorAt(view, 'resource-certificates')).toContain('kind');
  });

  test('say on the card in words why a check failed in TLS', async () => {
    const { view } = await page({
      seed: (backend) =>
        backend.resources.define('llm', {
          type: 'openai-compatible',
          settings: { baseUrl: 'https://llm.internal/v1', trustAliases: ['corporate-ca'] },
          lastCheck: { ok: false, failure: 'client_cert_rejected', checkedAt: '2026-10-08T00:00:00Z' },
        }),
    });
    await loaded(view);
    expect(card(view, 'llm').querySelector('.last-check')!.textContent).toContain('asked for a client certificate');
  });

  test('of the secrets list: each certificate with its subject, end, days left and fingerprint, and a warning near the end', async () => {
    const { view } = await page({ seed: withCertificates });
    const section = () => view.querySelector<HTMLElement>('section.secrets')!;
    await vi.waitFor(() => expect(section().querySelector('table')).not.toBeNull());

    const shown = section().querySelector<HTMLElement>('tr[data-alias="app-client"]')!;
    const certificates = [...section().querySelectorAll<HTMLElement>('[data-certificates-of="app-client"] li')];
    expect(certificates.map((li) => li.querySelector('.subject')!.textContent)).toEqual(['CN=app-client', 'CN=Corporate CA']);
    expect(certificates[0].querySelector('.fingerprint')!.textContent).toContain('AB:CD');
    expect(certificates[0].querySelector('.expiry')!.textContent).toContain('20');
    expect(certificates[0].classList.contains('expiring')).toBe(true);
    expect(shown).toBeDefined();
    expect(section().querySelector('[data-certificates-of="orders-pass"]')).toBeNull();
  });
});

describe('the choices of the forms are what the Engine tells (WI-55)', () => {
  /** Another Engine's catalog, which this Console was never built with. */
  const another = {
    types: [
      { type: 'counter' },
      { type: 'file' },
      {
        type: 'jdbc-pool',
        databases: [
          { kind: 'otherdb', properties: [{ name: 'Flavor', rule: 'oneOf', values: ['mild', 'hot'] }] },
          { kind: 'postgresql', properties: [{ name: 'ApplicationName', rule: 'text', maxLength: 64 }] },
        ],
      },
      {
        type: 'openai-compatible',
        endpoints: [
          { id: 'a.read', group: 'alpha', method: 'GET', path: '/a', request: 'none', response: 'json', streams: false, defaultEnabled: true, stateful: false },
          { id: 'a.drop', group: 'alpha', method: 'DELETE', path: '/a/{id}', request: 'none', response: 'json', streams: false, defaultEnabled: false, stateful: true },
          { id: 'b.make', group: 'beta', method: 'POST', path: '/b', request: 'multipart', response: 'binary', streams: false, defaultEnabled: false, stateful: false },
        ],
        requestParameters: [
          { name: 'top_q', kind: 'number', ceiling: true },
          { name: 'style', kind: 'object', ceiling: false },
        ],
      },
    ],
  };
  const checked = (view: HTMLElement) =>
    [...dialog(view).querySelectorAll<HTMLInputElement>('#openai-endpoints input[name="endpoint"]:checked')].map((i) => i.value);
  const entry = (view: HTMLElement, id: string) =>
    dialog(view).querySelector<HTMLInputElement>(`#openai-endpoints input[value="${id}"]`)!.closest('label')!;

  test('the endpoints are those it tells, by group, saying which a new resource has and which change what the service keeps', async () => {
    const { view } = await page({ catalog: { status: 200, body: another } });
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');

    expect([...dialog(view).querySelectorAll('#openai-endpoints .group-name')].map((g) => g.textContent!.trim())).toEqual(['alpha', 'beta']);
    expect(checked(view)).toEqual(['a.read']);
    expect(entry(view, 'a.read').querySelector('.default')!.textContent).toContain('new resource');
    expect(entry(view, 'a.read').querySelector('.stateful')).toBeNull();
    expect(entry(view, 'a.drop').querySelector('.stateful')!.textContent).toContain('keeps');
    expect(entry(view, 'a.drop').querySelector('.default')).toBeNull();
    expect(entry(view, 'b.make').textContent).toContain('POST /b');
    expect([...dialog(view).querySelectorAll('#openai-parameters tbody tr')].map((row) => row.querySelector('td')!.textContent!.trim())).toEqual([
      'top_q',
      'style',
    ]);
    expect(field(view, 'openai-max-top_q')).not.toBeNull();
    expect(field(view, 'openai-max-style')).toBeNull();

    type(field(view, 'openai-base-url'), 'http://llm/v1');
    entry(view, 'b.make').querySelector('input')!.click();
    type(field(view, 'openai-parameter-style'), '{"tone":"dry"}');
    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(sent('POST')).toBeDefined());
    expect(sent('POST').settings).toMatchObject({ endpoints: ['a.read', 'b.make'], defaults: { style: { tone: 'dry' } } });
  });

  test('a group of endpoints is enabled at once, and then disabled at once', async () => {
    const { view } = await page({ catalog: { status: 200, body: another } });
    await loaded(view);
    await defineForm(view, 'openai-compatible', 'llm');
    const alpha = () => dialog(view).querySelector<HTMLElement>('#openai-endpoints .group[data-group="alpha"]')!;

    button(alpha(), 'Enable all').click();
    await tick();
    expect(checked(view)).toEqual(['a.read', 'a.drop']);

    button(alpha(), 'Disable all').click();
    await tick();
    expect(checked(view)).toEqual([]);
  });

  test('the kinds of database are those it tells, and so are the properties each allows', async () => {
    const { view } = await page({ catalog: { status: 200, body: another } });
    await loaded(view);
    await defineForm(view, 'jdbc-pool', 'db');
    const kinds = dialog(view).querySelector<HTMLSelectElement>('#jdbc-kind')!;

    expect([...kinds.options].map((o) => [o.value, o.textContent!.trim()])).toEqual([
      ['otherdb', 'otherdb'],
      ['postgresql', 'postgresql'],
    ]);
    expect(dialog(view).querySelector('#jdbc-properties .allowed')!.textContent).toContain('Flavor (mild, hot)');
    choose(kinds, 'postgresql');
    await tick();
    expect(dialog(view).querySelector('#jdbc-properties .allowed')!.textContent).toContain('ApplicationName (64 characters at most)');
    expect(dialog(view).querySelector('#jdbc-properties .allowed')!.textContent).not.toContain('Flavor');
  });

  test('are read once, when the page is opened, and not each time the page reads the resources again', async () => {
    const { view, clock } = await page({ seed: (b) => b.defineResource('printer', true, 1) });
    await loaded(view);
    const asked = (path: string) => app.engine.received.filter((r) => r.method === 'GET' && r.path === path).length;
    const lists = asked('/api/v1/resources');

    clock.advance(3000);
    await vi.waitFor(() => expect(asked('/api/v1/resources')).toBeGreaterThan(lists));
    clock.advance(3000);
    await vi.waitFor(() => expect(asked('/api/v1/resources')).toBeGreaterThan(lists + 1));

    expect(asked('/api/v1/resource-types')).toBe(1);
  });

  const failing = { status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-55' } };
  const llm = (backend: TestApp['engine']['backend']) =>
    backend.resources.define('llm', {
      type: 'openai-compatible',
      capacity: 1,
      settings: { baseUrl: 'http://llm/v1', endpoints: ['chat.completions'] },
    });

  test('when they cannot be read, the page says so in words, and offers no database pool or service to define', async () => {
    const { view } = await page({ catalog: failing });
    await loaded(view);

    const notice = view.querySelector('.catalog-failed')!;
    expect(notice.textContent).toContain('resource types');
    expect(notice.textContent).toContain('e-55');
    button(view, 'Define a resource').click();
    await tick();
    const options = [...dialog(view).querySelectorAll<HTMLOptionElement>('#resource-type option')];
    expect(options.filter((o) => o.disabled).map((o) => o.value)).toEqual(['jdbc-pool', 'openai-compatible']);
    expect(options.find((o) => o.value === 'openai-compatible')!.textContent).toContain('not available');
  });

  test('when they cannot be read, a counter and a file are defined as ever', async () => {
    const { view } = await page({ catalog: failing });
    await loaded(view);
    await defineForm(view, 'file', 'report');
    type(field(view, 'resource-path'), 'out.txt');

    button(dialog(view), 'Define').click();

    await vi.waitFor(() => expect(cards(view)).toHaveLength(1));
    expect(sent('POST')).toEqual({ name: 'report', type: 'file', capacity: 1, settings: { path: 'out.txt' } });
  });

  test('when they cannot be read, a service or a database pool cannot be changed, and the dialog says why', async () => {
    const { view } = await page({ catalog: failing, seed: llm });
    await loaded(view);
    button(card(view, 'llm'), 'Change').click();
    await tick();

    expect(dialog(view).querySelector('.catalog-unavailable')!.textContent).toContain('resource types');
    expect(field(view, 'openai-base-url')).toBeNull();
    expect(button(dialog(view), 'Save').disabled).toBe(true);
  });

  test('making a response is told as changing what the service keeps, since the service keeps it (WI-58)', async () => {
    const { view } = await page({ languages: ['zh-TW'] });
    await loaded(view);
    button(view, '定義資源').click();
    await tick();
    choose(dialog(view).querySelector('#resource-type')!, 'openai-compatible');
    await tick();

    expect(entry(view, 'responses.create').querySelector('.stateful')!.textContent).toContain('會變更服務端保存的資料');
    expect(entry(view, 'responses.create').querySelector('.default')).toBeNull();
    expect(checked(view)).not.toContain('responses.create');
  });

  test('in zh-TW', async () => {
    const { view } = await page({ languages: ['zh-TW'], catalog: { status: 200, body: another } });
    await loaded(view);
    button(view, '定義資源').click();
    await tick();
    choose(dialog(view).querySelector('#resource-type')!, 'openai-compatible');
    await tick();

    expect(entry(view, 'a.drop').querySelector('.stateful')!.textContent).toContain('服務端');
    expect(entry(view, 'a.read').querySelector('.default')!.textContent).toContain('新資源');
    expect(button(dialog(view).querySelector('.group[data-group="alpha"]')!, '全部啟用')).toBeDefined();
  });
});

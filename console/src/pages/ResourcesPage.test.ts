import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ManualClock, ManualVisibility } from '../../test-support/manual-clock';
import ResourcesPage from './ResourcesPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };

const page = async (
  options: {
    languages?: string[];
    seed?: (backend: TestApp['engine']['backend']) => void;
  } = {},
) => {
  app = await createTestApp({ identity: root, languages: options.languages });
  app.engine.backend.autoRun = false;
  options.seed?.(app.engine.backend);
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

    type(dialog(view).querySelector('#resource-name')!, 'printer');
    button(dialog(view), 'Define').click();
    await vi.waitFor(() =>
      expect(dialog(view).querySelector('#resource-name')!.closest('.rl-field')!.querySelector('.rl-field-error')!.textContent).toBe(
        'A shared resource of this name exists already.',
      ),
    );

    type(dialog(view).querySelector('#resource-name')!, '-bad');
    button(dialog(view), 'Define').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('not valid'));
    expect(dialog(view)).not.toBeNull();
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
  });
});

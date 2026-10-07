import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import TriggerFormPage from './TriggerFormPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const HASH = 'c0ffee12'.padEnd(64, '3');

const page = async (
  options: {
    mode?: 'create' | 'edit';
    query?: string;
    languages?: string[];
    /** Others who uploaded the very same bytes as ada did. */
    sharedWith?: string[];
    seed?: (api: TestApp['context']['api']) => Promise<void>;
  } = {},
) => {
  app = await createTestApp({ identity: root, languages: options.languages });
  const pipelines = [
      {
        name: 'demo-slow',
        className: 'samples.slow.SlowPipeline',
        parameters: [
          { name: 'label', required: false, default: 'demo' },
          { name: 'steps', required: false, default: '30' },
        ],
      },
      {
        name: 'needs-token',
        className: 'x.NeedsToken',
        parameters: [{ name: 'token', required: true }],
      },
    ];
  for (const uploader of ['ada', ...(options.sharedWith ?? [])]) {
    app.engine.backend.seedArtifact(uploader, pipelines, { contentHash: HASH });
  }
  await options.seed?.(app.context.api);
  const mode = options.mode ?? 'create';
  app.context.router.navigate(`/triggers/${mode === 'create' ? 'new' : 'edit'}${options.query ?? ''}`);
  const view = app.mount(TriggerFormPage, { mode });
  return { view };
};
const ready = (view: HTMLElement) => vi.waitFor(() => expect(view.querySelector('form')).not.toBeNull());
const field = <T extends HTMLElement>(view: HTMLElement, id: string) => view.querySelector<T>(`#${id}`)!;
const type = (input: HTMLInputElement | HTMLSelectElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
};
const submit = (view: HTMLElement) => view.querySelector<HTMLButtonElement>('button.submit')!.click();
/** What the list of pipelines gives as the value of the choice: a version and a pipeline in it. */
const choice = (uploader: string, name: string) => JSON.stringify([HASH, uploader, name]);
const target = choice('ada', 'demo-slow');
const errorAt = (view: HTMLElement, id: string) =>
  view.querySelector(`#${id}`)!.closest('.rl-field')!.querySelector('.rl-field-error')?.textContent ?? null;

describe('making a trigger', () => {
  test('is a form for a cron trigger at first: name, kind, what it runs, the schedule, the parameters and whether it is on', async () => {
    const { view } = await page();
    await ready(view);

    expect(view.querySelector('h1')!.textContent).toBe('New trigger');
    expect(field<HTMLInputElement>(view, 'trigger-name').value).toBe('');
    expect(view.querySelector<HTMLInputElement>('input[name="kind"]:checked')!.value).toBe('cron');
    const options = [...field<HTMLSelectElement>(view, 'trigger-target').options].map((o) => o.textContent!.trim());
    expect(options).toContain('demo-slow · c0ffee1 · ada');
    expect(field(view, 'trigger-cron')).not.toBeNull();
    expect(field<HTMLInputElement>(view, 'trigger-zone').value).toBe('UTC');
    expect(field<HTMLInputElement>(view, 'trigger-enabled').checked).toBe(true);
  });

  test('has no schedule for a webhook', async () => {
    const { view } = await page();
    await ready(view);

    view.querySelector<HTMLInputElement>('input[name="kind"][value="webhook"]')!.click();
    await tick();

    expect(field(view, 'trigger-cron')).toBeNull();
    expect(field(view, 'trigger-zone')).toBeNull();
    expect(view.textContent).toContain('secret is shown once');
  });

  test('has the version and pipeline of the address chosen, and the parameters that pipeline declares, with their defaults', async () => {
    const { view } = await page({ query: `?contentHash=${HASH}&pipeline=demo-slow` });
    await ready(view);
    await tick();

    expect(field<HTMLSelectElement>(view, 'trigger-target').value).toBe(target);
    expect(field<HTMLInputElement>(view, 'param-steps').placeholder).toBe('30');
    expect(field(view, 'param-label')).not.toBeNull();
    expect(view.querySelector('[for="param-steps"]')!.textContent).toContain('optional');
  });

  test('says what a cron expression means as it is typed, and that there is no preview for what it cannot read', async () => {
    const { view } = await page();
    await ready(view);
    const cron = field<HTMLInputElement>(view, 'trigger-cron');

    expect(view.querySelector('.preview')!.textContent).toBe('');
    type(cron, '30 9 * * 1-5');
    await tick();
    expect(view.querySelector('.preview')!.textContent).toBe('At 09:30, Monday to Friday');

    type(cron, 'every day');
    await tick();
    expect(view.querySelector('.preview')!.textContent).toContain('no preview');
    expect(view.querySelector('.preview')!.textContent).toContain('five fields');
  });

  test('has expressions to start from, which fill the field', async () => {
    const { view } = await page();
    await ready(view);

    const preset = [...view.querySelectorAll<HTMLButtonElement>('.preset')].find((b) => b.textContent!.includes('Every 5 minutes'))!;
    preset.click();
    await tick();

    expect(field<HTMLInputElement>(view, 'trigger-cron').value).toBe('*/5 * * * *');
    expect(view.querySelector('.preview')!.textContent).toBe('Every 5 minutes');
  });

  test('makes a cron trigger with only the parameters that were given, and goes to its page', async () => {
    const { view } = await page({ query: `?contentHash=${HASH}&pipeline=demo-slow` });
    await ready(view);
    await tick();

    type(field(view, 'trigger-name'), 'nightly');
    type(field(view, 'trigger-cron'), '30 9 * * 1-5');
    type(field(view, 'trigger-zone'), 'Asia/Taipei');
    type(field(view, 'param-steps'), '3');
    submit(view);

    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect(app.context.router.search).toBe('?name=nightly');
    const made = await app.context.api.trigger('nightly');
    expect(made).toMatchObject({
      kind: 'cron',
      contentHash: HASH,
      pipeline: 'demo-slow',
      cron: '30 9 * * 1-5',
      timeZone: 'Asia/Taipei',
      parameters: { steps: '3' },
      enabled: true,
      createdBy: 'root',
    });
  });

  test('makes a trigger that is off when it is asked not to be on', async () => {
    const { view } = await page({ query: `?contentHash=${HASH}&pipeline=demo-slow` });
    await ready(view);
    await tick();
    type(field(view, 'trigger-name'), 'quiet');
    type(field(view, 'trigger-cron'), '* * * * *');
    field<HTMLInputElement>(view, 'trigger-enabled').click();
    submit(view);

    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect((await app.context.api.trigger('quiet')).enabled).toBe(false);
  });

  test('shows the secret of a webhook once, in a dialog that must be answered, and goes to the page of the trigger only after it', async () => {
    const { view } = await page({ query: `?contentHash=${HASH}&pipeline=demo-slow` });
    await ready(view);
    await tick();
    view.querySelector<HTMLInputElement>('input[name="kind"][value="webhook"]')!.click();
    await tick();
    type(field(view, 'trigger-name'), 'on.push');
    submit(view);

    await vi.waitFor(() => expect(view.querySelector('[role="dialog"]')).not.toBeNull());
    const secret = view.querySelector('.secret')!.textContent!;
    expect(secret.length).toBeGreaterThan(16);
    expect(app.context.router.path).toBe('/triggers/new');
    expect(location.href).not.toContain(secret);
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(secret);
    expect(view.querySelector('pre')!.textContent).toContain(secret);

    view.querySelector<HTMLInputElement>('[role="dialog"] input[type="checkbox"]')!.click();
    await tick();
    [...view.querySelectorAll<HTMLButtonElement>('footer button')].find((b) => b.textContent!.trim() === 'Done')!.click();

    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect(app.context.router.search).toBe('?name=on.push');
    expect(location.href).not.toContain(secret);
    expect(JSON.stringify(await app.context.api.trigger('on.push'))).not.toContain(secret);
  });

  test('asks for what is missing before it asks the Engine: a name, a version, and a parameter that is required', async () => {
    const { view } = await page();
    await ready(view);
    submit(view);
    await tick();
    expect(errorAt(view, 'trigger-name')).toBe('Enter a name.');
    expect(errorAt(view, 'trigger-target')).toBe('Choose a pipeline.');
    expect(errorAt(view, 'trigger-cron')).toBe('Enter a cron expression.');
    expect(app.engine.backend.triggers.triggers.size).toBe(0);

    type(field(view, 'trigger-target'), choice('ada', 'needs-token'));
    await tick();
    submit(view);
    await tick();
    expect(errorAt(view, 'param-token')).toBe('This parameter is required.');
  });

  test('says at the field what the Engine found wrong: the name, the expression, the time zone, a name that is taken, a parameter', async () => {
    const { view } = await page({
      query: `?contentHash=${HASH}&pipeline=demo-slow`,
      seed: async (api) => {
        await api.createTrigger({ name: 'taken', kind: 'cron', contentHash: HASH, pipeline: 'demo-slow', cron: '* * * * *' });
      },
    });
    await ready(view);
    await tick();
    type(field(view, 'trigger-name'), 'taken');
    type(field(view, 'trigger-cron'), '* * * * *');
    submit(view);
    await vi.waitFor(() => expect(errorAt(view, 'trigger-name')).toBe('A trigger of this name exists already.'));

    type(field(view, 'trigger-name'), '-bad');
    submit(view);
    await vi.waitFor(() => expect(errorAt(view, 'trigger-name')).toBe('The name is not valid.'));

    type(field(view, 'trigger-name'), 'fine');
    type(field(view, 'trigger-cron'), '99 * * * *');
    submit(view);
    await vi.waitFor(() => expect(errorAt(view, 'trigger-cron')).toBe('The cron expression is not valid.'));

    type(field(view, 'trigger-cron'), '* * * * *');
    type(field(view, 'trigger-zone'), 'Mars/Olympus');
    submit(view);
    await vi.waitFor(() => expect(errorAt(view, 'trigger-zone')).toBe('The time zone is not valid.'));
    expect(view.querySelector('[role="alert"]')).toBeNull();
  });

  test('says at the field of a parameter what the Engine found wrong with it', async () => {
    const { view } = await page({ query: `?contentHash=${HASH}&pipeline=needs-token` });
    await ready(view);
    await tick();
    type(field(view, 'trigger-name'), 'tok');
    type(field(view, 'trigger-cron'), '* * * * *');
    type(field(view, 'param-token'), 'x');
    // The Engine refuses a version that is gone since the form was opened.
    app.engine.faults.push({
      match: /POST \/api\/v1\/triggers/,
      status: 422,
      body: { error: 'invalid_parameters', message: 'm', problems: [{ name: 'token', problem: 'missing' }] },
      times: 1,
    });
    submit(view);

    await vi.waitFor(() => expect(errorAt(view, 'param-token')).toBe('This parameter is required.'));
  });

  test('says in words when the version or pipeline is gone, and what else the Engine refused', async () => {
    const { view } = await page({ query: `?contentHash=${HASH}&pipeline=demo-slow` });
    await ready(view);
    await tick();
    type(field(view, 'trigger-name'), 'x');
    type(field(view, 'trigger-cron'), '* * * * *');
    app.engine.faults.push({ match: /POST \/api\/v1\/triggers/, status: 404, body: { error: 'definition_not_found', message: 'm' }, times: 1 });

    submit(view);

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')!.textContent).toContain('No such pipeline version'));
  });

  test('cannot be sent twice while it is being sent', async () => {
    const { view } = await page({ query: `?contentHash=${HASH}&pipeline=demo-slow` });
    await ready(view);
    await tick();
    type(field(view, 'trigger-name'), 'once');
    type(field(view, 'trigger-cron'), '* * * * *');
    submit(view);
    submit(view);
    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect(app.engine.backend.triggers.triggers.size).toBe(1);
  });

  test('speaks the language of the screen', async () => {
    const { view } = await page({ languages: ['zh-TW'] });
    await ready(view);
    expect(view.querySelector('h1')!.textContent).toBe('建立 trigger');
    type(field(view, 'trigger-cron'), '30 9 * * *');
    await tick();
    expect(view.querySelector('.preview')!.textContent).toBe('每天 09:30');
  });
});

describe('changing a trigger', () => {
  const seed = async (api: TestApp['context']['api']) => {
    await api.createTrigger({
      name: 'nightly',
      kind: 'cron',
      contentHash: HASH,
      pipeline: 'demo-slow',
      cron: '30 9 * * 1-5',
      timeZone: 'Asia/Taipei',
      parameters: { steps: '3' },
      enabled: false,
    });
    await api.createTrigger({ name: 'hook', kind: 'webhook', contentHash: HASH, pipeline: 'demo-slow' });
  };

  test('starts from what the trigger is: the name and kind cannot be changed, the rest is filled in', async () => {
    const { view } = await page({ mode: 'edit', query: '?name=nightly', seed });
    await ready(view);
    await tick();

    expect(view.querySelector('h1')!.textContent).toBe('Edit trigger');
    expect(view.querySelector('.fixed-name')!.textContent).toBe('nightly');
    expect(field(view, 'trigger-name')).toBeNull();
    expect(view.querySelector('input[name="kind"]')).toBeNull();
    expect(view.querySelector('.fixed-kind')!.textContent).toContain('Cron');
    expect(field<HTMLSelectElement>(view, 'trigger-target').value).toBe(target);
    expect(field<HTMLInputElement>(view, 'trigger-cron').value).toBe('30 9 * * 1-5');
    expect(field<HTMLInputElement>(view, 'trigger-zone').value).toBe('Asia/Taipei');
    expect(field<HTMLInputElement>(view, 'param-steps').value).toBe('3');
    expect(field<HTMLInputElement>(view, 'trigger-enabled').checked).toBe(false);
    expect(view.querySelector('.preview')!.textContent).toBe('At 09:30, Monday to Friday');
  });

  test('saves the change and goes to the page of the trigger; what was not touched is as it was', async () => {
    const { view } = await page({ mode: 'edit', query: '?name=nightly', seed });
    await ready(view);
    await tick();

    type(field(view, 'trigger-cron'), '0 3 * * *');
    field<HTMLInputElement>(view, 'trigger-enabled').click();
    submit(view);

    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect(app.context.router.search).toBe('?name=nightly');
    expect(await app.context.api.trigger('nightly')).toMatchObject({
      cron: '0 3 * * *',
      timeZone: 'Asia/Taipei',
      parameters: { steps: '3' },
      enabled: true,
      createdBy: 'root',
    });
  });

  test('says at the field when the change is refused, and changes nothing', async () => {
    const { view } = await page({ mode: 'edit', query: '?name=nightly', seed });
    await ready(view);
    await tick();

    type(field(view, 'trigger-cron'), '99 99 * * *');
    submit(view);

    await vi.waitFor(() => expect(errorAt(view, 'trigger-cron')).toBe('The cron expression is not valid.'));
    expect((await app.context.api.trigger('nightly')).cron).toBe('30 9 * * 1-5');
  });

  test('a webhook has no schedule to change, and its secret is not here', async () => {
    const { view } = await page({ mode: 'edit', query: '?name=hook', seed });
    await ready(view);
    await tick();

    expect(field(view, 'trigger-cron')).toBeNull();
    expect(view.querySelector('.fixed-kind')!.textContent).toContain('Webhook');
    submit(view);
    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect(view.querySelector('[role="dialog"]')).toBeNull();
  });

  test('says when there is no such trigger', async () => {
    const { view } = await page({ mode: 'edit', query: '?name=nobody', seed });
    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    expect(view.textContent).toContain('No such trigger');
    expect(view.querySelector('form')).toBeNull();
  });
});

describe('the same content uploaded by two people', () => {
  test('a trigger is made for the version the address names, and the trigger says whose', async () => {
    const { view } = await page({
      sharedWith: ['bob'],
      query: `?contentHash=${HASH}&pipeline=demo-slow&uploader=bob`,
    });
    await ready(view);
    await tick();
    expect(field<HTMLSelectElement>(view, 'trigger-target').value).toBe(choice('bob', 'demo-slow'));

    type(field(view, 'trigger-name'), 'bobs');
    type(field(view, 'trigger-cron'), '* * * * *');
    submit(view);

    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect(await app.context.api.trigger('bobs')).toMatchObject({ contentHash: HASH, uploader: 'bob' });
  });

  test('an address that does not say whose chooses nobody, says so, and makes nothing', async () => {
    const { view } = await page({
      sharedWith: ['bob'],
      query: `?contentHash=${HASH}&pipeline=demo-slow`,
    });
    await ready(view);
    await tick();

    expect(field<HTMLSelectElement>(view, 'trigger-target').value).toBe('');
    expect(view.textContent).toContain('Choose whose version');
    type(field(view, 'trigger-name'), 'nobody');
    type(field(view, 'trigger-cron'), '* * * * *');
    submit(view);
    await vi.waitFor(() => expect(errorAt(view, 'trigger-target')).not.toBeNull());
    expect(await app.context.api.triggers()).toEqual([]);
  });

  test('a trigger is changed in what it runs of the version it has, and moves to the other only when chosen', async () => {
    const seed = async (api: TestApp['context']['api']) => {
      await api.createTrigger({
        name: 'bobs',
        kind: 'cron',
        contentHash: HASH,
        uploader: 'bob',
        pipeline: 'demo-slow',
        cron: '* * * * *',
      });
    };
    const { view } = await page({ mode: 'edit', query: '?name=bobs', sharedWith: ['bob'], seed });
    await ready(view);
    await tick();
    expect(field<HTMLSelectElement>(view, 'trigger-target').value).toBe(choice('bob', 'demo-slow'));

    type(field(view, 'trigger-cron'), '0 3 * * *');
    submit(view);
    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect(await app.context.api.trigger('bobs')).toMatchObject({ uploader: 'bob', cron: '0 3 * * *' });
  });

  test('moves to the other uploader\'s version when that is chosen', async () => {
    const seed = async (api: TestApp['context']['api']) => {
      await api.createTrigger({
        name: 'bobs',
        kind: 'cron',
        contentHash: HASH,
        uploader: 'bob',
        pipeline: 'demo-slow',
        cron: '* * * * *',
      });
    };
    const { view } = await page({ mode: 'edit', query: '?name=bobs', sharedWith: ['bob'], seed });
    await ready(view);
    await tick();

    type(field(view, 'trigger-target'), choice('ada', 'demo-slow'));
    submit(view);

    await vi.waitFor(() => expect(app.context.router.path).toBe('/triggers/detail'));
    expect((await app.context.api.trigger('bobs')).uploader).toBe('ada');
  });
});

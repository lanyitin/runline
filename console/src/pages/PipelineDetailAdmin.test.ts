import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import type { FakeRun } from '../../test-support/fake-backend';
import PipelineDetailPage from './PipelineDetailPage.svelte';

// What an admin can do on the page of a pipeline, besides reading it: allow it to run although it is
// UNSAFE (for this version only), and delete the version.

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const ada = { name: 'ada', role: 'developer' as const };
const HASH = 'c0ffee12'.padEnd(64, '3');

const page = async (
  options: {
    identity?: { name: string; role: 'admin' | 'developer' };
    unsafe?: boolean;
    languages?: string[];
    query?: string;
    seed?: (backend: TestApp['engine']['backend']) => void;
  } = {},
) => {
  app = await createTestApp({ identity: options.identity ?? root, languages: options.languages });
  app.engine.backend.seedArtifact(
    'ada',
    [
      options.unsafe === false
        ? { name: 'order-sync', className: 'com.acme.OrderSync' }
        : {
            name: 'order-sync',
            className: 'com.acme.OrderSync',
            reasons: [
              { kind: 'UNRESTRICTED_ACCESS', category: 'NETWORK' },
              { kind: 'NOT_ALLOW_LISTED', className: 'java.io.File', path: ['com.acme.OrderSync', 'java.io.File'] },
            ],
          },
      { name: 'order-other', className: 'com.acme.Other' },
    ],
    { contentHash: HASH },
  );
  options.seed?.(app.engine.backend);
  app.context.router.navigate(options.query ?? '/pipelines/x?pipeline=order-sync');
  return app.mount(PipelineDetailPage, { contentHash: HASH });
};
const ready = (view: HTMLElement) => vi.waitFor(() => expect(view.querySelector('h1')).not.toBeNull());
const admin = (view: HTMLElement) => view.querySelector<HTMLElement>('section.admin')!;
const button = (root: ParentNode, label: string) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === label)!;
const dialog = (view: HTMLElement) => view.querySelector<HTMLElement>('[role="dialog"]')!;
const allowed = () => app.engine.backend.definitionOf(HASH, 'ada', 'order-sync')!.allowUnsafeExecution;

describe('the page of a pipeline, for a developer', () => {
  test('has nothing of the admin: no switch, no deleting', async () => {
    const view = await page({ identity: ada });
    await ready(view);
    expect(view.querySelector('section.admin')).toBeNull();
    expect(button(view, 'Delete this version')).toBeUndefined();
  });
});

describe('unsafe execution', () => {
  test('is a switch for this pipeline of this version, which says what it does and that a new version does not inherit it', async () => {
    const view = await page();
    await ready(view);

    const section = admin(view);
    const toggle = section.querySelector<HTMLInputElement>('input[role="switch"]')!;
    expect(toggle.checked).toBe(false);
    expect(toggle.getAttribute('aria-label')).toBe('Allow unsafe execution');
    expect(section.querySelector('.state')!.textContent).toBe('Not allowed');
    expect(section.textContent).toContain('this version only');
    expect(section.textContent).toContain('not inherited');
  });

  test('is turned on after a confirmation that says what follows, with the reasons it is UNSAFE; Cancel leaves it off', async () => {
    const view = await page();
    await ready(view);
    const toggle = admin(view).querySelector<HTMLInputElement>('input[role="switch"]')!;

    toggle.click();
    await tick();
    const ask = dialog(view);
    expect(ask.querySelector('h2')!.textContent).toBe('Allow order-sync to run although it is UNSAFE?');
    expect(ask.textContent).toContain('not checked');
    expect(ask.textContent).toContain('java.io.File');
    expect(ask.textContent).toContain('2 reasons');
    button(ask, 'Cancel').click();
    await tick();
    expect(dialog(view)).toBeNull();
    expect(allowed()).toBe(false);
    expect(toggle.checked).toBe(false);

    toggle.click();
    await tick();
    button(dialog(view), 'Allow unsafe execution').click();
    await vi.waitFor(() => expect(allowed()).toBe(true));
    await vi.waitFor(() => expect(admin(view).querySelector('.state')!.textContent).toBe('Allowed'));
    expect(dialog(view)).toBeNull();
    expect(admin(view).querySelector('.set-by')!.textContent).toContain('root');
    expect(view.textContent).toContain('has allowed this pipeline to run although it is UNSAFE');
  });

  test('is turned off at once, without a question', async () => {
    const view = await page({ seed: (b) => b.allowUnsafe(HASH, 'order-sync') });
    await ready(view);
    const toggle = admin(view).querySelector<HTMLInputElement>('input[role="switch"]')!;
    expect(toggle.checked).toBe(true);

    toggle.click();

    await vi.waitFor(() => expect(allowed()).toBe(false));
    await vi.waitFor(() => expect(admin(view).querySelector('.state')!.textContent).toBe('Not allowed'));
    expect(dialog(view)).toBeNull();
  });

  test('is a run that is made then: a developer can create it after the admin has allowed it', async () => {
    const view = await page();
    await ready(view);
    admin(view).querySelector<HTMLInputElement>('input[role="switch"]')!.click();
    await tick();
    button(dialog(view), 'Allow unsafe execution').click();
    await vi.waitFor(() => expect(allowed()).toBe(true));

    const run = await app.context.api.createRun({ contentHash: HASH, pipeline: 'order-sync' });
    expect(run.unsafeExecution).toMatchObject({ setBy: 'root' });
  });

  test('says in the dialog what the Engine refused, and leaves it off', async () => {
    const view = await page();
    await ready(view);
    const toggle = admin(view).querySelector<HTMLInputElement>('input[role="switch"]')!;
    app.engine.faults.push({ match: /unsafe-execution/, status: 404, body: { error: 'definition_not_found', message: 'x' }, times: 1 });

    toggle.click();
    await tick();
    button(dialog(view), 'Allow unsafe execution').click();

    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('No such pipeline version'));
    expect(allowed()).toBe(false);
    expect(toggle.checked).toBe(false);
  });

  test('says when turning it off was refused, and keeps it on', async () => {
    const view = await page({ seed: (b) => b.allowUnsafe(HASH, 'order-sync') });
    await ready(view);
    const toggle = admin(view).querySelector<HTMLInputElement>('input[role="switch"]')!;
    app.engine.faults.push({ match: /unsafe-execution/, status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-9' }, times: 1 });

    toggle.click();

    await vi.waitFor(() => expect(admin(view).querySelector('[role="alert"]')).not.toBeNull());
    expect(toggle.checked).toBe(true);
  });

  test('says that it has no effect for a pipeline that is SAFE, for as long as it is', async () => {
    const view = await page({ unsafe: false });
    await ready(view);
    expect(admin(view).textContent).toContain('is SAFE');
  });
});

describe('deleting the version', () => {
  const open = async (view: HTMLElement) => {
    await ready(view);
    button(admin(view), 'Delete this version').click();
    await tick();
  };
  /** The dialog has what the preview said. */
  const shown = (view: HTMLElement) =>
    vi.waitFor(() => expect(dialog(view).querySelector('.impact, .blocked')).not.toBeNull());
  const confirm = (view: HTMLElement) => button(dialog(view), 'Delete the version');
  const seedRuns = (b: TestApp['engine']['backend'], ...states: FakeRun['state'][]) =>
    states.forEach((state, i) =>
      b.seedRun('ada', {
        contentHash: HASH,
        pipeline: 'order-sync',
        state,
        createdAt: `2026-0${i + 1}-05T10:00:00Z`,
      }),
    );

  test('asks first, and says what goes: the version and each pipeline in it, and that it cannot be undone', async () => {
    const view = await page();
    await open(view);
    await shown(view);

    const ask = dialog(view);
    expect(ask.querySelector('h2')!.textContent).toBe('Delete version c0ffee1?');
    expect(ask.textContent).toContain('order-sync');
    expect(ask.textContent).toContain('order-other');
    expect(ask.textContent).toContain('cannot be undone');
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
  });

  test('cannot be confirmed while the impact is being read', async () => {
    const view = await page();
    await open(view);

    expect(dialog(view).textContent).toContain('Loading');
    expect(confirm(view).disabled).toBe(true);
    await shown(view);
    expect(confirm(view).disabled).toBe(false);
  });

  test('says when the impact could not be read, and cannot be confirmed; Cancel still closes it', async () => {
    const view = await page();
    app.engine.faults.push({ match: /deletion-impact/, status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-1' }, times: 1 });
    await open(view);

    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')).not.toBeNull());
    expect(confirm(view).disabled).toBe(true);
    expect(button(dialog(view), 'Cancel').disabled).toBe(false);
    button(dialog(view), 'Cancel').click();
    await tick();
    expect(dialog(view)).toBeNull();
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
  });

  test('shows what the preview counted: the runs by state, when they were made, the log lines, and that the jar goes', async () => {
    const view = await page({
      seed: (b) => {
        seedRuns(b, 'SUCCEEDED', 'SUCCEEDED', 'FAILED');
        b.write(b.runs.find((r) => r.contentHash === HASH)!.runId, 'one', 'two');
      },
    });
    await open(view);
    await shown(view);

    const impact = dialog(view).querySelector('.impact')!;
    expect(impact.textContent).toContain('3 runs');
    expect(impact.textContent).toContain('SUCCEEDED');
    expect(impact.textContent).toContain('2 log entries');
    expect(impact.querySelector('.states')!.textContent).toMatch(/SUCCEEDED\D*2/);
    expect(impact.querySelector('.states')!.textContent).toMatch(/FAILED\D*1/);
    expect(impact.querySelectorAll('time')).toHaveLength(2);
    expect(impact.querySelectorAll('time')[0].getAttribute('datetime')).toBe('2026-01-05T10:00:00Z');
    expect(impact.querySelectorAll('time')[1].getAttribute('datetime')).toBe('2026-03-05T10:00:00Z');
    expect(dialog(view).textContent).toContain('and their log entries will be deleted with the version');
    expect(dialog(view).textContent).toContain('jar is removed');
  });

  test('says that the jar stays when another uploader has the same content', async () => {
    const view = await page({
      seed: (b) =>
        void b.seedArtifact('bob', [{ name: 'order-sync', className: 'com.acme.OrderSync' }], { contentHash: HASH }),
      query: '/pipelines/x?pipeline=order-sync&uploader=ada',
    });
    await open(view);
    await shown(view);
    expect(dialog(view).textContent).toContain('jar stays');
  });

  test('deletes the ended runs with the version, sending the number it showed', async () => {
    const view = await page({ seed: (b) => seedRuns(b, 'SUCCEEDED', 'FAILED') });
    await open(view);
    await shown(view);

    confirm(view).click();

    await vi.waitFor(() => expect(app.context.router.path).toBe('/pipelines'));
    expect(app.engine.backend.version(HASH, 'ada')).toBeUndefined();
    expect(app.engine.backend.runs.filter((r) => r.contentHash === HASH)).toEqual([]);
  });

  test('says that only the version is deleted when it has no runs, and deletes it', async () => {
    const view = await page();
    await open(view);
    await shown(view);

    expect(dialog(view).textContent).toContain('no runs');
    expect(dialog(view).textContent).toContain('Only the version');
    confirm(view).click();

    await vi.waitFor(() => expect(app.context.router.path).toBe('/pipelines'));
    expect(app.engine.backend.version(HASH, 'ada')).toBeUndefined();
  });

  test('cannot be confirmed twice while it is being done', async () => {
    const view = await page({ seed: (b) => seedRuns(b, 'SUCCEEDED') });
    await open(view);
    await shown(view);

    confirm(view).click();
    await tick();

    expect(confirm(view).disabled).toBe(true);
    await vi.waitFor(() => expect(app.context.router.path).toBe('/pipelines'));
  });

  test('does not delete when it is not confirmed', async () => {
    const view = await page({ seed: (b) => seedRuns(b, 'SUCCEEDED') });
    await open(view);
    await shown(view);
    button(dialog(view), 'Cancel').click();
    await tick();
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
    expect(app.engine.backend.runs.filter((r) => r.contentHash === HASH)).toHaveLength(1);
    expect(dialog(view)).toBeNull();
  });

  test('says why it cannot be deleted while a run has not ended, with how many, and cannot be confirmed', async () => {
    const view = await page({ seed: (b) => seedRuns(b, 'RUNNING', 'QUEUED', 'SUCCEEDED') });
    await open(view);
    await shown(view);

    const blocked = dialog(view).querySelector('.blocked')!;
    expect(blocked.textContent).toContain('2 runs have not ended');
    expect(confirm(view).disabled).toBe(true);
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
  });

  test('says why it cannot be deleted while a trigger refers to it, naming the trigger with a link, and cannot be confirmed', async () => {
    const view = await page({
      seed: (b) => void b.triggers.seed({ name: 'nightly', contentHash: HASH, pipeline: 'order-other' }),
    });
    await open(view);
    await shown(view);

    const link = dialog(view).querySelector('.blocked a')!;
    expect(link.textContent).toBe('nightly');
    expect(link.getAttribute('href')).toBe('/triggers/detail?name=nightly');
    expect(dialog(view).querySelector('.blocked')!.textContent).toContain('Delete the trigger');
    expect(confirm(view).disabled).toBe(true);
  });

  test('shows the new impact and does not delete when the runs changed after it was shown', async () => {
    const view = await page({ seed: (b) => seedRuns(b, 'SUCCEEDED') });
    await open(view);
    await shown(view);
    expect(dialog(view).querySelector('.impact')!.textContent).toContain('1 run');

    seedRuns(app.engine.backend, 'FAILED', 'FAILED');
    confirm(view).click();

    await vi.waitFor(() => expect(dialog(view).querySelector('.impact')!.textContent).toContain('3 runs'));
    expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('changed');
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
    expect(app.engine.backend.runs.filter((r) => r.contentHash === HASH)).toHaveLength(3);
    expect(app.context.router.path).not.toBe('/pipelines');

    confirm(view).click();
    await vi.waitFor(() => expect(app.context.router.path).toBe('/pipelines'));
    expect(app.engine.backend.version(HASH, 'ada')).toBeUndefined();
  });

  test('shows why when a run began after the impact was shown, and does not delete', async () => {
    const view = await page({ seed: (b) => seedRuns(b, 'SUCCEEDED') });
    await open(view);
    await shown(view);

    seedRuns(app.engine.backend, 'RUNNING');
    confirm(view).click();

    await vi.waitFor(() => expect(dialog(view).querySelector('.blocked')!.textContent).toContain('1 run has not ended'));
    expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('not ended');
    expect(confirm(view).disabled).toBe(true);
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
  });

  test('shows the trigger when one was bound after the impact was shown, and does not delete', async () => {
    const view = await page();
    await open(view);
    await shown(view);

    app.engine.backend.triggers.seed({ name: 'late', contentHash: HASH, pipeline: 'order-other' });
    confirm(view).click();

    await vi.waitFor(() => expect(dialog(view).querySelector('.blocked a')!.textContent).toBe('late'));
    expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('trigger');
    expect(confirm(view).disabled).toBe(true);
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
  });

  test('says any other refusal in the dialog, which stays open and can be confirmed again', async () => {
    const view = await page({ seed: (b) => seedRuns(b, 'SUCCEEDED') });
    await open(view);
    await shown(view);
    app.engine.faults.push({ match: /DELETE .*artifacts/, status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-2' }, times: 1 });

    confirm(view).click();

    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')).not.toBeNull());
    expect(dialog(view).querySelector('.impact')).not.toBeNull();
    expect(confirm(view).disabled).toBe(false);
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
  });

  test('shows a name that the server sent as text, never as markup', async () => {
    const view = await page({
      seed: (b) => void b.triggers.seed({ name: 'x<img src=x>', contentHash: HASH, pipeline: 'order-other' }),
    });
    await open(view);
    await shown(view);
    expect(dialog(view).querySelector('.blocked img')).toBeNull();
    expect(dialog(view).querySelector('.blocked')!.textContent).toContain('x<img src=x>');
  });
});

describe('a trigger for this version', () => {
  test('is a link that starts the form with this version and pipeline', async () => {
    const view = await page();
    await ready(view);
    const link = [...admin(view).querySelectorAll('a')].find((a) => a.textContent!.trim() === 'Bind a trigger')!;
    expect(link.getAttribute('href')).toBe(
      `/triggers/new?contentHash=${HASH}&pipeline=order-sync&uploader=ada`,
    );
  });
});

describe('the same content uploaded by two people', () => {
  const sharedWithBob = (b: TestApp['engine']['backend']) =>
    void b.seedArtifact(
      'bob',
      [
        {
          name: 'order-sync',
          className: 'com.acme.OrderSync',
          reasons: [{ kind: 'UNRESTRICTED_ACCESS', category: 'NETWORK' }],
        },
      ],
      { contentHash: HASH },
    );

  test('asks whose version when the address names none, with a link to each, and does not guess', async () => {
    const view = await page({ seed: sharedWithBob });

    await vi.waitFor(() => expect(view.querySelector('.choose-version')).not.toBeNull());
    const choice = view.querySelector('.choose-version')!;
    expect(choice.textContent).toContain('Choose whose version');
    expect([...choice.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual([
      `/pipelines/${HASH}?pipeline=order-sync&uploader=ada`,
      `/pipelines/${HASH}?pipeline=order-sync&uploader=bob`,
    ]);
    expect(view.querySelector('section.admin')).toBeNull();
    expect(view.querySelector('h1')).toBeNull();
  });

  test("shows the version the address names, and what is done to it is done to that version only", async () => {
    const view = await page({ seed: sharedWithBob, query: '/pipelines/x?pipeline=order-sync&uploader=bob' });
    await ready(view);
    expect(view.querySelector('dl.facts')!.textContent).toContain('bob');
    const toggle = admin(view).querySelector<HTMLInputElement>('input[role="switch"]')!;

    toggle.click();
    await tick();
    button(dialog(view), 'Allow unsafe execution').click();

    const of = (uploader: string) =>
      app.engine.backend.definitionOf(HASH, uploader, 'order-sync')!.allowUnsafeExecution;
    await vi.waitFor(() => expect(of('bob')).toBe(true));
    expect(of('ada')).toBe(false);
  });

  test('deleting takes the version shown and leaves the other uploader\'s', async () => {
    const view = await page({ seed: sharedWithBob, query: '/pipelines/x?pipeline=order-sync&uploader=bob' });
    await ready(view);
    button(admin(view), 'Delete this version').click();
    await vi.waitFor(() => expect(button(dialog(view), 'Delete the version').disabled).toBe(false));

    button(dialog(view), 'Delete the version').click();

    await vi.waitFor(() => expect(app.context.router.path).toBe('/pipelines'));
    expect(app.engine.backend.version(HASH, 'bob')).toBeUndefined();
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
  });

  test('a trigger for the version is bound to the uploader shown', async () => {
    const view = await page({ seed: sharedWithBob, query: '/pipelines/x?pipeline=order-sync&uploader=bob' });
    await ready(view);
    const link = [...admin(view).querySelectorAll('a')].find((a) => a.textContent!.trim() === 'Bind a trigger')!;
    expect(link.getAttribute('href')).toBe(
      `/triggers/new?contentHash=${HASH}&pipeline=order-sync&uploader=bob`,
    );
  });

  test('a trigger of the other uploader\'s version does not keep this one from being deleted, and is not named', async () => {
    const view = await page({
      seed: (b) => {
        sharedWithBob(b);
        b.triggers.seed({ name: 'adas', contentHash: HASH, uploader: 'ada', pipeline: 'order-other' });
      },
      query: '/pipelines/x?pipeline=order-sync&uploader=bob',
    });
    await ready(view);
    button(admin(view), 'Delete this version').click();
    await vi.waitFor(() => expect(button(dialog(view), 'Delete the version').disabled).toBe(false));

    button(dialog(view), 'Delete the version').click();

    await vi.waitFor(() => expect(app.context.router.path).toBe('/pipelines'));
    expect(app.engine.backend.version(HASH, 'bob')).toBeUndefined();
  });
});

describe('in zh-TW', () => {
  test('speaks the language of the screen', async () => {
    const view = await page({ languages: ['zh-TW'] });
    await ready(view);
    expect(admin(view).querySelector('.state')!.textContent).toBe('不允許');
    expect(button(admin(view), '刪除此版本')).toBeDefined();
  });

  test('says what deleting the version takes along, in the language of the screen', async () => {
    const view = await page({
      languages: ['zh-TW'],
      seed: (b) => b.seedRun('ada', { contentHash: HASH, pipeline: 'order-sync', state: 'SUCCEEDED' }),
    });
    await ready(view);
    button(admin(view), '刪除此版本').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('.impact')).not.toBeNull());
    expect(dialog(view).querySelector('.impact')!.textContent).toContain('1 個 run');
    expect(button(dialog(view), '刪除版本')).toBeDefined();
  });
});

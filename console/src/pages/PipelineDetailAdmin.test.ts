import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
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
  app.context.router.navigate('/pipelines/x?pipeline=order-sync');
  return app.mount(PipelineDetailPage, { contentHash: HASH });
};
const ready = (view: HTMLElement) => vi.waitFor(() => expect(view.querySelector('h1')).not.toBeNull());
const admin = (view: HTMLElement) => view.querySelector<HTMLElement>('section.admin')!;
const button = (root: ParentNode, label: string) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === label)!;
const dialog = (view: HTMLElement) => view.querySelector<HTMLElement>('[role="dialog"]')!;
const allowed = () => app.engine.backend.definitionOf(HASH, 'order-sync')!.allowUnsafeExecution;

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
  test('asks first, and says what goes: the version and each pipeline in it, and that it cannot be undone', async () => {
    const view = await page();
    await ready(view);

    button(admin(view), 'Delete this version').click();
    await tick();

    const ask = dialog(view);
    expect(ask.querySelector('h2')!.textContent).toBe('Delete version c0ffee1?');
    expect(ask.textContent).toContain('order-sync');
    expect(ask.textContent).toContain('order-other');
    expect(ask.textContent).toContain('cannot be undone');
    expect(app.engine.backend.artifacts.has(HASH)).toBe(true);
  });

  test('deletes it when confirmed, and shows the list of pipelines', async () => {
    const view = await page();
    await ready(view);
    button(admin(view), 'Delete this version').click();
    await tick();

    button(dialog(view), 'Delete the version').click();

    await vi.waitFor(() => expect(app.context.router.path).toBe('/pipelines'));
    expect(app.engine.backend.artifacts.has(HASH)).toBe(false);
  });

  test('does not delete when it is not confirmed', async () => {
    const view = await page();
    await ready(view);
    button(admin(view), 'Delete this version').click();
    await tick();
    button(dialog(view), 'Cancel').click();
    await tick();
    expect(app.engine.backend.artifacts.has(HASH)).toBe(true);
    expect(dialog(view)).toBeNull();
  });

  test('says why it cannot be deleted while a run refers to it, with the runs, and leaves it', async () => {
    const view = await page({ seed: (b) => b.seedRun('ada', { contentHash: HASH, pipeline: 'order-sync' }) });
    await ready(view);
    button(admin(view), 'Delete this version').click();
    await tick();

    button(dialog(view), 'Delete the version').click();

    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('in use'));
    await vi.waitFor(() => expect(dialog(view).querySelector('.in-use')).not.toBeNull());
    expect(dialog(view).querySelector('.in-use')!.textContent).toContain('1 run');
    expect(dialog(view).querySelector('.in-use')!.textContent).toContain('retention');
    expect(app.engine.backend.artifacts.has(HASH)).toBe(true);
    expect(app.context.router.path).not.toBe('/pipelines');
  });

  test('says why it cannot be deleted while a trigger refers to it, naming the trigger with a link', async () => {
    const view = await page({
      seed: (b) => void b.triggers.seed({ name: 'nightly', contentHash: HASH, pipeline: 'order-other' }),
    });
    await ready(view);
    button(admin(view), 'Delete this version').click();
    await tick();

    button(dialog(view), 'Delete the version').click();

    await vi.waitFor(() => expect(dialog(view).querySelector('.in-use')).not.toBeNull());
    const link = dialog(view).querySelector('.in-use a')!;
    expect(link.textContent).toBe('nightly');
    expect(link.getAttribute('href')).toBe('/triggers/detail?name=nightly');
    expect(dialog(view).querySelector('.in-use')!.textContent).toContain('Delete the trigger');
  });
});

describe('a trigger for this version', () => {
  test('is a link that starts the form with this version and pipeline', async () => {
    const view = await page();
    await ready(view);
    const link = [...admin(view).querySelectorAll('a')].find((a) => a.textContent!.trim() === 'Bind a trigger')!;
    expect(link.getAttribute('href')).toBe(`/triggers/new?contentHash=${HASH}&pipeline=order-sync`);
  });
});

describe('in zh-TW', () => {
  test('speaks the language of the screen', async () => {
    const view = await page({ languages: ['zh-TW'] });
    await ready(view);
    expect(admin(view).querySelector('.state')!.textContent).toBe('不允許');
    expect(button(admin(view), '刪除此版本')).toBeDefined();
  });
});

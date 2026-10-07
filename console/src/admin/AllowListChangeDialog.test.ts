import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import AllowListChangeDialog, { type Operation } from './AllowListChangeDialog.svelte';
import type { AllowEntry, AllowListChange } from '../api/admin-model';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const HASH = 'c0ffee12'.padEnd(64, '3');
const OTHER = 'beef0000'.padEnd(64, '4');

const open = async (
  operation: (entries: AllowEntry[]) => Operation,
  options: {
    languages?: string[];
    seed?: (backend: TestApp['engine']['backend']) => void;
  } = {},
) => {
  app = await createTestApp({ identity: root, languages: options.languages });
  // Two pipelines that print, in two versions; one of them is allowed to run although UNSAFE once it is.
  app.engine.backend.seedArtifact(
    'ada',
    [{ name: 'demo-slow', className: 'samples.slow.SlowPipeline', references: ['java.io.PrintStream'] }],
    { contentHash: HASH },
  );
  app.engine.backend.seedArtifact(
    'ada',
    [{ name: 'demo-failing', className: 'samples.failing.FailingPipeline', references: ['java.io.PrintStream'] }],
    { contentHash: OTHER },
  );
  options.seed?.(app.engine.backend);
  const entries = (await app.context.api.allowList()).entries;
  const finished = vi.fn<(change: AllowListChange | null) => void>();
  const view = app.mount(AllowListChangeDialog, { operation: operation(entries), onfinished: finished });
  return { view, finished, entries };
};
const dialog = (view: HTMLElement) => view.querySelector<HTMLElement>('[role="dialog"]')!;
const button = (view: HTMLElement, label: string) =>
  [...dialog(view).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === label)!;
const step = (view: HTMLElement) => dialog(view).querySelector('[data-step]')!.getAttribute('data-step');
const waitFor = (view: HTMLElement, wanted: string) =>
  vi.waitFor(() => expect(step(view)).toBe(wanted));
const type = (input: HTMLInputElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
};
const stat = (view: HTMLElement, name: string) => dialog(view).querySelector(`.stat.${name} .n`)!.textContent;
const version = () => app.engine.backend.allowList.current.version;
const names = () => app.engine.backend.allowList.entries.map((e) => e.name);

describe('adding an entry', () => {
  test('is a form first, which says that it is a decision to trust', async () => {
    const { view } = await open(() => ({ kind: 'add' }));

    expect(step(view)).toBe('form');
    expect(dialog(view).querySelector('h2')!.textContent).toBe('Add an allow-list entry');
    expect(dialog(view).textContent).toContain('decision to trust');
    expect(dialog(view).querySelector<HTMLInputElement>('input[name="entry-kind"]:checked')!.value).toBe('package');
    expect(dialog(view).querySelector('#entry-exact')).not.toBeNull();
  });

  test('has no "this package only" for a class', async () => {
    const { view } = await open(() => ({ kind: 'add' }));
    dialog(view).querySelector<HTMLInputElement>('input[name="entry-kind"][value="class"]')!.click();
    await tick();
    expect(dialog(view).querySelector('#entry-exact')).toBeNull();
  });

  test('asks for a name before it asks the Engine', async () => {
    const { view } = await open(() => ({ kind: 'add' }));
    button(view, 'Preview the effect').click();
    await tick();
    expect(dialog(view).querySelector('.rl-field-error')!.textContent).toBe('Enter a name.');
    expect(step(view)).toBe('form');
  });

  test('shows the effect before anything is changed: what would become UNSAFE or SAFE, and nothing is added', async () => {
    const { view } = await open(() => ({ kind: 'add' }), {
      seed: (backend) => {
        backend.allowList.entries = backend.allowList.entries.filter((e) => e.name !== 'java.io.PrintStream');
        for (const { definition } of backend.allDefinitions()) {
          definition.verdict = 'UNSAFE';
          definition.reasons = [{ kind: 'NOT_ALLOW_LISTED', category: null, className: 'java.io.PrintStream', member: null, path: ['x'], detail: null }];
        }
      },
    });
    dialog(view).querySelector<HTMLInputElement>('input[name="entry-kind"][value="class"]')!.click();
    await tick();
    type(dialog(view).querySelector('#entry-name')!, 'java.io.PrintStream');
    button(view, 'Preview the effect').click();

    await waitFor(view, 'preview');
    expect(version()).toBe(1);
    expect(names()).not.toContain('java.io.PrintStream');
    expect(stat(view, 'safe')).toBe('2');
    expect(stat(view, 'unsafe')).toBe('0');
    const changes = [...dialog(view).querySelectorAll('li.change')];
    expect(changes.map((c) => c.querySelector('.pipeline')!.textContent).sort()).toEqual(['demo-failing', 'demo-slow']);
    expect(changes[0].textContent).toContain('UNSAFE');
    expect(changes[0].textContent).toContain('SAFE');
    expect(dialog(view).textContent).toContain('Nothing has changed yet');
  });

  test('says when no verdict changes', async () => {
    const { view } = await open(() => ({ kind: 'add' }));
    type(dialog(view).querySelector('#entry-name')!, 'acme.tools');
    button(view, 'Preview the effect').click();
    await waitFor(view, 'preview');
    expect(dialog(view).textContent).toContain('No pipeline changes its verdict');
    expect(stat(view, 'unsafe')).toBe('0');
  });

  test('can go back from the preview to the form, with what was typed, and applies only when told to', async () => {
    const { view, finished } = await open(() => ({ kind: 'add' }));
    type(dialog(view).querySelector('#entry-name')!, 'acme.tools');
    button(view, 'Preview the effect').click();
    await waitFor(view, 'preview');

    button(view, 'Back').click();
    await tick();
    expect(step(view)).toBe('form');
    expect(dialog(view).querySelector<HTMLInputElement>('#entry-name')!.value).toBe('acme.tools');
    expect(version()).toBe(1);
    expect(finished).not.toHaveBeenCalled();
  });

  test('applies after the preview: the new version, the entry made by the admin, and the result as the Engine gives it', async () => {
    const { view, finished } = await open(() => ({ kind: 'add' }));
    type(dialog(view).querySelector('#entry-name')!, 'acme.tools');
    button(view, 'Preview the effect').click();
    await waitFor(view, 'preview');

    button(view, 'Apply the change').click();

    await waitFor(view, 'applied');
    expect(version()).toBe(2);
    expect(app.engine.backend.allowList.entries.at(-1)).toMatchObject({ name: 'acme.tools', createdBy: 'root' });
    expect(dialog(view).textContent).toContain('version 2 is in force');
    expect(finished).not.toHaveBeenCalled();

    button(view, 'Close').click();
    expect(finished).toHaveBeenCalledOnce();
    expect(finished.mock.calls[0][0]).toMatchObject({ preview: false, version: '2' });
  });

  test('names the entries that the new one makes unnecessary, and keeps them', async () => {
    const { view } = await open(() => ({ kind: 'add' }), {
      seed: (backend) => {
        backend.allowList.entries.push({ kind: 'class', name: 'acme.sub.Thing', exactOnly: null, createdBy: 'x', createdAt: 'a', updatedBy: 'x', updatedAt: 'a' });
      },
    });
    type(dialog(view).querySelector('#entry-name')!, 'acme');
    button(view, 'Preview the effect').click();
    await waitFor(view, 'preview');
    expect(dialog(view).querySelector('.redundant')!.textContent).toContain('acme.sub.Thing');
    expect(dialog(view).querySelector('.redundant')!.textContent).toContain('kept');
  });

  test('says at the name what is wrong with it, and why an entry exists or is covered, with the entry that does', async () => {
    const { view } = await open(() => ({ kind: 'add' }));
    const name = dialog(view).querySelector<HTMLInputElement>('#entry-name')!;

    type(name, '1bad..name');
    button(view, 'Preview the effect').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('.rl-field-error')!.textContent).toBe('The name is not valid.'));

    type(name, 'java.lang');
    button(view, 'Preview the effect').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('.refusal')!.textContent).toContain('exists already'));
    expect(dialog(view).querySelector('.refusal')!.textContent).toContain('java.lang');

    type(name, 'java.lang.invoke.deep');
    button(view, 'Preview the effect').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('.refusal')!.textContent).toContain('already covers'));
    expect(dialog(view).querySelector('.refusal')!.textContent).toContain('java.lang.invoke');
    expect(step(view)).toBe('form');
    expect(version()).toBe(1);
  });

  test('says what the Engine refused when the preview cannot be made, and can try again', async () => {
    const { view } = await open(() => ({ kind: 'add' }));
    type(dialog(view).querySelector('#entry-name')!, 'acme');
    app.engine.faults.push({ match: /POST \/api\/v1\/allowlist\/entries\?preview=true/, status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-7' }, times: 1 });

    button(view, 'Preview the effect').click();

    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')!.textContent).toContain('failed unexpectedly'));
    expect(step(view)).toBe('form');
    button(view, 'Preview the effect').click();
    await waitFor(view, 'preview');
  });

  test('is left with Cancel or Escape before it is applied, and nothing is changed', async () => {
    const { view, finished } = await open(() => ({ kind: 'add' }));
    type(dialog(view).querySelector('#entry-name')!, 'acme');
    button(view, 'Preview the effect').click();
    await waitFor(view, 'preview');

    button(view, 'Cancel').click();

    expect(finished).toHaveBeenCalledWith(null);
    expect(version()).toBe(1);
  });
});

describe('removing an entry that pipelines need', () => {
  const seedAllowed = (backend: TestApp['engine']['backend']) => backend.allowUnsafe(OTHER, 'demo-failing');

  test('starts at the effect, says which pipelines become UNSAFE and that their permission to run is taken back', async () => {
    const { view } = await open(
      (entries) => ({ kind: 'remove', entry: entries.find((e) => e.name === 'java.io.PrintStream')! }),
      { seed: seedAllowed },
    );

    await waitFor(view, 'preview');
    expect(dialog(view).querySelector('h2')!.textContent).toBe('Remove the entry java.io.PrintStream');
    expect(stat(view, 'unsafe')).toBe('2');
    expect(stat(view, 'revoked')).toBe('1');
    const failing = [...dialog(view).querySelectorAll('li.change')].find((c) => c.textContent!.includes('demo-failing'))!;
    expect(failing.querySelector('.revoked')!.textContent).toContain('taken back');
    expect(dialog(view).querySelector('.warning')!.textContent).toContain('taken back');
    expect(names()).toContain('java.io.PrintStream');
  });

  test('applies: the entry is gone, the pipelines are UNSAFE, and the permission is taken back; the result says so', async () => {
    const { view } = await open(
      (entries) => ({ kind: 'remove', entry: entries.find((e) => e.name === 'java.io.PrintStream')! }),
      { seed: seedAllowed },
    );
    await waitFor(view, 'preview');

    button(view, 'Remove the entry').click();

    await waitFor(view, 'applied');
    expect(names()).not.toContain('java.io.PrintStream');
    expect(app.engine.backend.definitionOf(HASH, 'ada', 'demo-slow')!.verdict).toBe('UNSAFE');
    expect(app.engine.backend.definitionOf(OTHER, 'ada', 'demo-failing')!.allowUnsafeExecution).toBe(false);
    expect(stat(view, 'unsafe')).toBe('2');
    expect(dialog(view).querySelector('.warning')!.textContent).toContain('taken back');
  });

  test('lists each uploader\'s version of the same bytes on its own, and says whose it is', async () => {
    const { view } = await open(
      (entries) => ({ kind: 'remove', entry: entries.find((e) => e.name === 'java.io.PrintStream')! }),
      {
        seed: (backend) =>
          void backend.seedArtifact(
            'bob',
            [{ name: 'demo-slow', className: 'samples.slow.SlowPipeline', references: ['java.io.PrintStream'] }],
            { contentHash: HASH },
          ),
      },
    );
    await waitFor(view, 'preview');

    const lines = [...dialog(view).querySelectorAll('li.change')].filter((c) => c.textContent!.includes('demo-slow'));

    expect(lines.map((c) => c.querySelector('.uploader')!.textContent)).toEqual(['ada', 'bob']);
  });

  test('says when the entry is gone since the preview was made, and applies nothing', async () => {
    const { view } = await open((entries) => ({ kind: 'remove', entry: entries.find((e) => e.name === 'java.io.PrintStream')! }));
    await waitFor(view, 'preview');
    // Somebody else removes the entry while the preview is open.
    app.engine.backend.allowList.entries = app.engine.backend.allowList.entries.filter((e) => e.name !== 'java.io.PrintStream');

    button(view, 'Remove the entry').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')).not.toBeNull());
    expect(dialog(view).textContent).toContain('No such allow-list entry');
    expect(step(view)).toBe('preview');
  });
});

describe('changing an entry', () => {
  test('starts from the entry, and says nothing to change when nothing is', async () => {
    const { view } = await open((entries) => ({ kind: 'modify', entry: entries.find((e) => e.name === 'java.util.concurrent')! }));

    expect(dialog(view).querySelector('h2')!.textContent).toBe('Change the entry java.util.concurrent');
    expect(dialog(view).querySelector<HTMLInputElement>('#entry-name')!.value).toBe('java.util.concurrent');
    expect(dialog(view).querySelector<HTMLInputElement>('#entry-exact')!.checked).toBe(false);
    button(view, 'Preview the effect').click();
    await tick();
    expect(dialog(view).querySelector('.rl-field-error')!.textContent).toBe('There is nothing to change.');
  });

  test('previews and applies only the part that is changed', async () => {
    const { view } = await open((entries) => ({ kind: 'modify', entry: entries.find((e) => e.name === 'java.util.concurrent')! }));
    dialog(view).querySelector<HTMLInputElement>('#entry-exact')!.click();
    await tick();
    button(view, 'Preview the effect').click();
    await waitFor(view, 'preview');
    expect(app.engine.backend.allowList.entries.find((e) => e.name === 'java.util.concurrent')!.exactOnly).toBe(false);

    button(view, 'Apply the change').click();
    await waitFor(view, 'applied');
    expect(app.engine.backend.allowList.entries.find((e) => e.name === 'java.util.concurrent')!.exactOnly).toBe(true);
    expect(version()).toBe(2);
  });

  test('a class has no "this package only"', async () => {
    const { view } = await open((entries) => ({ kind: 'modify', entry: entries.find((e) => e.name === 'java.io.PrintStream')! }));
    expect(dialog(view).querySelector('#entry-exact')).toBeNull();
  });
});

describe('judging again', () => {
  test('previews first, and applying does not make a version', async () => {
    const { view } = await open(() => ({ kind: 'recheck' }));

    await waitFor(view, 'preview');
    expect(dialog(view).querySelector('h2')!.textContent).toBe('Judge every pipeline again');
    expect(dialog(view).textContent).toContain('No pipeline changes its verdict');

    button(view, 'Judge again').click();
    await waitFor(view, 'applied');
    expect(version()).toBe(1);
    expect(dialog(view).textContent).toContain('version 1 is in force');
  });

  test('says what the Engine refused, and offers to try again', async () => {
    const { view } = await open(() => ({ kind: 'recheck' }), {});
    await waitFor(view, 'preview');
    app.engine.faults.push({ match: /POST \/api\/v1\/allowlist\/recheck$/, status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-8' }, times: 1 });
    button(view, 'Judge again').click();
    await vi.waitFor(() => expect(dialog(view).querySelector('[role="alert"]')).not.toBeNull());
    expect(step(view)).toBe('preview');
    button(view, 'Judge again').click();
    await waitFor(view, 'applied');
  });
});

describe('in zh-TW', () => {
  test('speaks the language of the screen', async () => {
    const { view } = await open(() => ({ kind: 'add' }), { languages: ['zh-TW'] });
    expect(dialog(view).querySelector('h2')!.textContent).toBe('新增白名單條目');
    expect(dialog(view).textContent).toContain('信任');
  });
});

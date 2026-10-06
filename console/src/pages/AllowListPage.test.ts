import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import AllowListPage from './AllowListPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const HASH = 'c0ffee12'.padEnd(64, '3');

const page = async (options: { languages?: string[] } = {}) => {
  app = await createTestApp({ identity: root, languages: options.languages });
  app.engine.backend.seedArtifact(
    'ada',
    [{ name: 'demo-slow', className: 'samples.slow.SlowPipeline', references: ['java.io.PrintStream'] }],
    { contentHash: HASH },
  );
  app.context.router.navigate('/allowlist');
  const view = app.mount(AllowListPage);
  return { view };
};
const loaded = (view: HTMLElement) => vi.waitFor(() => expect(view.querySelector('table.entries')).not.toBeNull());
const rows = (view: HTMLElement) => [...view.querySelectorAll<HTMLElement>('table.entries tbody tr')];
const nameOf = (row: HTMLElement) => row.querySelector('.name')!.textContent!.trim();
const button = (root: ParentNode, label: string) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === label)!;
const dialog = (view: HTMLElement) => view.querySelector<HTMLElement>('[role="dialog"]')!;
const row = (view: HTMLElement, name: string) => rows(view).find((r) => nameOf(r) === name)!;
const step = (view: HTMLElement) => dialog(view)?.querySelector('[data-step]')?.getAttribute('data-step');

describe('the allow-list', () => {
  test('says which version is in force, who made it and when', async () => {
    const { view } = await page();
    await loaded(view);

    expect(view.querySelector('h1')!.textContent).toBe('Allow-list');
    const current = view.querySelector('.current')!;
    expect(current.querySelector('.version')!.textContent).toBe('1');
    expect(current.textContent).toContain('system');
    expect(current.querySelector('time')).not.toBeNull();
  });

  test('says that an entry is a decision to trust, and what a verdict does not cover, in the Engine\'s words', async () => {
    const { view } = await page();
    await loaded(view);

    expect(view.querySelector('.trust')!.textContent).toContain('decision to trust');
    expect(view.querySelector('.limitations')!.textContent).toContain('Fake');
  });

  test('has every entry with its kind, its name, what it lets through and who made it', async () => {
    const { view } = await page();
    await loaded(view);

    expect(rows(view)).toHaveLength(44);
    const lang = row(view, 'java.lang');
    expect(lang.querySelector('.kind')!.textContent).toContain('package');
    expect(lang.querySelector('.scope')!.textContent).toContain('this package only');
    expect(row(view, 'java.lang.invoke').querySelector('.scope')!.textContent).toContain('and its subpackages');
    const printStream = row(view, 'java.io.PrintStream');
    expect(printStream.querySelector('.kind')!.textContent).toContain('class');
    expect(printStream.querySelector('.scope')!.textContent).toContain('and its nested classes');
    expect(lang.querySelector('.by')!.textContent).toContain('system');
    expect(view.querySelector('.count')!.textContent).toContain('44');
  });

  test('is searched by name and chosen by kind', async () => {
    const { view } = await page();
    await loaded(view);
    const search = view.querySelector<HTMLInputElement>('#allow-search')!;

    search.value = 'kotlin.c';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    await tick();
    expect(rows(view).map(nameOf)).toEqual(['kotlin.collections', 'kotlin.comparisons', 'kotlin.concurrent', 'kotlin.contracts', 'kotlin.coroutines']);

    search.value = '';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    const kind = view.querySelector<HTMLSelectElement>('#allow-kind')!;
    kind.value = 'class';
    kind.dispatchEvent(new Event('change', { bubbles: true }));
    await tick();
    expect(rows(view).map(nameOf)).toEqual(['java.io.PrintStream', 'kotlin.io.ConsoleKt', 'kotlin.io.CloseableKt', 'java.io.Closeable']);
  });

  test('has the history of versions, newest first, with what was done, by whom, and how many pipelines were judged again', async () => {
    const { view } = await page();
    await loaded(view);
    const history = [...view.querySelectorAll<HTMLElement>('.history tbody tr')];

    expect(history).toHaveLength(1);
    expect(history[0].querySelector('.v')!.textContent).toBe('1');
    expect(history[0].querySelector('.action')!.textContent).toBe('Initial list');
    expect(history[0].querySelector('.detail')!.textContent).toContain('default-2');
    expect(history[0].querySelector('.by')!.textContent).toContain('system');
  });
});

describe('changing the allow-list from the page', () => {
  test('adding an entry goes through its preview, and the page shows the new version, the entry and the history after it', async () => {
    const { view } = await page();
    await loaded(view);

    button(view, 'Add an entry').click();
    await tick();
    expect(dialog(view).querySelector('h2')!.textContent).toBe('Add an allow-list entry');
    const name = dialog(view).querySelector<HTMLInputElement>('#entry-name')!;
    name.value = 'acme.tools';
    name.dispatchEvent(new Event('input', { bubbles: true }));
    button(dialog(view), 'Preview the effect').click();
    await vi.waitFor(() => expect(step(view)).toBe('preview'));
    expect(rows(view)).toHaveLength(44);
    button(dialog(view), 'Apply the change').click();
    await vi.waitFor(() => expect(step(view)).toBe('applied'));
    button(dialog(view), 'Close').click();

    await vi.waitFor(() => expect(rows(view)).toHaveLength(45));
    expect(view.querySelector('.current .version')!.textContent).toBe('2');
    expect(nameOf(rows(view).at(-1)!)).toBe('acme.tools');
    expect(rows(view).at(-1)!.querySelector('.by')!.textContent).toContain('root');
    const history = [...view.querySelectorAll<HTMLElement>('.history tbody tr')];
    expect(history.map((h) => h.querySelector('.action')!.textContent)).toEqual(['Entry added', 'Initial list']);
    expect(history[0].querySelector('.by')!.textContent).toContain('root');
  });

  test('removing an entry that pipelines need shows which become UNSAFE, and after it the history says how many', async () => {
    const { view } = await page();
    await loaded(view);

    button(row(view, 'java.io.PrintStream'), 'Remove').click();
    await vi.waitFor(() => expect(step(view)).toBe('preview'));
    expect(dialog(view).querySelector('li.change .pipeline')!.textContent).toBe('demo-slow');
    button(dialog(view), 'Remove the entry').click();
    await vi.waitFor(() => expect(step(view)).toBe('applied'));
    button(dialog(view), 'Close').click();

    await vi.waitFor(() => expect(rows(view)).toHaveLength(43));
    const latest = view.querySelector<HTMLElement>('.history tbody tr')!;
    expect(latest.querySelector('.action')!.textContent).toBe('Entry removed');
    expect(latest.querySelector('.unsafe')!.textContent).toBe('1');
  });

  test('changing an entry starts from it', async () => {
    const { view } = await page();
    await loaded(view);

    button(row(view, 'java.util.concurrent'), 'Change').click();
    await tick();

    expect(dialog(view).querySelector('h2')!.textContent).toBe('Change the entry java.util.concurrent');
  });

  test('judging again is previewed first', async () => {
    const { view } = await page();
    await loaded(view);

    button(view, 'Judge every pipeline again').click();
    await vi.waitFor(() => expect(step(view)).toBe('preview'));
    expect(dialog(view).querySelector('h2')!.textContent).toBe('Judge every pipeline again');
  });

  test('leaving the dialog without applying changes nothing on the page', async () => {
    const { view } = await page();
    await loaded(view);

    button(row(view, 'java.io.PrintStream'), 'Remove').click();
    await vi.waitFor(() => expect(step(view)).toBe('preview'));
    button(dialog(view), 'Cancel').click();
    await tick();

    expect(dialog(view)).toBeNull();
    expect(rows(view)).toHaveLength(44);
    expect(view.querySelector('.current .version')!.textContent).toBe('1');
  });
});

describe('when the Engine refuses', () => {
  test('says so in words, with a way to try again', async () => {
    app = await createTestApp({ identity: root });
    app.engine.faults.push({ match: /GET \/api\/v1\/allowlist$/, status: 500, body: { error: 'internal_error', message: 'x', errorId: 'e-3' }, times: 1 });
    const view = app.mount(AllowListPage);
    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());

    button(view, 'Retry').click();
    await loaded(view);
  });
});

describe('in zh-TW', () => {
  test('speaks the language of the screen', async () => {
    const { view } = await page({ languages: ['zh-TW'] });
    await loaded(view);
    expect(view.querySelector('h1')!.textContent).toContain('白名單');
    expect(view.querySelector('.trust')!.textContent).toContain('信任');
    expect(row(view, 'java.lang').querySelector('.scope')!.textContent).toContain('僅此套件');
    expect(view.querySelector('.history .action')!.textContent).toBe('初始名單');
  });
});

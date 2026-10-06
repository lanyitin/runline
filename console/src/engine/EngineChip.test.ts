import { flushSync } from 'svelte';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, FULL_HASH, type TestApp } from '../../test-support/app';
import EngineChip from './EngineChip.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ready = async (view: HTMLElement) =>
  vi.waitFor(() => expect(view.textContent).toContain('a3f9c1e'));

describe('the Engine chip', () => {
  test('shows the version and the short commit hash once the Engine has said them', async () => {
    app = await createTestApp();
    const view = app.mount(EngineChip);

    await ready(view);

    expect(view.textContent).toContain('v0.4.2');
    expect(view.textContent).toContain('a3f9c1e');
    expect(view.textContent).not.toContain(FULL_HASH);
  });

  test('says that it is reading, while it is', async () => {
    app = await createTestApp({ engineMode: 'hang' });
    const view = app.mount(EngineChip);
    expect(view.textContent).toContain('Reading the Engine version');
    expect(view.querySelector('[aria-busy="true"]')).not.toBeNull();
  });

  test('says in words that the commit is unknown', async () => {
    app = await createTestApp({ commitHash: 'unknown' });
    const view = app.mount(EngineChip);
    await vi.waitFor(() => expect(view.textContent).toContain('v0.4.2'));
    expect(view.textContent).toContain('unknown');
  });

  test('says in words, not only in colour, that the build was dirty', async () => {
    app = await createTestApp({ dirty: true });
    const view = app.mount(EngineChip);
    await ready(view);
    expect(view.textContent).toContain('dirty');
  });

  test('says that the Engine is unavailable and offers to try again', async () => {
    app = await createTestApp({ engineMode: 'internal-error' });
    const view = app.mount(EngineChip);
    await vi.waitFor(() => expect(view.textContent).toContain('Engine unavailable'));

    app.engine.mode = 'normal';
    view.querySelector<HTMLButtonElement>('button.retry')!.click();

    await ready(view);
    expect(view.textContent).not.toContain('Engine unavailable');
  });

  test('speaks the language of the screen, and follows a change of it at once', async () => {
    app = await createTestApp({ engineMode: 'internal-error', languages: ['zh-TW'] });
    const view = app.mount(EngineChip);
    await vi.waitFor(() => expect(view.textContent).toContain('無法連線到 Engine'));

    app.context.i18n.setLocale('en');
    flushSync();

    expect(view.textContent).toContain('Engine unavailable');
  });
});

describe('the details of the Engine chip', () => {
  const open = async () => {
    app = await createTestApp();
    const view = app.mount(EngineChip);
    await ready(view);
    const toggle = view.querySelector<HTMLButtonElement>('button.toggle')!;
    return { view, toggle };
  };

  test('are closed at first, and a button opens and closes them', async () => {
    const { view, toggle } = await open();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(view.querySelector('[role="dialog"]')).toBeNull();

    toggle.click();
    flushSync();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(view.querySelector('[role="dialog"]')).not.toBeNull();

    toggle.click();
    flushSync();
    expect(view.querySelector('[role="dialog"]')).toBeNull();
  });

  test('hold the full commit hash and the address of the API', async () => {
    const { view, toggle } = await open();
    toggle.click();
    flushSync();

    const details = view.querySelector('[role="dialog"]')!;
    expect(details.textContent).toContain(FULL_HASH);
    expect(details.textContent).toContain(location.origin);
  });

  test('close with Escape, and the keyboard goes back to the button', async () => {
    const { view, toggle } = await open();
    toggle.click();
    flushSync();
    // The keyboard moves into the details when they open.
    await vi.waitFor(() => expect(document.activeElement).toBe(view.querySelector('button.close')));

    document.activeElement!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    flushSync();

    expect(view.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(toggle);
  });

  test('say that copying is not available when the browser has no clipboard', async () => {
    const { view, toggle } = await open();
    toggle.click();
    flushSync();

    // jsdom has no clipboard, as a page on plain http has none either.
    expect(navigator.clipboard).toBeUndefined();
    view.querySelector<HTMLButtonElement>('button.copy')!.click();
    await vi.waitFor(() =>
      expect(view.querySelector('[role="status"]')?.textContent).toContain('Copying is not available'),
    );
  });
});

describe('what the details of the Engine chip hold', () => {
  const opened = async (identity?: { name: string; role: 'developer' | 'admin' }, languages = ['en']) => {
    app = await createTestApp({ identity, languages });
    const view = app.mount(EngineChip);
    await ready(view);
    if (identity) await vi.waitFor(() => expect(view.querySelector('button.toggle')).not.toBeNull());
    view.querySelector<HTMLButtonElement>('button.toggle')!.click();
    flushSync();
    return view;
  };

  test('before anyone has signed in: the public fields only, and no word of the system', async () => {
    const view = await opened();
    const details = view.querySelector('[role="dialog"]')!.textContent!;

    expect(details).toContain('Version');
    expect(details).toContain(FULL_HASH);
    expect(details).not.toContain('JDK');
    expect(details).not.toContain('Uptime');
    expect(details).not.toContain('Build time');
    expect(details).not.toContain('Allow-list version');
  });

  test('once signed in: the build time with what it means, the JDK, the uptime, the allow-list version, the address', async () => {
    const view = await opened({ name: 'Ada', role: 'developer' });
    await vi.waitFor(() =>
      expect(view.querySelector('[role="dialog"]')!.textContent).toContain('25.0.4+1-LTS'),
    );
    const details = view.querySelector('[role="dialog"]')!;

    expect(details.textContent).toContain('Build time');
    expect(details.textContent).toContain('2026');
    expect(details.textContent).toContain('not the time it was built');
    expect(details.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-05T08:30:00Z');
    expect(details.textContent).toContain('Uptime');
    expect(details.textContent).toMatch(/1d\s*3h/);
    expect(details.textContent).toContain('Allow-list version');
    expect(details.textContent).toContain('3');
    expect(details.textContent).toContain(location.origin);
    expect(details.textContent).toContain(FULL_HASH);
  });

  test('keeps open while the details of the system arrive after signing in', async () => {
    const view = await opened();
    app.engine.callers = [{ name: 'Ada', role: 'developer', token: 'tok-late' }];

    await app.session.signIn('tok-late');

    await vi.waitFor(() =>
      expect(view.querySelector('[role="dialog"]')!.textContent).toContain('JDK'),
    );
  });

  test('in the language of the screen', async () => {
    const view = await opened({ name: 'Ada', role: 'developer' }, ['zh-TW']);
    await vi.waitFor(() => expect(view.querySelector('[role="dialog"]')!.textContent).toContain('建置時間'));
    expect(view.querySelector('[role="dialog"]')!.textContent).toContain('運行時間');
  });
});

describe('the status dot of the Engine chip', () => {
  const dotOf = (view: HTMLElement) => view.querySelector('.toggle .dot')!.className;
  const systemRead = () =>
    vi.waitFor(() =>
      expect(app.context.engineInfo.state).toMatchObject({
        status: 'ready',
        info: { system: expect.anything() },
      }),
    );

  test('is green and silent while the last call to the Engine got an answer', async () => {
    app = await createTestApp({ identity: { name: 'Ada', role: 'developer' } });
    const view = app.mount(EngineChip);
    await ready(view);

    expect(dotOf(view)).toContain('success');
    expect(view.textContent).not.toContain('No connection');
  });

  test('turns red and says so in words when the last call to the Engine got none, and back with the next answer', async () => {
    app = await createTestApp({ identity: { name: 'Ada', role: 'developer' } });
    const view = app.mount(EngineChip);
    await ready(view);
    await systemRead();

    app.engine.mode = 'internal-error';
    await app.session.request('/api/v1/system');
    flushSync();
    expect(dotOf(view)).toContain('danger');
    expect(view.textContent).toContain('No connection');

    app.engine.mode = 'normal';
    await app.session.request('/api/v1/system');
    flushSync();
    expect(dotOf(view)).toContain('success');
    expect(view.textContent).not.toContain('No connection');
  });

  test('asks nothing of the Engine to tell: no call of its own', async () => {
    app = await createTestApp({ identity: { name: 'Ada', role: 'developer' } });
    const view = app.mount(EngineChip);
    await ready(view);
    await new Promise((resolve) => setTimeout(resolve, 100));
    app.engine.log.length = 0;

    app.context.connection.record(false);
    flushSync();
    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(app.engine.log).toEqual([]);
  });
});

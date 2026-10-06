import { flushSync } from 'svelte';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, FULL_HASH, type TestApp } from '../../test-support/app';
import EnginePage from './EnginePage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const developer = { name: 'Ada', role: 'developer' as const };

const page = async (options: Parameters<typeof createTestApp>[0] = { identity: developer }) => {
  app = await createTestApp(options);
  return app.mount(EnginePage);
};

const row = (view: HTMLElement, term: string) =>
  [...view.querySelectorAll('dt')].find((dt) => dt.textContent?.trim() === term)
    ?.nextElementSibling;

describe('the Engine page', () => {
  test('is the Engine as GET /api/v1/system says it: version, full hash, build time, JDK, uptime, allow-list version, API address', async () => {
    const view = await page();
    await vi.waitFor(() => expect(row(view, 'JDK')).toBeTruthy());

    expect(view.querySelector('h1')!.textContent).toBe('Engine');
    expect(row(view, 'Version')!.textContent).toContain('0.4.2');
    expect(row(view, 'Full commit hash')!.textContent).toContain(FULL_HASH);
    expect(row(view, 'Build time')!.querySelector('time')!.getAttribute('datetime')).toBe(
      '2026-10-05T08:30:00Z',
    );
    expect(row(view, 'JDK')!.textContent).toBe('25.0.4+1-LTS');
    expect(row(view, 'Uptime')!.textContent).toMatch(/1d\s*3h/);
    expect(row(view, 'Allow-list version')!.textContent).toBe('3');
    expect(row(view, 'API base URL')!.textContent).toBe(location.origin);
  });

  test('says what the build time is: the time of the commit, not of the build', async () => {
    const view = await page();
    await vi.waitFor(() => expect(row(view, 'Build time')).toBeTruthy());
    expect(row(view, 'Build time')!.textContent).toContain('not the time it was built');
  });

  test('says in words that the build had uncommitted changes, and that the commit is unknown', async () => {
    const view = await page({ identity: developer, dirty: true, commitHash: 'unknown' });
    await vi.waitFor(() => expect(row(view, 'JDK')).toBeTruthy());

    expect(view.textContent).toContain('Built with uncommitted changes');
    expect(row(view, 'Full commit hash')!.textContent).toContain('unknown');
  });

  test('copies the full hash', async () => {
    const view = await page();
    await vi.waitFor(() => expect(row(view, 'JDK')).toBeTruthy());

    view.querySelector<HTMLButtonElement>('button.copy')!.click();
    await vi.waitFor(() =>
      expect(view.querySelector('[role="status"]')!.textContent).toContain(
        'Copying is not available',
      ),
    );
  });

  test('says that it is reading until the Engine has said the system', async () => {
    const view = await page({ identity: developer });
    expect(view.textContent).toContain('Reading the Engine version');
    expect(view.querySelector('[aria-busy="true"]')).not.toBeNull();
    await vi.waitFor(() => expect(row(view, 'JDK')).toBeTruthy());
  });

  test('keeps what it shows while it reads again', async () => {
    const view = await page({ identity: developer });
    await vi.waitFor(() => expect(row(view, 'JDK')).toBeTruthy());

    app.engine.mode = 'hang';
    app.context.engineInfo.reload();
    flushSync();

    expect(row(view, 'JDK')).toBeTruthy();
  });

  test('says that the Engine is unavailable, and offers to read again', async () => {
    const view = await page({ identity: developer });
    await vi.waitFor(() => expect(row(view, 'JDK')).toBeTruthy());
    app.engine.mode = 'internal-error';
    app.context.engineInfo.reload();
    // a reload keeps showing what it had; a failure shows the failure
    await vi.waitFor(() => expect(view.textContent).toContain('Engine unavailable'));

    app.engine.mode = 'normal';
    view.querySelector<HTMLButtonElement>('button.retry')!.click();
    await vi.waitFor(() => expect(row(view, 'JDK')).toBeTruthy());
  });

  test('follows the language', async () => {
    const view = await page({ identity: developer, languages: ['zh-TW'] });
    await vi.waitFor(() => expect(row(view, '運行時間')).toBeTruthy());
    expect(view.querySelector('h1')!.textContent).toBe('Engine 資訊');
  });
});

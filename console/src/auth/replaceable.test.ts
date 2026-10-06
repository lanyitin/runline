// The authentication boundary can be replaced (WI-34): the same Console, with a second way to sign
// in that is not a Bearer token, runs without a line of a screen being changed.

import { flushSync } from 'svelte';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { createCodeMethod } from '../../test-support/code-method';
import { createTestApp, TEST_TOKEN, type TestApp } from '../../test-support/app';
import App from '../App.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const developer = { name: 'Ada', role: 'developer' as const };
const admin = { name: 'Root', role: 'admin' as const };

const withCode = async (options: Parameters<typeof createTestApp>[0] = {}) => {
  app = await createTestApp({ method: createCodeMethod(), ...options });
  return app.mount(App);
};

describe('the Console with a second way to sign in', () => {
  test('shows the sign-in of that method, in the frame of the Console, with the Engine version', async () => {
    const view = await withCode();

    expect(view.querySelector('h1')!.textContent).toBe('Sign in to Runline Console');
    expect(view.querySelector('label')!.textContent).toBe('Access code');
    expect(view.querySelector('input[type="password"]')).toBeNull();
    await vi.waitFor(() => expect(view.textContent).toContain('a3f9c1e'));
  });

  test('signs in with that method: the shell of the role, and the requests carry that credential, not a Bearer token', async () => {
    const view = await withCode({ identity: developer });

    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());
    await vi.waitFor(() =>
      expect(
        app.engine.log.some(
          (call) => call.path === '/api/v1/system' && call.sessionCode === TEST_TOKEN,
        ),
      ).toBe(true),
    );
    expect(app.engine.log.every((call) => call.authorization === null)).toBe(true);
    expect([...view.querySelectorAll('nav a')].map((a) => a.getAttribute('href'))).toEqual([
      '/',
      '/pipelines',
      '/runs',
      '/upload',
      '/engine',
    ]);
  });

  test('turns the pages by role as with the token: an admin page is for admins', async () => {
    const view = await withCode({ identity: developer, path: '/allowlist' });
    await vi.waitFor(() => expect(view.querySelector('main h1')?.textContent).toBe('Admins only'));
  });

  test('shows the Engine page with the system details, read with that credential', async () => {
    const view = await withCode({ identity: admin, path: '/engine' });
    await vi.waitFor(() => expect(view.textContent).toContain('25.0.4+1-LTS'));
  });

  test("a code the Engine does not know is refused by that method's own screen", async () => {
    const view = await withCode();
    const input = view.querySelector<HTMLInputElement>('input')!;
    input.value = 'nope';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    flushSync();

    view.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();

    await vi.waitFor(() =>
      expect(view.querySelector('[role="alert"]')!.textContent).toContain('refused'),
    );
    expect(view.querySelector('nav')).toBeNull();
  });

  test('a 401 ends the session as with the token: the sign-in in place of the page, with the reason, at the same address', async () => {
    const view = await withCode({ identity: developer, path: '/runs' });
    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());
    app.engine.callers = [];

    await app.session.request('/api/v1/system');
    flushSync();

    expect(view.querySelector('nav')).toBeNull();
    expect(view.querySelector('[role="status"]')!.textContent).toContain('no longer valid');
    expect(location.pathname).toBe('/runs');
  });

  test('signing out in the top bar works the same', async () => {
    const view = await withCode({ identity: developer });
    await vi.waitFor(() => expect(view.querySelector('header button.sign-out')).not.toBeNull());

    view.querySelector<HTMLButtonElement>('header button.sign-out')!.click();
    flushSync();

    expect(view.querySelector('nav')).toBeNull();
    expect(sessionStorage.length).toBe(0);
  });
});

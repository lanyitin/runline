import { flushSync } from 'svelte';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import App from '../App.svelte';

// "Sign in with authentik" in the Console (WI-1202): the sign-in page, the pages of the person who
// signed in, signing out, and what a 401 or a failed login does. The Engine is the Fake Engine with
// login on, over HTTP; the Console is the real application.
let app: TestApp;
afterEach(() => app.dispose());

const admin = { name: 'dana', role: 'admin' as const };
const developer = { name: 'eli', role: 'developer' as const };

async function open(options: Parameters<typeof createTestApp>[0] = {}) {
  app = await createTestApp({ oidc: {}, ...options });
  return app.mount(App);
}
const loginLink = (view: HTMLElement) =>
  view.querySelector<HTMLAnchorElement>('a[href*="/api/v1/auth/oidc/login"]');
const returnOf = (link: HTMLAnchorElement) => new URL(link.href).searchParams.get('return');
const alertText = (view: HTMLElement) => view.querySelector('[data-login-error]')?.textContent ?? '';

describe('the sign-in page, with login on', () => {
  test('offers "Sign in with authentik" beside the token, as a link that moves the page to the Engine', async () => {
    const view = await open();

    const link = loginLink(view)!;
    expect(link.textContent!.trim()).toBe('Sign in with authentik');
    expect(new URL(link.href).origin + new URL(link.href).pathname).toBe(
      `${app.engine.url}/api/v1/auth/oidc/login`,
    );
    expect(view.querySelector('input[type="password"]')).not.toBeNull();
    expect(view.querySelector('main h1')!.textContent).toContain('Sign in');
  });

  test('says the name the Engine gives for the button', async () => {
    const view = await open({ oidc: { label: 'Company login' } });
    expect(loginLink(view)!.textContent!.trim()).toBe('Sign in with Company login');
  });

  test('is in the language of the screen', async () => {
    const view = await open({ languages: ['zh-TW'] });
    expect(loginLink(view)!.textContent!.trim()).toBe('使用 authentik 登入');
  });

  test('takes the person back to the page they asked for: the path and the query', async () => {
    const view = await open({ path: '/runs?state=FAILED' });
    expect(returnOf(loginLink(view)!)).toBe('/runs?state=FAILED');
  });

  test('keeps the version of the Engine and the language switch of the frame', async () => {
    const view = await open();
    await vi.waitFor(() => expect(view.textContent).toContain('v0.4.2'));
    expect(view.querySelector('header')).not.toBeNull();
    expect(view.textContent).toContain('a3f9c1e');
  });
});

describe('the sign-in page, with login off', () => {
  test('is as it was: the token only, no word of a provider', async () => {
    app = await createTestApp();
    const view = app.mount(App);
    await vi.waitFor(() => expect(view.querySelector('input')).not.toBeNull());

    expect(loginLink(view)).toBeNull();
    expect(view.querySelector('a[href*="/auth/"]')).toBeNull();
    expect(view.textContent).not.toContain('authentik');
  });
});

describe('a login that failed', () => {
  const CASES: Array<[string, string, string]> = [
    ['access_denied', 'cancelled', '取消'],
    ['invalid_state', 'Start again', '重新'],
    ['invalid_token', 'could not be verified', '驗證'],
    ['refresh_unavailable', 'administrator', '管理員'],
    ['not_authorized', 'group', '群組'],
    ['name_conflict', 'administrator', '管理員'],
    ['provider_unavailable', 'cannot be reached', '無法'],
    ['user_disabled', 'disabled', '停用'],
    ['too_many_logins', 'Too many', '過多'],
  ];

  test.each(CASES)('%s is said in English', async (code, english) => {
    const view = await open({ path: `/?login_error=${code}` });
    expect(alertText(view)).toContain(english);
  });

  test.each(CASES)('%s is said in Chinese', async (code, _english, chinese) => {
    const view = await open({ path: `/?login_error=${code}`, languages: ['zh-TW'] });
    expect(alertText(view)).toContain(chinese);
  });

  test('not authorized says to ask an administrator; the name clash says to contact one', async () => {
    let view = await open({ path: '/?login_error=not_authorized' });
    expect(alertText(view)).toMatch(/ask an administrator/i);
    await app.dispose();
    view = await open({ path: '/?login_error=name_conflict' });
    expect(alertText(view)).toMatch(/contact an administrator/i);
  });

  test('a provider set up wrongly is said as a matter of setup for an administrator, without detail', async () => {
    const view = await open({ path: '/?login_error=refresh_unavailable' });
    expect(alertText(view)).toMatch(/contact an administrator/i);
    expect(alertText(view)).not.toMatch(/refresh|token|offline_access/i);
  });

  test('is taken out of the address, and the way back does not carry it', async () => {
    const view = await open({ path: '/runs?state=FAILED&login_error=access_denied' });

    expect(location.search).toBe('?state=FAILED');
    expect(returnOf(loginLink(view)!)).toBe('/runs?state=FAILED');
    expect(alertText(view)).toContain('cancelled');
  });

  test('a code the Console does not know is said in general words and is not repeated', async () => {
    const view = await open({ path: '/?login_error=%3Cb%3Eevil%3C%2Fb%3E' });

    expect(alertText(view)).toContain('Signing in failed');
    expect(view.innerHTML).not.toContain('evil');
    expect(location.search).toBe('');
  });

  test('no message at all when the address has no code', async () => {
    const view = await open();
    expect(view.querySelector('[data-login-error]')).toBeNull();
  });
});

describe('a person who signed in with the provider', () => {
  test('gets the shell of the role: the name, and the pages of an admin', async () => {
    const view = await open({ oidc: { person: admin }, path: '/allowlist' });

    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());
    expect(view.querySelector('.user')!.textContent).toContain('dana');
    expect(view.querySelector('nav a[href="/allowlist"]')).not.toBeNull();
    expect(view.querySelector('main h1')!.textContent).toContain('Allow-list');
  });

  test('a developer sees no admin page in the navigation, and the address is refused too', async () => {
    const view = await open({ oidc: { person: developer }, path: '/allowlist' });

    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());
    expect(view.querySelector('nav a[href="/allowlist"]')).toBeNull();
    expect(view.querySelector('nav a[href="/resources"]')).toBeNull();
    expect(view.querySelector('main h1')!.textContent).toBe('Admins only');
    expect(view.querySelector('main')!.textContent).not.toContain('Allow-list');
  });

  test('holds no credential: nothing in the storage of the tab or of the browser', async () => {
    const view = await open({ oidc: { person: admin } });
    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());

    expect(sessionStorage.length).toBe(0);
    expect(Object.keys(localStorage).filter((key) => key.startsWith('runline.session'))).toEqual([]);
    expect(location.href).not.toMatch(/token|session|csrf/i);
    expect(view.textContent).not.toContain(app.browser.cookie('runline_session')!);
  });

  test('signs out with the button of the top bar: the Engine ends the session and the sign-in follows', async () => {
    const view = await open({ oidc: { person: admin }, path: '/runs' });
    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());
    const id = app.browser.cookie('runline_session')!;

    [...view.querySelectorAll<HTMLButtonElement>('header button')]
      .find((b) => b.textContent?.trim() === 'Sign out')!
      .click();

    await vi.waitFor(() => expect(view.querySelector('nav')).toBeNull());
    expect(app.engine.oidc!.has(id)).toBe(false);
    await vi.waitFor(() => expect(loginLink(view)).not.toBeNull());
  });

  test('is put at the sign-in by a 401 of any call, on the page they were on, and signs in back to it', async () => {
    const view = await open({ oidc: { person: admin }, path: '/runs?state=FAILED' });
    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());

    app.engine.oidc!.end(app.browser.cookie('runline_session')!);
    await app.session.request('/api/v1/system');
    flushSync();

    await vi.waitFor(() => expect(view.querySelector('nav')).toBeNull());
    expect(location.pathname + location.search).toBe('/runs?state=FAILED');
    expect(returnOf(loginLink(view)!)).toBe('/runs?state=FAILED');
    expect(view.textContent).toContain('no longer valid');
  });
});

describe('a person with a token, with login on', () => {
  test('is put at the sign-in by a 401 as well, and the way back is the page they were on', async () => {
    const view = await open({ identity: developer, path: '/runs' });
    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());

    app.engine.callers = [];
    await app.session.request('/api/v1/system');
    flushSync();

    await vi.waitFor(() => expect(view.querySelector('nav')).toBeNull());
    expect(returnOf(loginLink(view)!)).toBe('/runs');
    expect(view.querySelector('input[type="password"]')).not.toBeNull();
  });
});

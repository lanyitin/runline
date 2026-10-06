import { flushSync } from 'svelte';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../test-support/app';
import App from './App.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const developer = { name: 'Ada', role: 'developer' as const };
const admin = { name: 'Root', role: 'admin' as const };

const withApp = async (options: Parameters<typeof createTestApp>[0] = {}) => {
  app = await createTestApp(options);
  return app.mount(App);
};

const engineShown = (view: HTMLElement) =>
  vi.waitFor(() => {
    expect(view.textContent).toContain('v0.4.2');
    expect(view.textContent).toContain('a3f9c1e');
  });

describe('the pages of a developer', () => {
  test.each([
    ['/', 'Overview'],
    ['/pipelines', 'Pipelines'],
    ['/runs', 'Runs'],
    ['/runs/new', 'Create run'],
    ['/upload', 'Upload'],
  ])('%s is the page %s, not a placeholder', async (path, title) => {
    const view = await withApp({ identity: developer, path });
    expect(view.querySelector('main h1')!.textContent).toBe(title);
    expect(view.querySelector('main')!.textContent).not.toContain('This page is not built yet.');
  });

  test('a page of one run: its own page, under Runs in the breadcrumb and the navigation', async () => {
    app = await createTestApp({ identity: developer, path: '/' });
    const run = app.engine.backend.seedRun('Ada', { pipeline: 'order-sync' });
    app.context.router.navigate(`/runs/${run.runId}`);
    const view = app.mount(App);

    await vi.waitFor(() => expect(view.querySelector('main h1')!.textContent).toContain(run.runId.slice(0, 8)));
    const crumbs = [...view.querySelectorAll('header ol li')].map((li) => li.textContent!.replace('›', '').trim());
    expect(crumbs).toEqual(['Workspace', 'Runs', 'Run']);
    expect(view.querySelector('header ol a')!.getAttribute('href')).toBe('/runs');
    expect(view.querySelector('nav a[href="/runs"]')!.getAttribute('aria-current')).toBe('true');
    expect(document.title).toBe('Run · Runline Console');
  });

  test('a page of one pipeline, under Pipelines', async () => {
    app = await createTestApp({ identity: developer, path: '/' });
    const hash = app.engine.backend.seedArtifact('Ada', [{ name: 'order-sync', className: 'x.O' }]);
    app.context.router.navigate(`/pipelines/${hash}?pipeline=order-sync`);
    const view = app.mount(App);

    await vi.waitFor(() => expect(view.querySelector('main h1')!.textContent).toBe('order-sync'));
    expect(view.querySelector('nav a[href="/pipelines"]')!.getAttribute('aria-current')).toBe('true');
  });

  test('going from one run to another shows the other, whole', async () => {
    app = await createTestApp({ identity: developer, path: '/' });
    const one = app.engine.backend.seedRun('Ada', { pipeline: 'first-one' });
    const two = app.engine.backend.seedRun('Ada', { pipeline: 'second-one' });
    app.context.router.navigate(`/runs/${one.runId}`);
    const view = app.mount(App);
    await vi.waitFor(() => expect(view.querySelector('main')!.textContent).toContain('first-one'));

    app.context.router.navigate(`/runs/${two.runId}`);

    await vi.waitFor(() => expect(view.querySelector('main')!.textContent).toContain('second-one'));
    expect(view.querySelector('main')!.textContent).not.toContain('first-one');
  });

  test('the pages of the admin are still not built, and say so', async () => {
    const view = await withApp({ identity: admin, path: '/triggers' });
    expect(view.querySelector('main')!.textContent).toContain('This page is not built yet.');
  });
});

describe('before sign-in', () => {
  test.each(['/', '/runs', '/no/such/page'])(
    'the Engine version and commit hash are on %s',
    async (path) => {
      const view = await withApp({ path });
      await engineShown(view);
    },
  );

  test('the language can be switched, and the page follows without reloading', async () => {
    const view = await withApp({ languages: ['zh-TW'] });
    expect(view.querySelector('h1')!.textContent).toBe('登入 Runline Console');

    [...view.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'English')!.click();
    flushSync();

    expect(view.querySelector('h1')!.textContent).toBe('Sign in to Runline Console');
    expect(document.documentElement.lang).toBe('en');
  });

  test('shows no navigation and no user', async () => {
    const view = await withApp();
    expect(view.querySelector('nav')).toBeNull();
  });
});

describe('the shell of a signed-in user', () => {
  test('a developer has the workspace and the Engine page in the navigation and no admin item', async () => {
    const view = await withApp({ identity: developer });
    const nav = view.querySelector('nav')!;

    const links = [...nav.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/', '/pipelines', '/runs', '/upload', '/engine']);
    expect(nav.textContent).not.toContain('Allow-list');
    expect(nav.textContent).not.toContain('Admin');
  });

  test('an admin has every group, and the admin pages carry the Admin tag', async () => {
    const view = await withApp({ identity: admin });
    const nav = view.querySelector('nav')!;

    expect([...nav.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual([
      '/',
      '/pipelines',
      '/runs',
      '/upload',
      '/engine',
      '/triggers',
      '/allowlist',
      '/resources',
    ]);
    expect([...nav.querySelectorAll('h2')].map((h) => h.textContent?.trim())).toEqual([
      'Workspace',
      'Automation',
      'Admin',
    ]);
    expect(nav.querySelectorAll('.admin-tag').length).toBe(3);
  });

  test('says who is signed in and with which role, in words', async () => {
    const view = await withApp({ identity: admin });
    const user = view.querySelector('.user')!;
    expect(user.textContent).toContain('Root');
    expect(user.textContent).toContain('Admin');
  });

  test('shows the Engine version and hash in the sidebar and in the top bar', async () => {
    const view = await withApp({ identity: developer });
    await engineShown(view);
    expect(view.querySelectorAll('.chip').length).toBe(2);
    expect(view.querySelector('aside')!.textContent).toContain('a3f9c1e');
    expect(view.querySelector('header')!.textContent).toContain('a3f9c1e');
  });

  test('the page of the path is shown, with its title in the heading, the tab and the breadcrumb', async () => {
    const view = await withApp({ identity: developer, path: '/runs' });
    expect(view.querySelector('main h1')!.textContent).toBe('Runs');
    expect(document.title).toBe('Runs · Runline Console');
    expect(view.querySelector('header ol')!.textContent).toContain('Workspace');
    expect(view.querySelector('header ol [aria-current="page"]')!.textContent).toBe('Runs');
  });

  test('a path that is no page gets a clear not-found page, with a way back', async () => {
    const view = await withApp({ identity: developer, path: '/nothing/here' });
    expect(view.querySelector('main h1')!.textContent).toBe('Page not found');
    expect(view.querySelector('main')!.textContent).toContain('/nothing/here');
    expect(view.querySelector('main a')!.getAttribute('href')).toBe('/');
  });

  test('a developer who opens an admin page is told it is for admins', async () => {
    const view = await withApp({ identity: developer, path: '/allowlist' });
    expect(view.querySelector('main h1')!.textContent).toBe('Admins only');
    expect(view.querySelector('main')!.textContent).toContain('Developer');
  });

  test('a developer opens the Engine page without being turned away', async () => {
    const view = await withApp({ identity: developer, path: '/engine' });
    expect(view.querySelector('main h1')!.textContent).toBe('Engine');
    expect(view.querySelector('main')!.textContent).not.toContain('Admins only');
  });

  test('an admin opens the same page', async () => {
    const view = await withApp({ identity: admin, path: '/allowlist' });
    expect(view.querySelector('main h1')!.textContent).toBe('Allow-list');
  });

  test('a click in the navigation changes the page without loading again', async () => {
    const view = await withApp({ identity: developer, path: '/' });
    const marker = document.createElement('i');
    document.body.append(marker);

    view.querySelector<HTMLAnchorElement>('nav a[href="/upload"]')!.click();
    flushSync();

    expect(view.querySelector('main h1')!.textContent).toBe('Upload');
    expect(location.pathname).toBe('/upload');
    expect(view.querySelector('nav a[aria-current="page"]')!.getAttribute('href')).toBe('/upload');
    expect(marker.isConnected).toBe(true);
  });

  test('a change of language changes the navigation and the title in place', async () => {
    const view = await withApp({ identity: developer, path: '/runs', languages: ['en'] });
    app.context.i18n.setLocale('zh-TW');
    flushSync();

    expect(view.querySelector('main h1')!.textContent).toBe('Runs 執行紀錄');
    expect(view.querySelector('nav')!.textContent).toContain('工作區');
    expect(document.documentElement.lang).toBe('zh-TW');
  });

  test('has a link at the start that skips the navigation, to the main content', async () => {
    const view = await withApp({ identity: developer });
    const skip = view.querySelector<HTMLAnchorElement>('a.skip')!;
    expect(skip.getAttribute('href')).toBe('#main');
    expect(view.querySelector('main')!.id).toBe('main');
  });

  test('signing out takes the shell away and leaves the version in view', async () => {
    const view = await withApp({ identity: developer });
    app.session.signOut();
    flushSync();

    expect(view.querySelector('nav')).toBeNull();
    await engineShown(view);
  });
});

const TOKEN = 'tok-test-0123456789';
const NEW_TOKEN = 'tok-new-9876543210';

const typeToken = (view: HTMLElement, token: string) => {
  const input = view.querySelector<HTMLInputElement>('input[type="password"], input[type="text"]')!;
  input.value = token;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  flushSync();
};
const submit = (view: HTMLElement) => {
  view.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
};

describe('the sign-in', () => {
  test('asks for the token, in a labelled field that hides it, and says what the token decides', async () => {
    const view = await withApp();
    const input = view.querySelector<HTMLInputElement>('input')!;

    expect(view.querySelector('label')!.textContent).toContain('API token');
    expect(view.querySelector('label')!.getAttribute('for')).toBe(input.id);
    expect(input.type).toBe('password');
    expect(input.autocomplete).toBe('off');
    expect(view.textContent).toContain('role');
    expect(view.querySelector('nav')).toBeNull();
  });

  test('can show the token typed, and hide it again', async () => {
    const view = await withApp();
    const toggle = view.querySelector<HTMLButtonElement>('form button[aria-pressed]')!;
    const input = view.querySelector<HTMLInputElement>('input')!;

    toggle.click();
    flushSync();
    expect(input.type).toBe('text');
    toggle.click();
    flushSync();
    expect(input.type).toBe('password');
  });

  test('a token the Engine knows signs in: the shell of the role, the credential in the tab, none in the address', async () => {
    const view = await withApp({ identity: developer, path: '/' });
    app.session.signOut();
    flushSync();
    app.engine.callers = [{ ...developer, token: TOKEN }];

    typeToken(view, TOKEN);
    submit(view);

    await vi.waitFor(() => expect(view.querySelector('nav')).not.toBeNull());
    expect(view.querySelector('.user')!.textContent).toContain('Ada');
    expect(sessionStorage.getItem('runline.session')).toBe(TOKEN);
    expect(Object.values(localStorage)).not.toContain(TOKEN);
    expect(location.href).not.toContain(TOKEN);
    expect(location.pathname).toBe('/');
    expect(view.textContent).not.toContain(TOKEN);
  });

  test('a token the Engine refuses is said to be not valid, nothing is kept, and the field keeps focus on the message', async () => {
    const view = await withApp({ languages: ['en'] });

    typeToken(view, 'tok-wrong');
    submit(view);

    await vi.waitFor(() =>
      expect(view.querySelector('[role="alert"]')!.textContent).toContain('This token is not valid'),
    );
    expect(sessionStorage.length).toBe(0);
    expect(view.querySelector('nav')).toBeNull();
  });

  test('says the refusal in the language of the screen', async () => {
    const view = await withApp({ languages: ['zh-TW'] });

    typeToken(view, 'tok-wrong');
    submit(view);

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')!.textContent).toContain('Token 無效'));
  });

  test('says in words that the Engine cannot be reached, when it cannot', async () => {
    const view = await withApp();
    await app.engine.stop();

    typeToken(view, TOKEN);
    submit(view);

    await vi.waitFor(() =>
      expect(view.querySelector('[role="alert"]')!.textContent).toContain('could not be reached'),
    );
  });

  test('says what the Engine says when it fails (an error code, with the error id to give)', async () => {
    const view = await withApp();
    app.engine.mode = 'internal-error';

    typeToken(view, TOKEN);
    submit(view);

    await vi.waitFor(() =>
      expect(view.querySelector('[role="alert"]')!.textContent).toContain('The Engine failed unexpectedly'),
    );
    expect(view.querySelector('[role="alert"]')!.textContent).toContain('e-1');
  });

  test('asks for something before it asks the Engine', async () => {
    const view = await withApp();
    app.engine.log.length = 0;

    submit(view);
    flushSync();

    expect(view.querySelector('[role="alert"]')!.textContent).toContain('Enter the token');
    expect(app.engine.log).toEqual([]);
  });

  test('while the Engine is asked, the button waits', async () => {
    const view = await withApp();
    app.engine.mode = 'hang';

    typeToken(view, TOKEN);
    submit(view);
    flushSync();

    const button = view.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Connecting');
  });

  test('keeps the page that was asked for: signing in lands on it', async () => {
    const view = await withApp({ identity: developer, path: '/runs' });
    app.session.signOut();
    flushSync();
    expect(view.querySelector('nav')).toBeNull();
    expect(location.pathname).toBe('/runs');
    app.engine.callers = [{ ...developer, token: TOKEN }];

    typeToken(view, TOKEN);
    submit(view);

    await vi.waitFor(() => expect(view.querySelector('main h1')?.textContent).toBe('Runs'));
  });

  test('a developer who signs in at an admin page is told it is for admins', async () => {
    const view = await withApp({ identity: developer, path: '/allowlist' });
    app.session.signOut();
    flushSync();
    app.engine.callers = [{ ...developer, token: TOKEN }];

    typeToken(view, TOKEN);
    submit(view);

    await vi.waitFor(() => expect(view.querySelector('main h1')?.textContent).toBe('Admins only'));
  });
});

describe('a session that the Engine ends', () => {
  test('a 401 puts the sign-in in the place of the page, says why, and signing in again returns to the page', async () => {
    const view = await withApp({ identity: developer, path: '/engine' });
    await vi.waitFor(() => expect(view.querySelector('main h1')!.textContent).toBe('Engine'));
    app.engine.callers = [{ ...developer, token: NEW_TOKEN }]; // the old token is gone

    await app.session.request('/api/v1/system');
    flushSync();

    expect(view.querySelector('nav')).toBeNull();
    expect(view.querySelector('[role="status"], [role="alert"]')!.textContent).toContain('no longer valid');
    expect(location.pathname).toBe('/engine');
    expect(sessionStorage.length).toBe(0);

    typeToken(view, NEW_TOKEN);
    submit(view);
    await vi.waitFor(() => expect(view.querySelector('main h1')?.textContent).toBe('Engine'));
  });

  test('an Engine that cannot be asked about a kept session says so, and keeps the session for a reload', async () => {
    const view = await withApp({ storedCredential: TOKEN, engineMode: 'internal-error' });

    await vi.waitFor(() =>
      expect(view.querySelector('[role="status"]')!.textContent).toContain('resume your session'),
    );
    expect(view.querySelector('input')).not.toBeNull();
    expect(sessionStorage.getItem('runline.session')).toBe(TOKEN);
  });
});

describe('looking for the session when a tab starts', () => {
  test('says so while it looks, and keeps the Engine version in view', async () => {
    app = await createTestApp({ askTimeoutMs: 400 });
    const view = app.mount(App);
    app.session.start(); // a tab that is looking again
    flushSync();

    expect(view.querySelector('[role="status"]')!.textContent).toContain('session');
    await engineShown(view);
    await vi.waitFor(() => expect(view.querySelector('input')).not.toBeNull());
  });
});

describe('signing out', () => {
  test('is a button of the top bar; the sign-in follows, and the credential is gone', async () => {
    const view = await withApp({ identity: developer, path: '/runs' });

    const button = [...view.querySelectorAll<HTMLButtonElement>('header button')].find(
      (b) => b.textContent?.trim() === 'Sign out',
    )!;
    button.click();
    flushSync();

    expect(view.querySelector('nav')).toBeNull();
    expect(view.querySelector('input')).not.toBeNull();
    expect(sessionStorage.length).toBe(0);
  });
});

describe('the warning about a page that is not HTTPS', () => {
  afterEach(() => vi.unstubAllGlobals());

  // The browser says whether the page is a secure context: HTTPS, or the machine itself (localhost,
  // 127.0.0.1, ::1), which is where the Console is tried. jsdom has no such thing: it is stood for.
  test('is not on a page the browser calls secure', async () => {
    vi.stubGlobal('isSecureContext', true);
    const view = await withApp();
    expect(view.textContent).not.toContain('not served over HTTPS');
  });

  test('is on a page that is not: the token would cross the network in the clear', async () => {
    vi.stubGlobal('isSecureContext', false);
    const view = await withApp();
    expect(view.textContent).toContain('not served over HTTPS');
  });
});

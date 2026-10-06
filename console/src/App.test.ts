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
    expect(view.textContent).toContain('登入功能尚未提供');

    [...view.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'English')!.click();
    flushSync();

    expect(view.textContent).toContain('Signing in is not available yet');
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
    app.identity.signOut();
    flushSync();

    expect(view.querySelector('nav')).toBeNull();
    await engineShown(view);
  });
});

import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { createBrowser, type TestBrowser } from '../../test-support/browser-fetch';
import { FakeEngine, type FakeCaller } from '../../test-support/fake-engine';
import { MemoryStorage } from '../../test-support/memory-storage';
import { SESSION_STORAGE_KEY } from './credential-store';
import { createSession, type Session, type SessionOptions } from './session.svelte';
import { CHANNEL_NAME, createTabSync, type TabMessage } from './tab-sync';
import { createTokenMethod } from './token-method';

// Signing in with a provider (WI-1202): the Engine keeps the session and sets a cookie; the Console
// holds no credential, only who the person is and the CSRF token of the session. The Engine is the
// Fake Engine over HTTP, with login on; the browser around `fetch` is the test browser.
const ada: FakeCaller = { name: 'ada', role: 'developer', token: 'tok-ada-0123456789' };
const ASK_TIMEOUT = 100;

let engine: FakeEngine;
let browser: TestBrowser;
const cleanup: Array<() => void> = [];

async function start(oidc: boolean) {
  engine = await FakeEngine.start(
    { version: '0.4.2', commitHash: 'a'.repeat(40), dirty: false },
    { callers: [ada], ...(oidc ? { oidc: { label: 'authentik' } } : {}) },
  );
  browser = createBrowser(engine.url);
}
afterEach(async () => {
  cleanup.splice(0).forEach((close) => close());
  await engine.stop();
});

function tab(options: Partial<SessionOptions> = {}) {
  const storage = new MemoryStorage();
  const session = createSession({
    method: createTokenMethod({ timeoutMs: 2_000 }),
    storage,
    sync: createTabSync(),
    baseUrl: engine.url,
    askTimeoutMs: ASK_TIMEOUT,
    fetch: browser.fetch,
    ...options,
  });
  cleanup.push(() => session.dispose());
  return { session, storage };
}

function overhear() {
  const channel = new BroadcastChannel(CHANNEL_NAME);
  const heard: TabMessage[] = [];
  channel.onmessage = (event) => heard.push(event.data as TabMessage);
  cleanup.push(() => channel.close());
  return heard;
}

const signedInAs = (session: Session, name: string, role: string) =>
  expect(session.state).toEqual({ status: 'authenticated', identity: { name, role } });
const settle = (ms = ASK_TIMEOUT * 3) => new Promise((resolve) => setTimeout(resolve, ms));
const holdsNothing = (storage: MemoryStorage) => {
  expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  expect(sessionStorage.length).toBe(0);
  expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
};

describe('the ways to sign in, as the Engine says', () => {
  beforeEach(() => start(true));

  test('with login on and nobody signed in: anonymous, with the way to the provider and its name', async () => {
    const { session } = tab();
    await session.start();

    expect(session.state).toEqual({ status: 'anonymous' });
    expect(session.oidc?.label).toBe('authentik');
  });

  test('the way to the provider is the Engine\'s login start, with the page to return to', async () => {
    const { session } = tab();
    await session.start();

    const url = new URL(session.oidc!.loginUrl('/runs?state=FAILED'), 'http://x');
    expect(url.origin + url.pathname).toBe(`${engine.url}/api/v1/auth/oidc/login`);
    expect(url.searchParams.get('return')).toBe('/runs?state=FAILED');
  });

  test('a page to return to that is not of this site is not sent', async () => {
    const { session } = tab();
    await session.start();

    for (const target of ['https://evil.example/', '//evil.example/', 'runs', '/a\\b', '/a b', '']) {
      expect(new URL(session.oidc!.loginUrl(target), 'http://x').searchParams.get('return')).toBe('/');
    }
  });
});

describe('a tab that reopens with a token of its own', () => {
  test('still learns that the Engine has login with a provider (the pages that depend on it)', async () => {
    await start(true);
    const { session, storage } = tab();
    storage.setItem(SESSION_STORAGE_KEY, ada.token);
    await session.start();

    signedInAs(session, 'ada', 'developer');
    expect(session.oidc?.label).toBe('authentik');
  });

  test('knows there is none when the Engine has none', async () => {
    await start(false);
    const { session, storage } = tab();
    storage.setItem(SESSION_STORAGE_KEY, ada.token);
    await session.start();

    signedInAs(session, 'ada', 'developer');
    expect(session.oidc).toBeNull();
  });
});

describe('with login off', () => {
  beforeEach(() => start(false));

  test('there is no way to the provider, and signing in with a token is as it was', async () => {
    const { session, storage } = tab();
    await session.start();

    expect(session.oidc).toBeNull();
    expect(session.state).toEqual({ status: 'anonymous' });
    expect(await session.signIn(ada.token)).toEqual({ ok: true });
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBe(ada.token);
  });

  test('the Engine that cannot be asked leaves the token as the only way', async () => {
    await engine.stop();
    const { session } = tab({ baseUrl: 'http://127.0.0.1:1' });
    await session.start();

    expect(session.oidc).toBeNull();
    expect(session.state).toEqual({ status: 'anonymous' });
    engine = await FakeEngine.start({ version: '1', commitHash: 'a'.repeat(40), dirty: false });
  });
});

describe('a person who has signed in with the provider', () => {
  beforeEach(async () => {
    await start(true);
    browser.setCookie('runline_session', engine.oidc!.signIn({ name: 'dana', role: 'admin' }));
  });

  test('is found when the page opens: the name and role from the Engine, no credential kept', async () => {
    const { session, storage } = tab();
    await session.start();

    signedInAs(session, 'dana', 'admin');
    holdsNothing(storage);
  });

  test('calls the API with the cookie and no Authorization header', async () => {
    const { session } = tab();
    await session.start();

    const response = await session.request('/api/v1/system');

    expect(response.status).toBe(200);
    expect((await response.json()).caller).toMatchObject({ name: 'dana', role: 'admin' });
    expect(engine.log.at(-1)?.authorization).toBeNull();
  });

  test('a change carries the CSRF token of the session and is not refused; a read does not need it', async () => {
    const { session } = tab();
    await session.start();

    const change = await session.request('/api/v1/runs', { method: 'POST', body: '{}' });

    expect(change.status).not.toBe(403);
    expect(change.status).not.toBe(401);
  });

  test('a file goes up asking the browser for the cookie, with the CSRF token of the session', async () => {
    const { session } = tab();
    await session.start();
    const token = (await (await browser.fetch(`${engine.url}/api/v1/auth/session`, { credentials: 'include' })).json())
      .csrfToken;

    // The browser engine of these tests has the cookie jar of its own page, not the test browser's,
    // so the Engine answers 401 here; what is checked is what the Console sent. The real browser
    // sends the cookie (e2e/oidc.e2e.ts uploads a jar as a person who signed in with the provider).
    await session.upload('/api/v1/artifacts', new Blob(['x']));

    expect(engine.log.at(-1)).toMatchObject({ path: '/api/v1/artifacts', authorization: null });
    expect(engine.csrfTokens.at(-1)).toBe(token);
  });

  test('holds nothing in the storage of the tab, nor in the channel between the tabs', async () => {
    const heard = overhear();
    const { session, storage } = tab();
    await session.start();
    await session.request('/api/v1/system');
    await session.signOut();
    await settle();

    holdsNothing(storage);
    expect(heard.filter((message) => 'credential' in message)).toEqual([]);
    expect(heard).toEqual([]);
  });

  test('is signed out when the Engine says 401: here, with the reason, and no other tab is told', async () => {
    const heard = overhear();
    const { session } = tab();
    await session.start();
    engine.oidc!.end(browser.cookie('runline_session')!);

    const response = await session.request('/api/v1/system');

    expect(response.status).toBe(401);
    expect(session.state).toEqual({ status: 'anonymous', reason: 'expired' });
    await settle();
    expect(heard).toEqual([]);
  });

  test('signing out calls the Engine, then the tab has nobody: the cookie is gone and the session with it', async () => {
    const { session } = tab();
    await session.start();
    const id = browser.cookie('runline_session')!;

    await session.signOut();

    expect(session.state).toEqual({ status: 'anonymous', reason: 'signed-out' });
    expect(engine.oidc!.has(id)).toBe(false);
    expect(browser.cookie('runline_session')).toBeUndefined();
    await expect(session.request('/api/v1/system')).rejects.toMatchObject({ status: 401 });
  });

  test('another tab finds out by its next request: a 401 puts it at the sign-in', async () => {
    const first = tab();
    const second = tab();
    await first.session.start();
    await second.session.start();

    await first.session.signOut();
    expect(second.session.state.status).toBe('authenticated');
    const response = await second.session.request('/api/v1/system');

    expect(response.status).toBe(401);
    expect(second.session.state).toEqual({ status: 'anonymous', reason: 'expired' });
  });

  test('a sign-out the Engine could not do leaves the person signed in', async () => {
    const { session } = tab();
    await session.start();
    engine.faults.push({ match: /POST \/api\/v1\/auth\/logout/, status: 500, times: 1 });

    await session.signOut();

    signedInAs(session, 'dana', 'admin');
  });

  test('a session the Engine has ended since is not found when the page opens', async () => {
    engine.oidc!.end(browser.cookie('runline_session')!);
    const { session } = tab();
    await session.start();

    expect(session.state).toEqual({ status: 'anonymous' });
    expect(session.oidc).not.toBeNull();
  });
});

describe('a token, with login on', () => {
  beforeEach(() => start(true));

  test('still signs in as it did: kept in the tab, sent as Bearer, with no CSRF token', async () => {
    const { session, storage } = tab();
    await session.start();

    expect(await session.signIn(ada.token)).toEqual({ ok: true });
    const response = await session.request('/api/v1/system');

    expect(response.status).toBe(200);
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBe(ada.token);
    expect(engine.log.at(-1)?.authorization).toBe(`Bearer ${ada.token}`);
  });

  test('a sign-out of the token is as it was: forgotten here, told to the others', async () => {
    const heard = overhear();
    const { session, storage } = tab();
    await session.start();
    await session.signIn(ada.token);

    await session.signOut();

    expect(session.state).toEqual({ status: 'anonymous', reason: 'signed-out' });
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    await settle();
    expect(heard).toContainEqual({ type: 'signed-out' });
  });
});

import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { createBrowser, type TestBrowser } from './browser-fetch';
import { FakeEngine } from './fake-engine';

// The browser of the tests: what a real browser does around `fetch` that Node's `fetch` does not,
// and that the Console's calls to an Engine with login depend on.
let engine: FakeEngine;
let browser: TestBrowser;
beforeEach(async () => {
  engine = await FakeEngine.start(
    { version: '1', commitHash: 'a'.repeat(40), dirty: false },
    { oidc: {} },
  );
  browser = createBrowser(engine.url);
});
afterEach(() => engine.stop());

describe('the cookies of the test browser', () => {
  test('are sent when the call asks for credentials, and not when it does not', async () => {
    browser.setCookie('runline_session', engine.oidc!.signIn({ name: 'ada', role: 'admin' }));
    const without = await browser.fetch(`${engine.url}/api/v1/auth/session`);
    expect(without.status).toBe(401);
    const asking = await browser.fetch(`${engine.url}/api/v1/auth/session`, { credentials: 'include' });
    expect(asking.status).toBe(200);
  });

  test('are cleared by the answer that ends them (Max-Age=0)', async () => {
    const id = engine.oidc!.signIn({ name: 'ada', role: 'admin' });
    browser.setCookie('runline_session', id);
    const session = await (
      await browser.fetch(`${engine.url}/api/v1/auth/session`, { credentials: 'include' })
    ).json();
    const out = await browser.fetch(`${engine.url}/api/v1/auth/logout`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-CSRF-Token': session.csrfToken },
    });
    expect(out.status).toBe(204);
    expect(browser.cookie('runline_session')).toBeUndefined();
  });
});

describe('the Origin of the test browser', () => {
  test('is the page the Console is served from, which a browser sends with a change', async () => {
    const id = engine.oidc!.signIn({ name: 'ada', role: 'admin' });
    browser.setCookie('runline_session', id);
    const session = await (
      await browser.fetch(`${engine.url}/api/v1/auth/session`, { credentials: 'include' })
    ).json();
    // The Fake Engine refuses a change whose Origin is not its own address: the browser adds it.
    const out = await browser.fetch(`${engine.url}/api/v1/auth/logout`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-CSRF-Token': session.csrfToken },
    });
    expect(out.status).toBe(204);
    expect(engine.oidc!.has(id)).toBe(false);
  });
});

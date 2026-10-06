// The Console in a real browser (Chrome), against a real packaged Engine: the production build as
// the Engine serves it, with the Engine's content security policy in force. See the Console section of the README.

import { mkdirSync } from 'node:fs';
import { networkInterfaces } from 'node:os';
import type { Browser, BrowserContext, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import {
  consoleLog,
  launchChrome,
  newContext,
  sessionContent,
  settled,
  signInWith,
  violations,
  watch,
} from './browser';

const engineUrl = process.env.E2E_ENGINE_URL;
if (!engineUrl) throw new Error('E2E_ENGINE_URL is not set: the tests need a running Engine');
// The Engine's API_TOKENS (`name:role:token,...`), with at least a developer and an admin in it.
const tokens = process.env.E2E_TOKENS;
if (!tokens) throw new Error('E2E_TOKENS is not set: name:role:token of a developer and an admin');
const callers = parseCallers(tokens);
const developer = callers.find((c) => c.role === 'developer')!;
const admin = callers.find((c) => c.role === 'admin')!;
const screenshots = process.env.E2E_SCREENSHOTS;
if (screenshots) mkdirSync(screenshots, { recursive: true });

let browser: Browser;
let info: { version: string; commitHash: string };

beforeAll(async () => {
  browser = await launchChrome();
  info = await (await fetch(`${engineUrl}/api/v1/info`)).json();
});
afterAll(async () => {
  await browser?.close();
});

const shortHash = () => info.commitHash.slice(0, 7);

describe('before sign-in, on the Engine itself', () => {
  test('shows the Engine version and commit hash, with no policy violation and nothing foreign', async () => {
    const context = await newContext(browser, 'zh-TW');
    const page = await context.newPage();
    const problems = await watch(page, engineUrl);

    await page.goto(`${engineUrl}/`);
    await page.getByText(`v${info.version}`).first().waitFor();

    expect(await page.locator('footer').innerText()).toContain(shortHash());
    expect(await violations(page)).toEqual([]);
    expect(problems).toEqual({ csp: [], console: [], foreign: [] });
    if (screenshots) await page.screenshot({ path: `${screenshots}/pre-login-zh-TW.png` });
    await context.close();
  });

  test('the fonts that come with the Console are loaded from the Engine', async () => {
    const context = await newContext(browser, 'en-US');
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await page.getByText(`v${info.version}`).first().waitFor();

    const loaded = await page.evaluate(async () => {
      await document.fonts.ready;
      return [...document.fonts].filter((f) => f.status === 'loaded').map((f) => f.family);
    });
    expect(loaded.some((family) => family.includes('Inter'))).toBe(true);
    expect(loaded.some((family) => family.includes('JetBrains Mono'))).toBe(true);
    await context.close();
  });

  test.each([
    [['zh-TW'], 'zh-TW'],
    [['zh-HK'], 'zh-TW'],
    [['en-US'], 'en'],
    [['en-GB', 'zh-TW'], 'en'],
    [['ja', 'fr'], 'zh-TW'],
  ])('a browser that prefers %j gets %s', async (languages, expected) => {
    const context = await newContext(browser, languages[0], languages);
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    expect(await page.evaluate(() => document.documentElement.lang)).toBe(expected);
    await context.close();
  });

  test('switching the language takes effect without a reload, is kept, and is shared by the tabs', async () => {
    const context = await newContext(browser, 'zh-TW');
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await page.getByRole('heading', { name: '登入 Runline Console' }).waitFor();
    await page.evaluate(() => ((window as unknown as { __mark: number }).__mark = 42));

    await page.getByRole('button', { name: 'English' }).click();

    await page.getByRole('heading', { name: 'Sign in to Runline Console' }).waitFor();
    expect(await page.evaluate(() => document.documentElement.lang)).toBe('en');
    expect(await page.evaluate(() => (window as unknown as { __mark: number }).__mark)).toBe(42);
    expect(await page.evaluate(() => localStorage.getItem('runline.locale'))).toBe('en');
    if (screenshots) await page.screenshot({ path: `${screenshots}/pre-login-en.png` });

    const other = await context.newPage();
    await other.goto(`${engineUrl}/`);
    await other.getByRole('heading', { name: 'Sign in to Runline Console' }).waitFor();

    await page.getByRole('button', { name: '繁體中文' }).click();
    await other.getByRole('heading', { name: '登入 Runline Console' }).waitFor(); // the other tab follows, without a reload
    await context.close();
  });

  test('the keyboard reaches the skip link, the language switch and the Engine details', async () => {
    const context = await newContext(browser, 'en-US');
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await page.getByText(`v${info.version}`).first().waitFor();

    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => document.activeElement?.textContent)).toContain('Skip to content');

    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter'); // the English button, pressed with the keyboard
    expect(await page.evaluate(() => document.documentElement.lang)).toBe('en');

    await page.getByRole('button', { name: new RegExp(`v${info.version}`) }).focus();
    await page.keyboard.press('Enter');
    const details = page.getByRole('dialog');
    await details.waitFor();
    expect(await details.innerText()).toContain(info.commitHash);

    await page.keyboard.press('Escape');
    await details.waitFor({ state: 'detached' });
    expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-expanded'))).toBe('false');
    await context.close();
  });

  test('copying the commit hash puts it on the clipboard', async () => {
    const context = await newContext(browser, 'en-US');
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: engineUrl });
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await page.getByRole('button', { name: new RegExp(`v${info.version}`) }).click();
    await page.getByRole('button', { name: 'Copy the commit hash' }).click();
    await page.getByText('Copied').waitFor();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(info.commitHash);
    await context.close();
  });
});


/** A tab of [context] on the Console, watched for what must stay empty. */
const openTab = async (context: BrowserContext, path = '/') => {
  const page = await context.newPage();
  const problems = await watch(page, engineUrl);
  const log = consoleLog(page);
  await page.goto(`${engineUrl}${path}`);
  return { page, problems, log };
};

const signedInTab = async (context: BrowserContext, who: { token: string }, path = '/') => {
  const tab = await openTab(context, path);
  expect(await settled(tab.page)).toBe('sign-in');
  await signInWith(tab.page, who.token);
  await tab.page.locator('aside nav').waitFor();
  return tab;
};

const navLinks = async (page: Page) =>
  (await page.locator('aside nav a .label').allTextContents()).map((text) => text.trim());

describe('signing in, against the real Engine', () => {
  test('a token the Engine refuses is said to be not valid; nothing is kept, nothing leaves the tab', async () => {
    const context = await newContext(browser, 'en-US');
    const { page, problems, log } = await openTab(context);
    await settled(page);

    await signInWith(page, 'tok-not-of-this-engine');

    await page.getByRole('alert').getByText('This token is not valid.').waitFor();
    expect(await sessionContent(page)).toEqual({});
    expect(await page.locator('aside').count()).toBe(0);
    expect(await violations(page)).toEqual([]);
    // The refusal is a 401 of the Engine, which the browser reports as a console error: nothing else is.
    expect(problems.csp).toEqual([]);
    expect(problems.foreign).toEqual([]);
    expect(log.join('\n')).not.toContain('tok-not-of-this-engine');
    if (screenshots) await page.screenshot({ path: `${screenshots}/sign-in-refused-en.png` });
    await context.close();
  });

  test('says the refusal in the language of the screen', async () => {
    const context = await newContext(browser, 'zh-TW', ['zh-TW']);
    const { page } = await openTab(context);
    await settled(page);

    await signInWith(page, 'tok-not-of-this-engine');

    await page.getByRole('alert').getByText('Token 無效。').waitFor();
    await context.close();
  });

  test('a developer token: the workspace and the Engine page, no admin page, the credential only in the tab', async () => {
    const context = await newContext(browser, 'en-US');
    const { page, problems, log } = await signedInTab(context, developer);

    expect(await navLinks(page)).toEqual(['Overview', 'Pipelines', 'Runs', 'Upload', 'Engine']);
    expect(await page.locator('header .user').innerText()).toContain(developer.name);
    expect(await page.locator('header .user').textContent()).toContain('Developer');
    expect(await page.locator('aside nav .admin-tag').count()).toBe(0);

    expect(await sessionContent(page)).toEqual({ 'runline.session': developer.token });
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage }))).not.toContain(developer.token);
    expect(page.url()).not.toContain(developer.token);
    expect(await page.evaluate(() => history.length)).toBeLessThan(5);
    expect(await page.content()).not.toContain(developer.token);

    await page.locator('aside').getByText(info.commitHash.slice(0, 7)).waitFor();
    await page.locator('header').getByText(info.commitHash.slice(0, 7)).waitFor();
    expect(await violations(page)).toEqual([]);
    expect(problems).toEqual({ csp: [], console: [], foreign: [] });
    expect(log.join('\n')).not.toContain(developer.token);
    if (screenshots) await page.screenshot({ path: `${screenshots}/shell-developer-en.png` });
    await context.close();
  });

  test('an admin token: every group, the admin pages marked', async () => {
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedInTab(context, admin);

    expect(await navLinks(page)).toEqual([
      'Overview', 'Pipelines', 'Runs', 'Upload', 'Engine', 'Triggers', 'Allow-list', 'Resources',
    ]);
    expect(await page.locator('aside nav .admin-tag').count()).toBe(3);
    expect(await page.locator('header .user').textContent()).toContain('Admin');
    await page.goto(`${engineUrl}/allowlist`);
    await page.getByRole('heading', { name: 'Allow-list', level: 1 }).waitFor();
    expect(problems).toEqual({ csp: [], console: [], foreign: [] });
    if (screenshots) await page.screenshot({ path: `${screenshots}/shell-admin-en.png` });
    await context.close();
  });

  test('a developer who types the address of an admin page is told it is for admins', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedInTab(context, developer, '/allowlist');

    await page.getByRole('heading', { name: 'Admins only', level: 1 }).waitFor();
    expect(await navLinks(page)).not.toContain('Allow-list');
    await context.close();
  });

  test('the Engine page is there for a developer and for an admin, with what GET /api/v1/system says', async () => {
    for (const who of [developer, admin]) {
      const context = await newContext(browser, 'en-US');
      const { page, problems } = await signedInTab(context, who, '/engine');

      await page.getByRole('heading', { name: 'Engine', level: 1 }).waitFor();
      const dl = page.locator('main dl');
      await dl.getByText('JDK').waitFor();
      const system = await (
        await fetch(`${engineUrl}/api/v1/system`, { headers: { Authorization: `Bearer ${who.token}` } })
      ).json();
      const text = await dl.innerText();
      expect(text).toContain(info.version);
      expect(text).toContain(info.commitHash);
      expect(text).toContain(system.jdk);
      expect(text).toContain('not the time it was built');
      expect(text).toContain(system.allowListVersion);
      expect(await dl.locator('time').first().getAttribute('datetime')).toBe(system.buildTime);
      expect(text).toContain(new URL(engineUrl).origin);
      expect(problems).toEqual({ csp: [], console: [], foreign: [] });
      if (screenshots) await page.screenshot({ path: `${screenshots}/engine-page-${who.role}-en.png` });
      await context.close();
    }
  });

  test('the Engine chip shows the system details once signed in, and the public fields only before', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await openTab(context);
    await settled(page);
    await page.getByRole('button', { name: new RegExp(`v${info.version}`) }).click();
    const before = await page.getByRole('dialog').innerText();
    expect(before).not.toContain('JDK');
    expect(before).not.toContain('Uptime');
    await page.keyboard.press('Escape');

    await signInWith(page, developer.token);
    await page.locator('aside nav').waitFor();
    await page.locator('aside').getByRole('button', { name: new RegExp(`v${info.version}`) }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByText('JDK').waitFor();
    const after = await dialog.innerText();
    expect(after).toContain('Build time');
    expect(after).toContain('Uptime');
    expect(after).toContain('Allow-list version');
    if (screenshots) await page.screenshot({ path: `${screenshots}/engine-chip-open-en.png` });
    await context.close();
  });

  test('signing in at an address lands on that page, and so does signing in again after a session is refused', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await openTab(context, '/runs');
    await settled(page);
    expect(new URL(page.url()).pathname).toBe('/runs');

    await signInWith(page, developer.token);
    await page.getByRole('heading', { name: 'Runs', level: 1 }).waitFor();
    expect(new URL(page.url()).pathname).toBe('/runs');

    // A credential the Engine does not accept, in place of the good one, and a new look at the Engine.
    await page.evaluate(() => sessionStorage.setItem('runline.session', 'tok-revoked'));
    await page.reload();
    await page.getByText('Your session is no longer valid').waitFor();
    expect(new URL(page.url()).pathname).toBe('/runs');
    expect(await sessionContent(page)).toEqual({});
    if (screenshots) await page.screenshot({ path: `${screenshots}/sign-in-expired-en.png` });

    await signInWith(page, developer.token);
    await page.getByRole('heading', { name: 'Runs', level: 1 }).waitFor();
    await context.close();
  });

  test('reloading one tab keeps the session, and signing out in the top bar ends it', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedInTab(context, developer, '/upload');

    await page.reload();
    expect(await settled(page)).toBe('signed-in');
    await page.getByRole('heading', { name: 'Upload', level: 1 }).waitFor();

    await page.locator('header').getByRole('button', { name: 'Sign out' }).click();
    await page.locator('form input').waitFor();
    expect(await sessionContent(page)).toEqual({});
    expect(new URL(page.url()).pathname).toBe('/upload');
    await context.close();
  });

  test('warns on a page that is not HTTPS and not the machine itself, and not on localhost', async () => {
    const port = new URL(engineUrl).port;
    const lan = Object.values(networkInterfaces())
      .flat()
      .find((address) => address && address.family === 'IPv4' && !address.internal)?.address;
    expect(lan, 'this machine has a network address of its own').toBeTruthy();

    const context = await newContext(browser, 'en-US');
    const remote = await context.newPage();
    await remote.goto(`http://${lan}:${port}/`);
    await settled(remote);
    await remote.getByText('not served over HTTPS').waitFor();
    if (screenshots) await remote.screenshot({ path: `${screenshots}/sign-in-insecure-en.png` });

    const local = await context.newPage();
    await local.goto(`http://localhost:${port}/`);
    await settled(local);
    expect(await local.getByText('not served over HTTPS').count()).toBe(0);
    await context.close();
  });

  test('the sign-in and the signed-in pages are styled and scripted under the Engine policy, with no violation', async () => {
    const context = await newContext(browser, 'zh-TW');
    const { page, problems } = await openTab(context);
    await settled(page);
    expect(await violations(page)).toEqual([]);
    await signInWith(page, admin.token);
    await page.locator('aside nav').waitFor();
    for (const path of ['/runs', '/engine', '/triggers']) {
      await page.locator(`aside nav a[href="${path}"]`).click();
      await page.waitForURL(`**${path}`);
    }
    await page.goto(`${engineUrl}/nothing-here`);
    await page.getByRole('heading', { level: 1 }).waitFor();
    expect(await violations(page)).toEqual([]);
    expect(problems).toEqual({ csp: [], console: [], foreign: [] });
    if (screenshots) await page.screenshot({ path: `${screenshots}/shell-admin-zh-TW.png` });
    await context.close();
  });
});

describe('the tabs of one session, in one browser', () => {
  test('a new tab gets the session of the tab that is signed in: the credential into its own sessionStorage, no sign-in shown', async () => {
    const context = await newContext(browser, 'en-US');
    const first = await signedInTab(context, developer);

    const { page } = await openTab(context, '/runs');
    expect(await settled(page)).toBe('signed-in');

    await page.getByRole('heading', { name: 'Runs', level: 1 }).waitFor();
    expect(await sessionContent(page)).toEqual({ 'runline.session': developer.token });
    expect(await sessionContent(first.page)).toEqual({ 'runline.session': developer.token });
    await context.close();
  });

  test('a sign-in in one tab signs in the tab that waits at the sign-in', async () => {
    const context = await newContext(browser, 'en-US');
    const a = await openTab(context);
    const b = await openTab(context);
    expect(await settled(a.page)).toBe('sign-in');
    expect(await settled(b.page)).toBe('sign-in');

    await signInWith(a.page, admin.token);

    await b.page.locator('aside nav').waitFor();
    expect(await b.page.locator('header .user').innerText()).toContain(admin.name);
    expect(await sessionContent(b.page)).toEqual({ 'runline.session': admin.token });
    await context.close();
  });

  test('a sign-out in one tab signs out every tab, and each forgets the credential', async () => {
    const context = await newContext(browser, 'en-US');
    const a = await signedInTab(context, developer);
    const b = await openTab(context);
    const c = await openTab(context);
    expect(await settled(b.page)).toBe('signed-in');
    expect(await settled(c.page)).toBe('signed-in');

    await a.page.locator('header').getByRole('button', { name: 'Sign out' }).click();

    for (const tab of [a, b, c]) {
      await tab.page.locator('form input').waitFor();
      expect(await tab.page.locator('aside').count()).toBe(0);
      expect(await sessionContent(tab.page)).toEqual({});
    }
    await context.close();
  });

  test('a session the Engine refuses, found in one tab, ends in every tab, with the reason', async () => {
    const context = await newContext(browser, 'en-US');
    const a = await signedInTab(context, developer, '/runs');
    const b = await openTab(context, '/engine');
    expect(await settled(b.page)).toBe('signed-in');

    await a.page.evaluate(() => sessionStorage.setItem('runline.session', 'tok-revoked'));
    await a.page.reload();

    await a.page.getByText('Your session is no longer valid').waitFor();
    await b.page.getByText('Your session is no longer valid').waitFor();
    expect(await sessionContent(b.page)).toEqual({});
    expect(new URL(b.page.url()).pathname).toBe('/engine'); // the page it was on is kept
    await context.close();
  });

  test('two tabs that open at the same moment, with a signed-in tab, both get the session', async () => {
    const context = await newContext(browser, 'en-US');
    await signedInTab(context, developer);

    const [x, y] = await Promise.all([context.newPage(), context.newPage()]);
    await Promise.all([x.goto(engineUrl), y.goto(engineUrl)]);

    expect(await settled(x)).toBe('signed-in');
    expect(await settled(y)).toBe('signed-in');
    await context.close();
  });

  test('two tabs that open at the same moment with nobody signed in both show the sign-in, in time, and one sign-in serves both', async () => {
    const context = await newContext(browser, 'en-US');
    const started = Date.now();

    const [x, y] = await Promise.all([context.newPage(), context.newPage()]);
    await Promise.all([x.goto(engineUrl), y.goto(engineUrl)]);

    expect(await settled(x)).toBe('sign-in');
    expect(await settled(y)).toBe('sign-in');
    expect(Date.now() - started).toBeLessThan(5_000);

    await signInWith(x, developer.token);
    await y.locator('aside nav').waitFor();
    await context.close();
  });

  test('a tab that opens while another signs out ends signed out, whichever came first', async () => {
    const context = await newContext(browser, 'en-US');
    const a = await signedInTab(context, developer);
    const b = await context.newPage();

    await Promise.all([
      b.goto(engineUrl),
      a.page.locator('header').getByRole('button', { name: 'Sign out' }).click(),
    ]);

    await a.page.locator('form input').waitFor();
    await b.locator('form input').waitFor();
    expect(await sessionContent(a.page)).toEqual({});
    expect(await sessionContent(b)).toEqual({});
    await context.close();
  });

  test('when every tab has been closed, the next one asks for the token again', async () => {
    const context = await newContext(browser, 'en-US');
    const first = await signedInTab(context, developer);
    await first.page.close();
    await context.close();

    const fresh = await newContext(browser, 'en-US'); // the browser opened again: no tab, no storage
    const { page } = await openTab(fresh);
    expect(await settled(page)).toBe('sign-in');
    expect(await sessionContent(page)).toEqual({});
    await fresh.close();
  });

  test('a tab of another origin is not given the session: the channel stops at the origin', async () => {
    const context = await newContext(browser, 'en-US');
    await signedInTab(context, developer);

    const origin = new URL(engineUrl);
    expect(origin.hostname).toBe('localhost');
    const other = await context.newPage();
    await other.goto(`http://127.0.0.1:${origin.port}/`);

    expect(await settled(other)).toBe('sign-in');
    expect(await sessionContent(other)).toEqual({});
    await context.close();
  });

  test('the channel carries the events of the session and the credential, and nothing else', async () => {
    const context = await newContext(browser, 'en-US');
    const a = await openTab(context);
    const listener = await openTab(context);
    await settled(a.page);
    await settled(listener.page);
    await listener.page.evaluate(() => {
      const heard: unknown[] = [];
      (window as unknown as { __heard: unknown[] }).__heard = heard;
      new BroadcastChannel('runline.session').onmessage = (event) => heard.push(event.data);
    });

    await signInWith(a.page, developer.token);
    await a.page.locator('aside nav').waitFor();
    await a.page.locator('header').getByRole('button', { name: 'Sign out' }).click();
    await a.page.locator('form input').waitFor();

    const heard = await listener.page.evaluate(
      () => (window as unknown as { __heard: Array<Record<string, unknown>> }).__heard,
    );
    expect(heard).toContainEqual({ type: 'signed-in', credential: developer.token });
    expect(heard).toContainEqual({ type: 'signed-out' });
    for (const message of heard) {
      expect(['signed-in', 'signed-out', 'expired', 'ask', 'answer']).toContain(message.type);
      expect(Object.keys(message).filter((key) => key !== 'type' && key !== 'credential')).toEqual([]);
    }
    await context.close();
  });
});

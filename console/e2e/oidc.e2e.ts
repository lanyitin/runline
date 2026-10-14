// "Sign in with authentik" in a real browser (Chrome), against a real packaged Engine that has login
// with a provider on, its real PostgreSQL, and a real OpenID Connect provider (Keycloak, with the
// realm of the Engine's own tests: engine/src/test/resources/oidc/runline-realm.json). The people
// are made at the provider through its administration API; the browser signs in at the provider's
// own page. See e2e/README.md for how to start the three.
//
// What it shows: an admin and a developer sign in and get the pages of their role; a person in
// neither group is refused in words; the session is a cookie that scripts cannot read and the
// Console keeps no credential anywhere; signing out puts the other tab at the sign-in on its next
// request, and signing in leads back to the page asked for; a token still signs in; a file goes up
// as a person who signed in with the provider. The endpoints are then held to the contract the
// Fake Engine is held to (contract/auth-contract.ts). The last two tests are the start of a sign-in
// failing (WI-1208): a provider that cannot be reached, and no room for another sign-in; they come
// last because the second one fills the Engine's room for sign-ins under way for ten minutes.

import { join } from 'node:path';
import type { Browser, BrowserContext, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { describeAuthMethodsContract, describeOidcSessionContract } from '../contract/auth-contract';
import { parseCallers } from '../contract/system-contract';
import { describeUsersContract } from '../contract/users-contract';
import {
  PHONE_360,
  PHONE_390,
  WIDEST_NARROW,
  horizontalOverflow,
  launchChrome,
  newContext,
  sessionContent,
  violations,
} from './browser';
import { dialogIn, trouble } from './phone';

const engineUrl = process.env.E2E_OIDC_ENGINE_URL;
if (!engineUrl) {
  throw new Error('E2E_OIDC_ENGINE_URL is not set: the Engine with login on (its public address)');
}
const providerUrl = process.env.E2E_OIDC_PROVIDER_URL;
if (!providerUrl) {
  throw new Error('E2E_OIDC_PROVIDER_URL is not set: the address of the Keycloak the Engine uses');
}
const tokens = process.env.E2E_TOKENS;
if (!tokens) throw new Error('E2E_TOKENS is not set: the API_TOKENS of the Engine');
const root = parseCallers(tokens).find((c) => c.role === 'admin')!;
const jars = process.env.E2E_JARS;
if (!jars) throw new Error('E2E_JARS is not set: the directory of the sample jars');

// The two ways the start of a sign-in can fail need an Engine of their own to be real: one whose
// provider cannot be reached, and a limit of sign-ins under way small enough to reach (the limit
// of the Engine at engineUrl, RUNLINE_OIDC_MAX_PENDING_LOGINS).
const unreachableEngineUrl = process.env.E2E_OIDC_UNREACHABLE_ENGINE_URL;
if (!unreachableEngineUrl) {
  throw new Error(
    'E2E_OIDC_UNREACHABLE_ENGINE_URL is not set: an Engine with login on whose provider cannot be reached',
  );
}
const maxPendingLogins = Number(process.env.E2E_OIDC_MAX_PENDING_LOGINS);
if (!Number.isInteger(maxPendingLogins) || maxPendingLogins < 1) {
  throw new Error('E2E_OIDC_MAX_PENDING_LOGINS is not set: RUNLINE_OIDC_MAX_PENDING_LOGINS of the Engine');
}

const REALM = 'runline';
const ADMIN_GROUP = 'runline-admin';
const DEVELOPER_GROUP = 'runline-developer';

// --- the provider's administration API: the people of the tests -------------------------------

interface Person {
  username: string;
  password: string;
}

async function providerAdmin(path: string, init: RequestInit = {}) {
  const token = await fetch(`${providerUrl}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: 'admin',
      password: 'admin',
    }),
  }).then(async (r) => (await r.json()).access_token as string);
  return fetch(`${providerUrl}/admin/realms/${REALM}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...init.headers },
  });
}

let counter = 0;
async function makePerson(groups: string[], suffix = ''): Promise<Person> {
  const username = `e2e${Date.now().toString(36)}${counter++}${suffix}`;
  const password = `pw-${username}`;
  const created = await providerAdmin('/users', {
    method: 'POST',
    body: JSON.stringify({
      username,
      enabled: true,
      email: `${username}@example.test`,
      emailVerified: true,
      firstName: 'First',
      lastName: username,
      credentials: [{ type: 'password', value: password, temporary: false }],
    }),
  });
  expect(created.status).toBe(201);
  const id = created.headers.get('location')!.split('/').pop()!;
  for (const group of groups) {
    const found = await providerAdmin(`/groups?search=${group}&exact=true`);
    const groupId = ((await found.json()) as Array<{ id: string; name: string }>).find(
      (g) => g.name === group,
    )!.id;
    expect((await providerAdmin(`/users/${id}/groups/${groupId}`, { method: 'PUT' })).status).toBe(204);
  }
  return { username, password };
}

// --- the browser -------------------------------------------------------------------------------

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
});
afterAll(async () => {
  await browser?.close();
});

const signInLink = (page: Page) => page.locator('a[href*="/api/v1/auth/oidc/login"]');

/** At the provider's own page: types the person's name and password. */
async function atProvider(page: Page, who: Person) {
  await page.locator('input[name="username"]').fill(who.username);
  await page.locator('input[name="password"]').fill(who.password);
  await page.locator('#kc-login').click();
}

/** A tab of [context] opened at [path], signed in with the provider as [who]. */
async function signedInWithProvider(context: BrowserContext, who: Person, path = '/') {
  const page = await context.newPage();
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  await page.goto(`${engineUrl}${path}`);
  await signInLink(page).click();
  await atProvider(page, who);
  // Attached, not visible: on a phone the navigation is a drawer that is closed.
  await page.locator('aside nav').waitFor({ state: 'attached', timeout: 20_000 });
  return { page, errors };
}

const sessionCookie = async (context: BrowserContext) =>
  (await context.cookies(engineUrl)).find((c) => c.name === 'runline_session');

describe('an admin who signs in with the provider', () => {
  test('lands on the page asked for, with the pages of an admin, and the Console holds no credential', async () => {
    const dana = await makePerson([ADMIN_GROUP]);
    const context = await newContext(browser, 'en-US');
    const { page, errors } = await signedInWithProvider(context, dana, '/allowlist');

    expect(new URL(page.url()).pathname).toBe('/allowlist');
    expect(page.url()).not.toMatch(/code=|state=|session|token/i);
    expect(await page.locator('.user').innerText()).toContain(dana.username);
    expect(await page.locator('aside nav a[href="/allowlist"]').count()).toBe(1);
    expect(await page.locator('main h1').innerText()).toContain('Allow-list');

    // The cookie is the Engine's, out of reach of scripts; the Console keeps nothing of its own.
    const cookie = await sessionCookie(context);
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe('Lax');
    expect(await page.evaluate(() => document.cookie)).not.toContain('runline_session');
    expect(await sessionContent(page)).toEqual({});
    expect(await page.evaluate(() => Object.keys(localStorage))).not.toContain('runline.session');
    expect(await page.content()).not.toContain(cookie!.value);

    expect(errors).toEqual([]);
    expect(await violations(page)).toEqual([]);
    await context.close();
  });

  test('can change something: a jar goes up with the cookie and the CSRF token', async () => {
    const dana = await makePerson([ADMIN_GROUP]);
    const context = await newContext(browser, 'en-US');
    const { page } = await signedInWithProvider(context, dana, '/upload');

    await page.setInputFiles('input[type="file"]', join(jars, 'demo-slow.jar'));
    await page.locator('button.submit').click();
    await page.locator('.result').waitFor();
    expect(await page.locator('.result').innerText()).toMatch(
      /A new version was made|You uploaded this jar before/,
    );
    await context.close();
  });
});

describe('a developer who signs in with the provider', () => {
  test('gets the pages of a developer only: no admin page in the navigation, and the address is refused', async () => {
    const eli = await makePerson([DEVELOPER_GROUP]);
    const context = await newContext(browser, 'en-US');
    const { page } = await signedInWithProvider(context, eli, '/');

    expect(await page.locator('.user').innerText()).toContain(eli.username);
    expect(await page.locator('aside nav a[href="/allowlist"]').count()).toBe(0);
    expect(await page.locator('aside nav a[href="/resources"]').count()).toBe(0);
    expect(await page.locator('aside nav a[href="/triggers"]').count()).toBe(0);

    await page.goto(`${engineUrl}/allowlist`);
    await page.locator('main h1').filter({ hasText: 'Admins only' }).waitFor();
    await context.close();
  });
});

describe('a person in neither group', () => {
  test('is refused in words, back at the sign-in, with nothing in the address and no session', async () => {
    const nobody = await makePerson([]);
    const context = await newContext(browser, 'en-US');
    const page = await context.newPage();
    await page.goto(`${engineUrl}/runs`);
    await signInLink(page).click();
    await atProvider(page, nobody);

    const failure = page.locator('[data-login-error]');
    await failure.waitFor({ timeout: 20_000 });
    expect(await failure.innerText()).toContain('Ask an administrator');
    expect(new URL(page.url()).search).toBe('');
    expect(await page.locator('aside nav').count()).toBe(0);
    expect(await sessionCookie(context)).toBeUndefined();
    await context.close();
  });

  test('is told in Chinese when the language is Chinese', async () => {
    const nobody = await makePerson([]);
    const context = await newContext(browser, 'zh-TW', ['zh-TW']);
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    expect((await signInLink(page).innerText()).trim()).toBe('使用 authentik 登入');
    await signInLink(page).click();
    await atProvider(page, nobody);
    await page.locator('[data-login-error]').waitFor({ timeout: 20_000 });
    expect(await page.locator('[data-login-error]').innerText()).toContain('向管理員申請');
    await context.close();
  });
});

describe('signing out', () => {
  test('puts the tab at the sign-in, and the other tab by its next request, on the page it was on', async () => {
    const dana = await makePerson([ADMIN_GROUP]);
    const context = await newContext(browser, 'en-US');
    const { page: first } = await signedInWithProvider(context, dana, '/runs');
    const second = await context.newPage();
    await second.goto(`${engineUrl}/runs`);
    await second.locator('aside nav').waitFor();

    await first.locator('header button', { hasText: 'Sign out' }).click();
    await signInLink(first).waitFor();
    expect(await sessionCookie(context)).toBeUndefined();

    // The second tab does not know yet; its next request is refused, and it goes to the sign-in.
    await second.locator('aside nav a[href="/pipelines"]').click();
    await signInLink(second).waitFor({ timeout: 20_000 });
    expect(new URL(second.url()).pathname).toBe('/pipelines');
    expect(new URL((await signInLink(second).getAttribute('href'))!, engineUrl).searchParams.get('return')).toBe(
      '/pipelines',
    );

    // Signing in again leads back to that page. The provider may still know the person and send
    // the browser straight back, or ask again: both are its business.
    await signInLink(second).click();
    const username = second.locator('input[name="username"]');
    const shell = second.locator('aside nav');
    await username.or(shell).first().waitFor({ timeout: 20_000 });
    if ((await shell.count()) === 0) await atProvider(second, dana);
    await shell.waitFor({ timeout: 20_000 });
    expect(new URL(second.url()).pathname).toBe('/pipelines');
    await context.close();
  });
});

describe('the token', () => {
  test('still signs in beside the provider: kept in the tab, none of it in a cookie', async () => {
    const context = await newContext(browser, 'en-US');
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await signInLink(page).waitFor();

    await page.locator('form input').fill(root.token);
    await page.locator('form button[type="submit"]').click();
    await page.locator('aside nav').waitFor();

    expect(await page.locator('.user').innerText()).toContain(root.name);
    expect(Object.values(await sessionContent(page))).toContain(root.token);
    expect(await sessionCookie(context)).toBeUndefined();
    await context.close();
  });
});

// The endpoints, held to the contract the Fake Engine is held to. A session is made by the browser
// signing in at the provider; its cookie is the credential the contract's calls carry.
describeAuthMethodsContract('the real Engine with OIDC', {
  baseUrl: () => engineUrl,
  oidcEnabled: true,
});
describeOidcSessionContract('the real Engine with OIDC', {
  baseUrl: () => engineUrl,
  origin: () => engineUrl,
  signIn: async () => {
    const who = await makePerson([DEVELOPER_GROUP]);
    const context = await newContext(browser, 'en-US');
    await signedInWithProvider(context, who, '/');
    const cookie = (await sessionCookie(context))!.value;
    await context.close();
    return { cookie, name: who.username, role: 'developer' };
  },
});


describeUsersContract('the real Engine with OIDC', {
  baseUrl: () => engineUrl,
  origin: () => engineUrl,
  callers: () => parseCallers(tokens),
  signIn: async (role) => {
    const who = await makePerson([role === 'admin' ? ADMIN_GROUP : DEVELOPER_GROUP]);
    const context = await newContext(browser, 'en-US');
    await signedInWithProvider(context, who, '/');
    const cookie = (await sessionCookie(context))!.value;
    await context.close();
    return { cookie, name: who.username, role };
  },
});

// WI-1206: an admin looks at the people who signed in, in the Console, and disables one. The
// developer's tab goes back to the sign-in on its next action; signing in again is refused in words
// (the provider still allows it: the refusal is the Engine's); enabling brings the person back.
describe('the administration of users in the Console', () => {
  const userRow = (page: Page, who: Person) =>
    page.locator('table.users tbody tr').filter({ has: page.locator('.name', { hasText: who.username }) });
  const confirm = (page: Page) => page.locator('[role="dialog"] button.confirm');

  /** Signs in again at the sign-in link: the provider may ask for the password or send the browser straight back. */
  async function signInAgain(page: Page, who: Person) {
    await signInLink(page).click();
    const username = page.locator('input[name="username"]');
    const done = page.locator('aside nav').or(page.locator('[data-login-error]'));
    await username.or(done).first().waitFor({ timeout: 20_000 });
    if ((await username.count()) > 0) await atProvider(page, who);
  }

  test('an admin disables a developer and enables them again; the developer is turned away at the next action and cannot sign in until then', async () => {
    const dana = await makePerson([ADMIN_GROUP]);
    const eli = await makePerson([DEVELOPER_GROUP]);
    const danaContext = await newContext(browser, 'en-US');
    const eliContext = await newContext(browser, 'en-US');
    const { page: eliPage } = await signedInWithProvider(eliContext, eli, '/runs');
    const { page: adminPage, errors } = await signedInWithProvider(danaContext, dana, '/users');

    // The page lists the person, as plain text, with the sessions they have.
    await adminPage.locator('main h1', { hasText: 'Users' }).waitFor();
    expect(await adminPage.locator('aside nav a[href="/users"]').count()).toBe(1);
    const eliRow = userRow(adminPage, eli);
    await eliRow.waitFor();
    expect(await eliRow.locator('.status').innerText()).toContain('Active');
    expect(Number(await eliRow.locator('.count').innerText())).toBeGreaterThanOrEqual(1);

    // Disabling asks first, and says what follows.
    await eliRow.getByRole('button', { name: 'Disable' }).click();
    const dialog = adminPage.locator('[role="dialog"]');
    await dialog.waitFor();
    expect(await dialog.innerText()).toContain('every session at once');
    await confirm(adminPage).click();
    await eliRow.locator('.status', { hasText: 'Disabled' }).waitFor();
    expect(await adminPage.locator('[data-result]').innerText()).toMatch(/Disabled .* session/);
    expect(await eliRow.locator('.count').innerText()).toBe('0');

    // The developer's tab does not know; its next action is refused, and it is at the sign-in.
    await eliPage.locator('aside nav a[href="/pipelines"]').click();
    await signInLink(eliPage).waitFor({ timeout: 20_000 });
    expect(await eliPage.locator('aside nav').count()).toBe(0);

    // Signing in again is refused in words, without technical detail, though the provider allows it.
    await signInAgain(eliPage, eli);
    const refusal = eliPage.locator('[data-login-error]');
    await refusal.waitFor({ timeout: 20_000 });
    expect(await refusal.innerText()).toContain('disabled your account');
    expect(await refusal.innerText()).not.toMatch(/user_disabled|\b4\d\d\b/);
    expect(new URL(eliPage.url()).search).toBe('');
    expect(await eliPage.locator('aside nav').count()).toBe(0);

    // Enabling brings the person back.
    await eliRow.getByRole('button', { name: 'Enable' }).click();
    await adminPage.locator('[role="dialog"]').waitFor();
    await confirm(adminPage).click();
    await eliRow.locator('.status', { hasText: 'Active' }).waitFor();
    await signInAgain(eliPage, eli);
    await eliPage.locator('aside nav').waitFor({ timeout: 20_000 });
    expect(await eliPage.locator('.user').innerText()).toContain(eli.username);

    expect(errors).toEqual([]);
    expect(await violations(adminPage)).toEqual([]);
    await danaContext.close();
    await eliContext.close();
  });

  test('an admin ends one session of a person: their tab is turned away at the next action, and they are not disabled', async () => {
    const dana = await makePerson([ADMIN_GROUP]);
    const eli = await makePerson([DEVELOPER_GROUP]);
    const danaContext = await newContext(browser, 'en-US');
    const eliContext = await newContext(browser, 'en-US');
    const { page: eliPage } = await signedInWithProvider(eliContext, eli, '/runs');
    const { page: adminPage } = await signedInWithProvider(danaContext, dana, '/users');

    const eliRow = userRow(adminPage, eli);
    await eliRow.waitFor();
    await eliRow.getByRole('button', { name: 'Sessions' }).click();
    const dialog = adminPage.locator('[role="dialog"]');
    await dialog.locator('tbody tr').first().waitFor();
    await dialog.locator('tbody tr').first().getByRole('button', { name: 'End' }).click();
    await dialog.getByText('No valid sessions.').waitFor();
    await dialog.locator('button[aria-label="Close"]').click();
    expect(await eliRow.locator('.status').innerText()).toContain('Active');

    await eliPage.locator('aside nav a[href="/pipelines"]').click();
    await signInLink(eliPage).waitFor({ timeout: 20_000 });
    await signInAgain(eliPage, eli);
    await eliPage.locator('aside nav').waitFor({ timeout: 20_000 });
    await danaContext.close();
    await eliContext.close();
  });

  test('a developer does not see the page: not in the navigation, and the address is turned away', async () => {
    const eli = await makePerson([DEVELOPER_GROUP]);
    const context = await newContext(browser, 'en-US');
    const { page } = await signedInWithProvider(context, eli, '/');

    expect(await page.locator('aside nav a[href="/users"]').count()).toBe(0);
    await page.goto(`${engineUrl}/users`);
    await page.locator('main h1').filter({ hasText: 'Admins only' }).waitFor();
    expect(await page.locator('table.users').count()).toBe(0);
    await context.close();
  });
});

// WI-63 (ADR-048): the same administration on a phone. The sign-in with the provider, the page of the
// users (a name without a space is the longest thing it shows), the dialog of the sessions of a
// person and the questions of disabling one are in the window, and the page does not scroll sideways.
describe.each([
  ['360px', PHONE_360],
  ['390px', PHONE_390],
  ['767px', WIDEST_NARROW],
])('the administration of users on a phone at %s', (_name, viewport) => {
  test('the sign-in with the provider does not scroll sideways, and its link can be pressed', async () => {
    const context = await newContext(browser, 'en-US', undefined, viewport);
    const page = await context.newPage();
    await page.goto(`${engineUrl}/runs`);
    await signInLink(page).waitFor();

    expect(await horizontalOverflow(page)).toBe(0);
    expect(await trouble(page, 'body')).toEqual([]);
    await context.close();
  });

  test('the page of users holds its first column, and the sessions and the questions are in the window', async () => {
    const dana = await makePerson([ADMIN_GROUP]);
    const eli = await makePerson([DEVELOPER_GROUP], 'long'.repeat(10));
    const eliContext = await newContext(browser, 'en-US');
    await signedInWithProvider(eliContext, eli, '/runs');
    const danaContext = await newContext(browser, 'en-US', undefined, viewport);
    const { page } = await signedInWithProvider(danaContext, dana, '/users');

    const row = page.locator('table.users tbody tr').filter({ has: page.locator('.name', { hasText: eli.username }) });
    await row.waitFor();
    expect(await horizontalOverflow(page)).toBe(0);
    expect(await trouble(page, 'main')).toEqual([]);
    const table = page.locator('table.users');
    expect(await table.evaluate((el) => getComputedStyle(el.querySelector('tbody td')!).position)).toBe('sticky');
    expect(await table.evaluate((el) => getComputedStyle(el.parentElement!).overflowX)).not.toBe('visible');

    await row.getByRole('button', { name: 'Sessions' }).click();
    const dialog = await dialogIn(page, viewport);
    await dialog.locator('tbody tr').first().waitFor();
    expect(await horizontalOverflow(page)).toBe(0);
    expect(await trouble(page, '[role=dialog]')).toEqual([]);
    await dialog.locator('button[aria-label="Close"]').click();

    await row.getByRole('button', { name: 'Disable' }).click();
    await dialogIn(page, viewport);
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    await danaContext.close();
    await eliContext.close();
  });
});

// The start of a sign-in is a page the browser is sent to, so when it fails it must come back to
// the Console with words, not show the Engine's answer (WI-1208, ADR-031).
describe('the start of a sign-in that fails', () => {
  test('a provider that cannot be reached: back at the sign-in, in words, with nothing in the address', async () => {
    const context = await newContext(browser, 'en-US');
    const page = await context.newPage();
    await page.goto(`${unreachableEngineUrl}/runs`);
    await signInLink(page).click();

    const failure = page.locator('[data-login-error]');
    await failure.waitFor({ timeout: 20_000 });
    expect(await failure.innerText()).toContain('cannot be reached');
    expect(new URL(page.url()).origin).toBe(new URL(unreachableEngineUrl).origin);
    expect(new URL(page.url()).search).toBe('');
    expect(await signInLink(page).count()).toBe(1);
    expect((await context.cookies()).find((c) => c.name === 'runline_session')).toBeUndefined();
    await context.close();
  });

  test.each([
    ['360px', PHONE_360],
    ['390px', PHONE_390],
    ['767px', WIDEST_NARROW],
  ])('the words of the failure fit a phone at %s', async (_name, viewport) => {
    const context = await newContext(browser, 'en-US', undefined, viewport);
    const page = await context.newPage();
    await page.goto(`${unreachableEngineUrl}/runs`);
    await signInLink(page).click();
    await page.locator('[data-login-error]').waitFor({ timeout: 20_000 });

    expect(await horizontalOverflow(page)).toBe(0);
    expect(await trouble(page, 'body')).toEqual([]);
    await context.close();
  });

  test('no room for another sign-in: back at the sign-in, in words, with nothing in the address', async () => {
    // Use up the room; the start answers with the Console's address and nothing else in it.
    let refusal: string | null = null;
    for (let i = 0; i <= maxPendingLogins && refusal === null; i++) {
      const start = await fetch(`${engineUrl}/api/v1/auth/oidc/login`, { redirect: 'manual' });
      expect(start.status).toBe(302);
      const location = start.headers.get('location')!;
      if (location.startsWith('/')) refusal = location;
    }
    expect(refusal).toBe('/?login_error=too_many_logins');

    const context = await newContext(browser, 'zh-TW', ['zh-TW']);
    const page = await context.newPage();
    await page.goto(`${engineUrl}/runs`);
    await signInLink(page).click();
    const failure = page.locator('[data-login-error]');
    await failure.waitFor({ timeout: 20_000 });
    expect(await failure.innerText()).toContain('人數過多');
    expect(new URL(page.url()).search).toBe('');
    expect((await context.cookies()).find((c) => c.name === 'runline_session')).toBeUndefined();
    await context.close();
  });
});

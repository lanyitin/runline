// The resource roles of a pipeline in a real browser (Chrome), against a real packaged Engine with
// its PostgreSQL and the real sample jar `demo-roles.jar` (WI-1013, ADR-025): the page of a
// pipeline lists its roles with their type and purpose (text from the jar), a developer and an
// admin choose, replace and unbind the shared resource of each role from a list that holds the
// resources of the role's type only, a disabled resource is marked, a refused run leads to the
// role, an admin who can see two uploaders chooses whose bindings, a developer has no way to
// define a resource, and the resources page names the roles that use a resource.
//
// Needs what admin.e2e.ts needs (the Engine, E2E_TOKENS with two developers and an admin, E2E_JARS
// holding demo-roles.jar). What a test makes it removes.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Browser, BrowserContext, Locator, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { launchChrome, newContext, settled, signInWith, watch } from './browser';

const need = (name: string, why: string) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set: ${why}`);
  return value;
};
const engineUrl = need('E2E_ENGINE_URL', 'the tests need a running Engine');
const callers = parseCallers(need('E2E_TOKENS', 'name:role:token of two developers and an admin'));
const [ada, bob] = callers.filter((c) => c.role === 'developer');
const root = callers.find((c) => c.role === 'admin')!;
const jars = need('E2E_JARS', 'the directory of the sample jars');
const screenshots = process.env.E2E_SCREENSHOTS;

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
});
afterAll(async () => {
  await browser?.close();
});

const stamp = () => Date.now().toString(36);
let counter = 0;
const fresh = (prefix: string) => `${prefix}-${stamp()}${(counter++).toString(36)}`;
const shot = async (page: Page, name: string) => {
  if (screenshots) await page.screenshot({ path: `${screenshots}/${name}.png`, fullPage: true });
};

const api = (who: { token: string }, path: string, init: RequestInit = {}) =>
  fetch(`${engineUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/json', ...init.headers },
  });
const json = async (response: Response) => (await response.json()) as any;

const ROLES = ['report-store', 'ticket-counter', 'scratch'];
const rolesPath = (hash: string, uploader?: string) =>
  `/api/v1/definitions/${hash}/demo-roles/resource-bindings${uploader ? `?uploader=${uploader}` : ''}`;
const pagePath = (hash: string, uploader?: string, role?: string) =>
  `/pipelines/${hash}?pipeline=demo-roles${uploader ? `&uploader=${uploader}` : ''}${role ? `&role=${role}` : ''}`;

async function putJar(who: { token: string }) {
  const response = await fetch(`${engineUrl}/api/v1/artifacts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/octet-stream' },
    body: readFileSync(join(jars, 'demo-roles.jar')) as unknown as BodyInit,
  });
  expect([200, 201]).toContain(response.status);
  return ((await response.json()) as { contentHash: string }).contentHash;
}

/** A binding belongs to an uploader and a pipeline name, so the ones an earlier test left go first. */
async function clearBindings(hash: string) {
  for (const who of [ada, bob]) {
    for (const role of ROLES) {
      await api(who, `/api/v1/definitions/${hash}/demo-roles/resource-bindings/${role}`, { method: 'DELETE' });
    }
  }
}

const made: string[] = [];
async function define(type: 'file' | 'counter', options: { enabled?: boolean } = {}) {
  const name = fresh(`e2e-${type}`);
  const body: Record<string, unknown> = { name, capacity: 1, type };
  if (type === 'file') body.settings = { path: `e2e/${name}.txt` };
  expect((await api(root, '/api/v1/resources', { method: 'POST', body: JSON.stringify(body) })).status).toBe(201);
  made.push(name);
  if (options.enabled === false) {
    expect((await api(root, `/api/v1/resources/${name}`, { method: 'PATCH', body: JSON.stringify({ enabled: false }) })).status).toBe(200);
  }
  return name;
}
async function cleanUp(hash: string) {
  await clearBindings(hash);
  for (const name of made.splice(0)) await api(root, `/api/v1/resources/${name}`, { method: 'DELETE' });
}

async function signedIn(context: BrowserContext, who: { token: string }, path: string) {
  const page = await context.newPage();
  const problems = await watch(page, engineUrl);
  await page.goto(`${engineUrl}${path}`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, who.token);
  await page.locator('aside nav').waitFor();
  return { page, problems };
}

const role = (page: Page, name: string): Locator => page.locator(`li.role[data-role="${name}"]`);
const optionsOf = async (page: Page, name: string) =>
  (await role(page, name).locator('select option').allInnerTexts()).map((t) => t.trim());
const bindingOf = async (who: { token: string }, hash: string, name: string, uploader?: string) =>
  (await json(await api(who, rolesPath(hash, uploader)))).roles.find((r: any) => r.role === name).binding;

describe('the roles of a pipeline, for a developer', () => {
  test('each role is listed with its type and purpose; the list holds the resources of its type only, a disabled one marked; a role is bound, replaced and unbound', async () => {
    const hash = await putJar(ada);
    await cleanUp(hash);
    const first = await define('file');
    const second = await define('file');
    const off = await define('file', { enabled: false });
    const counter = await define('counter');
    const context = await newContext(browser, 'en-US');
    try {
      const { page, problems } = await signedIn(context, ada, pagePath(hash, ada.name));
      await page.getByRole('heading', { name: 'demo-roles', level: 1 }).waitFor();
      await role(page, 'report-store').waitFor();

      expect(await page.locator('li.role .role-name').allInnerTexts()).toEqual(expect.arrayContaining(ROLES));
      expect(await role(page, 'report-store').locator('.role-type').innerText()).toBe('File');
      expect(await role(page, 'report-store').locator('.role-purpose').innerText()).toBe('Where the weekly report is written');
      expect(await role(page, 'ticket-counter').locator('.role-type').innerText()).toBe('Counter');
      expect(await role(page, 'scratch').locator('.role-type').innerText()).toBe('any type');
      expect(await role(page, 'report-store').locator('.role-state').innerText()).toBe('Not bound');
      expect(await page.locator('.cannot-run').innerText()).toContain('No run of this pipeline can be created yet');
      await shot(page, 'wi1013-roles-unbound-en');

      // The list of a role holds the resources of its type: a counter is not offered for a file.
      const files = await optionsOf(page, 'report-store');
      expect(files).toEqual(expect.arrayContaining([first, second, `${off} (disabled)`]));
      expect(files).not.toContain(counter);
      const counters = await optionsOf(page, 'ticket-counter');
      expect(counters).toContain(counter);
      expect(counters).not.toContain(first);
      expect(counters).not.toContain(second);

      // Bind.
      const store = role(page, 'report-store');
      await store.locator('select').selectOption(first);
      await store.getByRole('button', { name: 'Save' }).click();
      await expect.poll(async () => store.locator('.role-result').innerText().catch(() => '')).toBe(`report-store is now bound to ${first}.`);
      expect(await store.locator('.role-current').innerText()).toContain(`Bound to ${first}`);
      expect(await store.locator('.role-bound-by').innerText()).toContain(ada.name);
      expect(await store.locator('.role-state').innerText()).toBe('Available');
      expect((await bindingOf(ada, hash, 'report-store'))?.resource).toBe(first);
      await shot(page, 'wi1013-role-bound-en');

      // Replace.
      await store.locator('select').selectOption(second);
      await store.getByRole('button', { name: 'Save' }).click();
      await expect.poll(async () => store.locator('.role-current').innerText()).toContain(`Bound to ${second}`);
      expect((await bindingOf(ada, hash, 'report-store'))?.resource).toBe(second);

      // A disabled resource can be chosen, and is marked as what keeps a run from being made.
      await store.locator('select').selectOption(off);
      await store.getByRole('button', { name: 'Save' }).click();
      await expect.poll(async () => store.locator('.role-state').innerText()).toBe('Disabled');
      expect(await store.locator('.role-problem').innerText()).toContain(`${off} is disabled`);
      await shot(page, 'wi1013-role-disabled-en');

      // Unbind.
      await store.getByRole('button', { name: 'Unbind' }).click();
      await expect.poll(async () => store.locator('.role-result').innerText().catch(() => '')).toBe('report-store is not bound any more.');
      expect(await store.locator('.role-state').innerText()).toBe('Not bound');
      expect(await bindingOf(ada, hash, 'report-store')).toBeNull();

      expect(problems.console).toEqual([]);
      expect(problems.csp).toEqual([]);
    } finally {
      await context.close();
      await cleanUp(hash);
    }
  }, 90_000);

  test('a developer has no way to define or delete a resource, and sees only their own bindings', async () => {
    const hash = await putJar(ada);
    await putJar(bob);
    await cleanUp(hash);
    const file = await define('file');
    await api(bob, `/api/v1/definitions/${hash}/demo-roles/resource-bindings/report-store`, {
      method: 'PUT',
      body: JSON.stringify({ resource: file }),
    });
    const context = await newContext(browser, 'en-US');
    try {
      const { page } = await signedIn(context, ada, pagePath(hash, ada.name));
      await role(page, 'report-store').waitFor();
      // No door to the page where resources are made, and no picker of uploaders.
      expect(await page.locator('aside nav a[href="/resources"]').count()).toBe(0);
      expect(await page.locator('section.bindings a[href="/resources"]').count()).toBe(0);
      expect(await page.locator('select.uploader').count()).toBe(0);
      // Bob's binding is not in what ada sees.
      expect(await role(page, 'report-store').locator('.role-state').innerText()).toBe('Not bound');
      // Another's version is a version that is not there.
      await page.goto(`${engineUrl}${pagePath(hash, bob.name)}`);
      await page.getByText('No such pipeline version').or(page.getByText('not found')).first().waitFor();
      expect(await page.locator('li.role').count()).toBe(0);
      await page.goto(`${engineUrl}/resources`);
      await page.getByRole('heading', { name: 'Admins only' }).waitFor();
    } finally {
      await context.close();
      await cleanUp(hash);
    }
  }, 90_000);

  test('a run that is refused for a shared resource leads to the role; the roles are in the language of the screen', async () => {
    const hash = await putJar(ada);
    await cleanUp(hash);
    const file = await define('file', { enabled: false });
    const counter = await define('counter');
    for (const [name, resource] of [['report-store', file], ['ticket-counter', counter], ['scratch', counter]]) {
      expect(
        (await api(ada, `/api/v1/definitions/${hash}/demo-roles/resource-bindings/${name}`, { method: 'PUT', body: JSON.stringify({ resource }) })).status,
      ).toBe(200);
    }
    const context = await newContext(browser, 'en-US');
    try {
      const { page } = await signedIn(context, ada, `/runs/new?contentHash=${hash}&pipeline=demo-roles&uploader=${ada.name}`);
      await page.locator('button.submit').click();
      const unavailable = page.locator('.unavailable');
      await unavailable.waitFor();
      expect(await page.locator('[role="alert"]').first().innerText()).toContain('shared resource');
      expect(await unavailable.locator('a').allInnerTexts()).toEqual(['report-store · Disabled']);
      await shot(page, 'wi1013-run-refused-en');

      await unavailable.locator('a').click();
      await role(page, 'report-store').waitFor();
      expect(page.url()).toContain('role=report-store');
      await expect.poll(async () => role(page, 'report-store').getAttribute('aria-current')).toBe('true');
      expect(await role(page, 'ticket-counter').getAttribute('aria-current')).toBeNull();
    } finally {
      await context.close();
    }

    const zh = await newContext(browser, 'zh-TW', ['zh-TW']);
    try {
      const { page } = await signedIn(zh, ada, pagePath(hash, ada.name));
      await role(page, 'report-store').waitFor();
      expect(await role(page, 'report-store').locator('.role-state').innerText()).toBe('已停用');
      expect(await role(page, 'report-store').locator('select option').first().innerText()).toBe('選擇共享資源');
      await shot(page, 'wi1013-role-disabled-zh');
    } finally {
      await zh.close();
      await cleanUp(hash);
    }
  }, 90_000);
});

describe('the roles of a pipeline, for an admin', () => {
  test('an admin who can see two uploaders chooses whose bindings, and changes those of the one chosen only', async () => {
    const hash = await putJar(ada);
    expect(await putJar(bob)).toBe(hash);
    await cleanUp(hash);
    const first = await define('file');
    const second = await define('file');
    expect(
      (await api(ada, `/api/v1/definitions/${hash}/demo-roles/resource-bindings/report-store`, { method: 'PUT', body: JSON.stringify({ resource: first }) })).status,
    ).toBe(200);
    const context = await newContext(browser, 'en-US');
    try {
      // With no uploader named the admin is asked whose version.
      const { page, problems } = await signedIn(context, root, pagePath(hash));
      await page.locator('.choose-version').waitFor();
      expect(await page.locator('.choose-version li').allInnerTexts()).toEqual(expect.arrayContaining([ada.name, bob.name]));
      await page.locator('.choose-version').getByRole('link', { name: ada.name }).click();

      await role(page, 'report-store').waitFor();
      const picker = page.locator('select.uploader');
      expect(await picker.inputValue()).toBe(ada.name);
      expect(await picker.locator('option').allInnerTexts()).toEqual(expect.arrayContaining([ada.name, bob.name]));
      expect(await role(page, 'report-store').locator('.role-current').innerText()).toContain(`Bound to ${first}`);
      await shot(page, 'wi1013-roles-admin-uploader-en');

      // Bob's bindings are their own: nothing is bound there.
      await picker.selectOption(bob.name);
      await expect.poll(() => page.url()).toContain(`uploader=${bob.name}`);
      await expect.poll(async () => role(page, 'report-store').locator('.role-state').innerText()).toBe('Not bound');

      const store = role(page, 'report-store');
      await store.locator('select').selectOption(second);
      await store.getByRole('button', { name: 'Save' }).click();
      await expect.poll(async () => store.locator('.role-current').innerText()).toContain(`Bound to ${second}`);
      expect(await store.locator('.role-bound-by').innerText()).toContain(root.name);

      expect((await bindingOf(root, hash, 'report-store', bob.name))?.resource).toBe(second);
      expect((await bindingOf(root, hash, 'report-store', ada.name))?.resource).toBe(first);
      expect((await bindingOf(ada, hash, 'report-store'))?.setBy).toBe(ada.name);
      expect((await bindingOf(bob, hash, 'report-store'))?.setBy).toBe(root.name);
      // The only thing the browser logged is the Engine asking whose version (409), on purpose.
      expect(problems.console.filter((line) => !line.includes('409'))).toEqual([]);
      expect(problems.csp).toEqual([]);
    } finally {
      await context.close();
      await cleanUp(hash);
    }
  }, 90_000);

  test('the resources page names the pipeline, the role and the uploader that use a resource, with a link to the role; a resource bound to a role is not named by the role\'s own name', async () => {
    const hash = await putJar(ada);
    await cleanUp(hash);
    const file = await define('file');
    expect(
      (await api(ada, `/api/v1/definitions/${hash}/demo-roles/resource-bindings/report-store`, { method: 'PUT', body: JSON.stringify({ resource: file }) })).status,
    ).toBe(200);
    const context = await newContext(browser, 'en-US');
    try {
      const { page } = await signedIn(context, root, '/resources');
      const card = page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${file}$`) }) });
      await card.waitFor();
      // One row for each version of the pipeline that comes to the resource: this one's.
      const link = card.locator(`.declared a[href^="/pipelines/${hash}?"]`);
      await link.waitFor();
      const row = link.locator('xpath=..');
      expect(await card.locator('.declared h3').textContent()).toBe('Used by');
      const text = await row.innerText();
      expect(text).toContain('demo-roles');
      expect(text).toContain('report-store');
      expect(text).toContain(ada.name);
      expect(text).toContain('expects File');
      await shot(page, 'wi1013-resources-used-by-en');

      await link.click();
      await role(page, 'report-store').waitFor();
      expect(page.url()).toContain(`uploader=${ada.name}`);
      expect(page.url()).toContain('role=report-store');
      await expect.poll(async () => role(page, 'report-store').getAttribute('aria-current')).toBe('true');
    } finally {
      await context.close();
      await cleanUp(hash);
    }
  }, 90_000);
});

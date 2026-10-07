// The resources page of WI-49 in a real browser (Chrome), against a real packaged Engine with its
// PostgreSQL, the real sample jars (E2E_JARS) and a real PKCS12 keystore that the test changes with
// the JDK's keytool, as an operator does: the type, settings, secret alias and status of each
// resource, a check that is made only when asked and whose result is the API's, deleting after a
// preview that is the API's (free, and in use by a real run), the keystore section and its reload
// (a real change of the file, and a file that cannot be read), the pipelines that declare a
// resource and the status of the declarations on a pipeline's page, for an admin and a developer.
//
// The keystore holds a secret whose value is a marker (E2E_SECRET_MARKER): it must never be in the
// page (DOM), in the browser's storage or cookies, or in any answer the browser receives.
//
// Needs, besides what admin.e2e.ts needs: E2E_SECRET_MARKER, E2E_KEYSTORE (the Engine's
// RUNLINE_KEYSTORE_PATH, holding `llm-key` whose value is the marker), E2E_KEYSTORE_PASSWORD_FILE
// (its RUNLINE_KEYSTORE_PASSWORD_FILE) and E2E_KEYTOOL (keytool of JDK 25; `keytool` by default).
// What a test changes it puts back, the keystore included.

import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Browser, BrowserContext, Page } from 'playwright-core';
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
const ada = callers.filter((c) => c.role === 'developer')[0];
const root = callers.find((c) => c.role === 'admin')!;
const jars = need('E2E_JARS', 'the directory of the sample jars');
const marker = need('E2E_SECRET_MARKER', 'the value of the secret llm-key in the keystore');
const keystore = need('E2E_KEYSTORE', "the Engine's keystore file, which the test changes with keytool");
const passwordFile = need('E2E_KEYSTORE_PASSWORD_FILE', "the keystore's password file");
const keytool = process.env.E2E_KEYTOOL ?? 'keytool';
const screenshots = process.env.E2E_SCREENSHOTS;
if (screenshots) mkdirSync(screenshots, { recursive: true });

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
});
afterAll(async () => {
  await browser?.close();
});

const shot = async (page: Page, name: string) => {
  if (screenshots) await page.screenshot({ path: `${screenshots}/${name}.png`, fullPage: true });
};
const stamp = () => Date.now().toString(36);
const dialog = (page: Page) => page.getByRole('dialog');

const api = (who: { token: string }, path: string, init: RequestInit = {}) =>
  fetch(`${engineUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/json', ...init.headers },
  });
const json = async (response: Response) => (await response.json()) as any;
const resource = async (name: string) => json(await api(root, `/api/v1/resources/${name}`));

async function putJar(name: string) {
  const response = await fetch(`${engineUrl}/api/v1/artifacts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${ada.token}`, 'Content-Type': 'application/octet-stream' },
    body: readFileSync(join(jars, `${name}.jar`)) as unknown as BodyInit,
  });
  return (await response.json()) as { contentHash: string };
}

/** `demo-printer`, a counter of capacity 1, enabled, as the sample pipelines expect it. */
async function printer() {
  const there = await api(root, '/api/v1/resources/demo-printer');
  if (there.status === 404) {
    await api(root, '/api/v1/resources', { method: 'POST', body: JSON.stringify({ name: 'demo-printer', capacity: 1 }) });
  } else {
    await api(root, '/api/v1/resources/demo-printer', { method: 'PATCH', body: JSON.stringify({ capacity: 1, enabled: true }) });
  }
}

/**
 * Everything the browser holds or was given, to look for the marker in: every answer's body (kept
 * as the pages receive them), the DOM, the storage and the cookies of every page of the context.
 */
class Leaks {
  readonly bodies: string[] = [];
  private readonly pages: Page[] = [];

  watch(page: Page) {
    this.pages.push(page);
    page.on('response', async (response) => {
      try {
        this.bodies.push(`${response.url()}\n${JSON.stringify(response.headers())}\n${await response.text()}`);
      } catch {
        // A redirect or an answer the page left: it has no body to read.
      }
    });
  }

  async expectNone(context: BrowserContext) {
    expect(this.bodies.length).toBeGreaterThan(0);
    expect(this.bodies.filter((body) => body.includes(marker))).toEqual([]);
    for (const page of this.pages.filter((p) => !p.isClosed())) {
      const held = await page.evaluate(() => ({
        dom: document.documentElement.outerHTML,
        local: JSON.stringify({ ...localStorage }),
        session: JSON.stringify({ ...sessionStorage }),
      }));
      expect(held.dom).not.toContain(marker);
      expect(held.local).not.toContain(marker);
      expect(held.session).not.toContain(marker);
    }
    expect(JSON.stringify(await context.cookies())).not.toContain(marker);
  }
}

async function signedIn(context: BrowserContext, who: { token: string }, path: string, leaks?: Leaks) {
  const page = await context.newPage();
  leaks?.watch(page);
  const problems = await watch(page, engineUrl);
  await page.goto(`${engineUrl}${path}`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, who.token);
  await page.locator('aside nav').waitFor();
  return { page, problems };
}

/** As the runbook says: on a copy, then renamed over the file, so the Engine never reads half of it. */
function addSecret(alias: string, value: string) {
  const copy = `${keystore}.new`;
  copyFileSync(keystore, copy);
  execFileSync(keytool, ['-importpass', '-alias', alias, '-keystore', copy, '-storetype', 'PKCS12', '-storepass:file', passwordFile], {
    input: `${value}\n`,
  });
  renameSync(copy, keystore);
}

describe('the resources page of an admin', () => {
  test('each card has the type, the settings, the secret alias and whether the keystore has it, and the last check; a check is made only when asked, and its result is the one the API keeps', async () => {
    const llm = `e2e-llm-${stamp()}`;
    const file = `e2e-file-${stamp()}`;
    const made = await api(root, '/api/v1/resources', {
      method: 'POST',
      body: JSON.stringify({
        name: llm,
        capacity: 1,
        type: 'openai-compatible',
        // Nothing listens there: the check is refused a connection.
        settings: { baseUrl: 'http://127.0.0.1:9/v1' },
        secretAlias: 'LLM-Key',
      }),
    });
    expect(made.status).toBe(201);
    await api(root, '/api/v1/resources', {
      method: 'POST',
      body: JSON.stringify({ name: file, capacity: 1, type: 'file', settings: { path: `e2e/${file}.txt` } }),
    });
    const leaks = new Leaks();
    const context = await newContext(browser, 'en-US');
    try {
      const { page, problems } = await signedIn(context, root, '/resources', leaks);
      const llmCard = page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${llm}$`) }) });
      const fileCard = page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${file}$`) }) });
      await llmCard.waitFor();
      expect(await llmCard.locator('.type').innerText()).toBe('OpenAI-compatible service');
      expect(await llmCard.locator('.settings').innerText()).toContain('http://127.0.0.1:9/v1');
      expect(await llmCard.locator('.secret .alias').innerText()).toBe('llm-key');
      expect(await llmCard.locator('.secret-status').innerText()).toBe('Found in the keystore');
      expect(await llmCard.locator('.last-check').innerText()).toContain('Never checked');
      expect(await fileCard.locator('.type').innerText()).toBe('File');
      expect(await fileCard.locator('.settings').innerText()).toContain(`e2e/${file}.txt`);
      expect(await fileCard.locator('.secret').count()).toBe(0);

      // The page reads itself again: no check is made by that.
      await page.waitForTimeout(7000);
      expect((await resource(llm)).lastCheck).toBeNull();

      await llmCard.getByRole('button', { name: 'Check' }).click();
      await llmCard.locator('.last-check .outcome.failed').waitFor({ timeout: 20_000 });
      const llmCheck = (await resource(llm)).lastCheck;
      expect(llmCheck).toMatchObject({ ok: false, failure: 'connection_failed' });
      expect(await llmCard.locator('.last-check .outcome').innerText()).toBe('Failed: the service could not be reached');
      expect(await llmCard.locator('.last-check time').getAttribute('datetime')).toBe(llmCheck.checkedAt);

      await fileCard.getByRole('button', { name: 'Check' }).click();
      await fileCard.locator('.last-check .outcome.passed').waitFor({ timeout: 20_000 });
      expect((await resource(file)).lastCheck).toMatchObject({ ok: true, failure: null });
      await shot(page, 'wi49-resources-cards-en');
      expect(problems.csp).toEqual([]);
      await leaks.expectNone(context);
    } finally {
      await context.close();
      await api(root, `/api/v1/resources/${llm}`, { method: 'DELETE' });
      await api(root, `/api/v1/resources/${file}`, { method: 'DELETE' });
    }
  }, 90_000);

  test('a card names the pipelines that declare it, with links; the page of a pipeline says the type each declaration expects and whether it can be used, to an admin and to a developer', async () => {
    const plain = await putJar('demo-resource');
    const typed = await putJar('demo-typed');
    await printer();
    const context = await newContext(browser, 'en-US');
    try {
      const { page } = await signedIn(context, root, '/resources');
      const card = page.locator('.resource', { has: page.locator('h2.name', { hasText: /^demo-printer$/ }) });
      await card.waitFor();
      const declared = (await resource('demo-printer')).declaredBy;
      expect(await card.locator('.declared .summary').innerText()).toContain(
        `Declared by ${declared.count} pipeline definition${declared.count === 1 ? '' : 's'}`,
      );
      const typedLink = card.locator(`.declared a[href="/pipelines/${typed.contentHash}?pipeline=demo-typed&uploader=${ada.name}"]`);
      expect(await typedLink.locator('xpath=..').innerText()).toContain('expects File');
      const plainLink = card.locator(`.declared a[href="/pipelines/${plain.contentHash}?pipeline=demo-resource&uploader=${ada.name}"]`);
      expect(await plainLink.locator('xpath=..').innerText()).toContain('any type');
      await shot(page, 'wi49-resources-declared-en');

      await typedLink.click();
      await page.getByRole('heading', { name: 'demo-typed', level: 1 }).waitFor();
      const declaration = page.locator('.declarations li', { hasText: 'demo-printer' });
      expect(await declaration.locator('.declared-type').innerText()).toBe('expects File');
      expect(await declaration.locator('.status').innerText()).toBe('Defined as another type');
      await shot(page, 'wi49-pipeline-declarations-admin-en');
    } finally {
      await context.close();
    }

    const developer = await newContext(browser, 'en-US');
    try {
      const { page } = await signedIn(developer, ada, `/pipelines/${plain.contentHash}?pipeline=demo-resource&uploader=${ada.name}`);
      await page.getByRole('heading', { name: 'demo-resource', level: 1 }).waitFor();
      const declaration = page.locator('.declarations li', { hasText: 'demo-printer' });
      expect(await declaration.locator('.declared-type').innerText()).toBe('any type');
      expect(await declaration.locator('.status').innerText()).toBe('Available');
      await page.goto(`${engineUrl}/resources`);
      await page.getByRole('heading', { name: 'Admins only' }).waitFor();
      expect((await api(ada, '/api/v1/secrets')).status).toBe(403);
      await shot(page, 'wi49-pipeline-declarations-developer-en');
    } finally {
      await developer.close();
    }
  }, 60_000);

  test('deleting asks first with the preview the API gives, and deletes; a resource a real run holds is not deleted, and the page says how to free it', async () => {
    const name = `e2e-del-${stamp()}`;
    await api(root, '/api/v1/resources', { method: 'POST', body: JSON.stringify({ name, capacity: 1 }) });
    const { contentHash } = await putJar('demo-resource');
    await printer();
    const context = await newContext(browser, 'en-US');
    let runId: string | null = null;
    try {
      const { page, problems } = await signedIn(context, root, '/resources');
      const card = page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${name}$`) }) });
      await card.getByRole('button', { name: 'Delete' }).click();
      await dialog(page).locator('.preview').waitFor();
      const preview = await json(await api(root, `/api/v1/resources/${name}?preview=true`, { method: 'DELETE' }));
      expect(preview).toMatchObject({ definitions: 0, triggers: 0, holders: 0, waiters: 0, inUse: false });
      expect(await dialog(page).locator('.preview').innerText()).toContain('No pipeline definition declares it');
      expect(await dialog(page).locator('.preview').innerText()).toContain('No run holds it or waits for it.');
      await shot(page, 'wi49-resources-delete-en');
      await dialog(page).getByRole('button', { name: 'Delete the resource' }).click();
      await card.waitFor({ state: 'detached' });
      expect((await api(root, `/api/v1/resources/${name}`)).status).toBe(404);

      // A real run holds demo-printer: the preview says so and the page does not offer to delete it.
      runId = (await json(await api(ada, '/api/v1/runs', { method: 'POST', body: JSON.stringify({ contentHash, pipeline: 'demo-resource' }) }))).runId;
      const printerCard = page.locator('.resource', { has: page.locator('h2.name', { hasText: /^demo-printer$/ }) });
      await printerCard.locator('.holders li').first().waitFor({ timeout: 20_000 });
      await printerCard.getByRole('button', { name: 'Delete' }).click();
      await dialog(page).locator('.in-use').waitFor();
      const busy = await json(await api(root, '/api/v1/resources/demo-printer?preview=true', { method: 'DELETE' }));
      expect(busy).toMatchObject({ holders: 1, waiters: 0, inUse: true });
      expect(await dialog(page).locator('.preview').innerText()).toContain(
        `${busy.definitions} pipeline definition${busy.definitions === 1 ? ' declares' : 's declare'} it`,
      );
      expect(await dialog(page).locator('.in-use').innerText()).toContain('1 run holds it and 0 runs wait for it');
      expect(await dialog(page).locator('.in-use').innerText()).toContain('disable it');
      expect(await dialog(page).getByRole('button', { name: 'Delete the resource' }).isDisabled()).toBe(true);
      await shot(page, 'wi49-resources-delete-in-use-en');
      await dialog(page).getByRole('button', { name: 'Cancel' }).click();
      expect((await api(root, '/api/v1/resources/demo-printer', { method: 'DELETE' })).status).toBe(409);
      expect(problems.csp).toEqual([]);
    } finally {
      if (runId) await api(ada, `/api/v1/runs/${runId}/cancel`, { method: 'POST' });
      await context.close();
    }
  }, 90_000);
});

describe('the keystore section', () => {
  test('lists the aliases as the API does, never a value; a reload after an operator adds a secret with keytool says what changed; a file that cannot be read is said in words and nothing changes; the marker is nowhere in the browser', async () => {
    const llm = `e2e-llm-${stamp()}`;
    await api(root, '/api/v1/resources', {
      method: 'POST',
      body: JSON.stringify({ name: llm, capacity: 1, type: 'openai-compatible', settings: { baseUrl: 'http://127.0.0.1:9/v1' }, secretAlias: 'llm-key' }),
    });
    const added = `e2e-new-${stamp()}`;
    const backup = `${keystore}.e2e-backup`;
    copyFileSync(keystore, backup);
    const leaks = new Leaks();
    const context = await newContext(browser, 'en-US');
    try {
      const { page, problems } = await signedIn(context, root, '/resources', leaks);
      const section = page.locator('section.secrets');
      await section.locator('tbody tr').first().waitFor();
      const listed = (await json(await api(root, '/api/v1/secrets'))).secrets as any[];
      const rows = async () =>
        section.locator('tbody tr').evaluateAll((trs) =>
          trs.map((tr) => [...tr.querySelectorAll('td')].map((td) => (td.textContent ?? '').trim())),
        );
      expect((await rows()).map((r) => r[0])).toEqual(listed.map((s) => s.alias));
      const llmRow = (await rows()).find((r) => r[0] === 'llm-key')!;
      expect(llmRow[1]).toBe('Secret');
      expect(llmRow[2]).toBe('Found');
      expect(llmRow[3]).toContain(llm);
      expect((await rows()).find((r) => r[0] === 'corporate-ca')![1]).toBe('Trusted certificate');
      expect(await section.innerText()).toContain('keytool');
      expect(await section.innerText()).toContain('not the secrets of webhook triggers');
      expect(await section.locator('input, textarea').count()).toBe(0);

      // An operator adds a secret with keytool; the Engine sees it only after the reload.
      addSecret(added, `added-${marker}`);
      await section.getByRole('button', { name: 'Reload the keystore' }).click();
      await section.locator('.reloaded').waitFor();
      const after = (await json(await api(root, '/api/v1/secrets'))).secrets as any[];
      expect(await section.locator('.reloaded').innerText()).toContain(`${after.length} aliases`);
      expect(await section.locator('.reloaded li').allInnerTexts()).toEqual([`${added} (used by no resource)`]);
      await section.locator('tbody tr', { hasText: added }).waitFor();
      await shot(page, 'wi49-keystore-reloaded-en');

      // A file that is not a keystore: the reload is refused, and the aliases stay as they were.
      writeFileSync(`${keystore}.new`, 'this is not a keystore');
      renameSync(`${keystore}.new`, keystore);
      await section.getByRole('button', { name: 'Reload the keystore' }).click();
      const alert = section.getByRole('alert');
      await alert.waitFor();
      const refused = await api(root, '/api/v1/secrets/reload', { method: 'POST' });
      const refusal = await json(refused);
      expect([refused.status, refusal.error]).toEqual([422, 'secret_store_unreadable']);
      const words: Record<string, string> = {
        file_missing: 'The keystore file is missing.',
        wrong_password: 'The keystore password is wrong.',
        corrupt: 'The keystore file is damaged or cut short.',
        wrong_format: 'The keystore file is not PKCS12.',
        unreadable: 'The keystore file could not be opened.',
      };
      expect(await alert.innerText()).toContain('the secrets in memory are unchanged');
      expect(await alert.innerText()).toContain(words[refusal.problem]);
      expect((await json(await api(root, '/api/v1/secrets'))).secrets.map((s: any) => s.alias)).toContain(added);
      await shot(page, 'wi49-keystore-unreadable-en');

      // In zh-TW too.
      const zh = await newContext(browser, 'zh-TW', ['zh-TW']);
      try {
        const tab = await signedIn(zh, root, '/resources', leaks);
        await tab.page.locator('section.secrets h2', { hasText: '金鑰庫機密' }).waitFor();
        await tab.page.locator('.resource .type', { hasText: 'OpenAI 相容服務' }).first().waitFor();
        await shot(tab.page, 'wi49-resources-zh');
        await leaks.expectNone(zh);
      } finally {
        await zh.close();
      }
      expect(problems.csp).toEqual([]);
      await leaks.expectNone(context);
    } finally {
      await context.close();
      copyFileSync(backup, keystore);
      await api(root, '/api/v1/secrets/reload', { method: 'POST' });
      await api(root, `/api/v1/resources/${llm}`, { method: 'DELETE' });
    }
  }, 90_000);
});

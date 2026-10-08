// The forms of the typed resources of WI-50 in a real browser (Chrome), against a real packaged
// Engine with its PostgreSQL and a real PKCS12 keystore: a `file` under the Engine's resource root,
// a `jdbc-pool` of a real PostgreSQL database whose password is in the keystore, and an
// `openai-compatible` service that is the Fake of the Engine's tests run as a process of its own
// (`./gradlew :accessors:fakeOpenAiServer`, held to the protocol by OpenAiServerContract), which
// asks for the key that is in the keystore. Each is defined with its form, refused at a field,
// checked (passed and failed, each the same as the API's last check), changed and deleted; the use
// of a pool and of a service is shown while a real run holds a connection or waits for an answer,
// read again with the page and without a check. No secret value is typed anywhere: the aliases are
// chosen among the keystore's, and neither the marker (the key) nor the database password is in any
// answer the browser received, in the DOM, the storage or the cookies.
//
// Needs, besides what resources.e2e.ts needs (E2E_SECRET_MARKER is the key the Fake asks for):
// - the keystore holds `db-pass` (the password of E2E_DB_PASSWORD) and `other-pass` (another
//   secret), besides `llm-key`;
// - a PostgreSQL that the Engine reaches at E2E_DB_HOST:E2E_DB_PORT, with the database `orders`
//   owned by the account `reader`, whose password is E2E_DB_PASSWORD;
// - E2E_OPENAI_URL: the base address the Fake printed (FAKE_OPENAI_KEY_FILE holding the marker,
//   FAKE_OPENAI_CHAT_DELAY_MS at 15000 or more);
// - `demo-usage.jar` among the sample jars (`./gradlew -p dev/sample-pipelines pipelineJars`).
// It defines `demo-db` and `demo-llm` (the names the sample pipelines declare) and deletes them.

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
const ada = callers.filter((c) => c.role === 'developer')[0];
const root = callers.find((c) => c.role === 'admin')!;
const jars = need('E2E_JARS', 'the directory of the sample jars');
const marker = need('E2E_SECRET_MARKER', 'the value of the secret llm-key, the key the Fake service asks for');
const dbPassword = need('E2E_DB_PASSWORD', 'the password of the account reader, the secret db-pass');
const dbHost = need('E2E_DB_HOST', 'the host of the PostgreSQL the Engine reaches');
const dbPort = need('E2E_DB_PORT', 'the port of that PostgreSQL');
const openAiUrl = need('E2E_OPENAI_URL', 'the base address of the Fake OpenAI compatible service');
const screenshots = process.env.E2E_SCREENSHOTS;

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
const cardOf = (page: Page, name: string) =>
  page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${name.replace(/\./g, '\\.')}$`) }) });
/** The error said at the field (or group of fields) of this id. */
const errorAt = (page: Page, id: string) =>
  dialog(page).locator(`.rl-field:has(#${id}) .rl-field-error, fieldset#${id} > .rl-field-error`).first();

const api = (who: { token: string }, path: string, init: RequestInit = {}) =>
  fetch(`${engineUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/json', ...init.headers },
  });
const json = async (response: Response) => (await response.json()) as any;
const resource = async (name: string) => json(await api(root, `/api/v1/resources/${name}`));
const remove = (name: string) => api(root, `/api/v1/resources/${name}`, { method: 'DELETE' });

async function putJar(name: string) {
  const response = await fetch(`${engineUrl}/api/v1/artifacts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${ada.token}`, 'Content-Type': 'application/octet-stream' },
    body: readFileSync(join(jars, `${name}.jar`)) as unknown as BodyInit,
  });
  return (await response.json()) as { contentHash: string };
}

/** Every answer, the DOM, the storage and the cookies: none may hold a secret value. */
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
    for (const secret of [marker, dbPassword]) {
      expect(this.bodies.filter((body) => body.includes(secret))).toEqual([]);
      for (const page of this.pages.filter((p) => !p.isClosed())) {
        const held = await page.evaluate(() => ({
          dom: document.documentElement.outerHTML,
          local: JSON.stringify({ ...localStorage }),
          session: JSON.stringify({ ...sessionStorage }),
        }));
        expect(held.dom).not.toContain(secret);
        expect(held.local).not.toContain(secret);
        expect(held.session).not.toContain(secret);
      }
      expect(JSON.stringify(await context.cookies())).not.toContain(secret);
    }
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

/** Opens the form to define a resource of [type] (its label), with a name. */
async function defineForm(page: Page, type: string, name: string) {
  await page.getByRole('button', { name: 'Define a resource' }).first().click();
  await dialog(page).locator('#resource-type').selectOption({ label: type });
  await dialog(page).locator('#resource-name').fill(name);
}

/** Chooses [alias] once the aliases of the keystore are there. */
async function chooseAlias(page: Page, alias: string) {
  await dialog(page).locator(`#resource-secret option[value="${alias}"]`).waitFor({ state: 'attached' });
  await dialog(page).locator('#resource-secret').selectOption(alias);
}

/** Checks the resource of [card] and waits for the outcome; the API's last check is the card's. */
async function check(card: Locator, name: string, outcome: 'passed' | 'failed') {
  const before = (await resource(name)).lastCheck?.checkedAt ?? null;
  await card.getByRole('button', { name: 'Check' }).click();
  await expect
    .poll(async () => (await resource(name)).lastCheck?.checkedAt ?? null, { timeout: 30_000 })
    .not.toBe(before);
  await card.locator(`.last-check .outcome.${outcome}`).waitFor({ timeout: 20_000 });
  const last = (await resource(name)).lastCheck;
  await expect.poll(() => card.locator('.last-check time').getAttribute('datetime')).toBe(last.checkedAt);
  return last;
}

/** Changes the resource of [card] with [edit] in its form, and saves. */
async function change(page: Page, card: Locator, edit: () => Promise<void>) {
  await card.getByRole('button', { name: 'Change' }).click();
  await edit();
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await dialog(page).waitFor({ state: 'detached' });
}

describe('the form of a file', () => {
  test('is refused at the path out of the resource root, defines the file, checks it, changes its path (which forgets the check) and deletes it', async () => {
    const name = `e2e-file-${stamp()}`;
    const dir = `e2e-${stamp()}`;
    const context = await newContext(browser, 'en-US');
    try {
      const { page, problems } = await signedIn(context, root, '/resources');
      await defineForm(page, 'File', name);
      expect(await dialog(page).locator('#resource-secret').count()).toBe(0);
      await dialog(page).locator('#resource-path').fill('../outside.txt');
      await dialog(page).getByRole('button', { name: 'Define' }).click();
      await expect.poll(() => errorAt(page, 'resource-path').innerText()).toContain('outside the resource root');
      expect((await api(root, `/api/v1/resources/${name}`)).status).toBe(404);
      await shot(page, 'wi50-file-refused-en');

      await dialog(page).locator('#resource-path').fill(`${dir}/out.txt`);
      await dialog(page).getByRole('button', { name: 'Define' }).click();
      await dialog(page).waitFor({ state: 'detached' });
      const card = cardOf(page, name);
      await card.waitFor();
      expect(await resource(name)).toMatchObject({ type: 'file', capacity: 1, settings: { path: `${dir}/out.txt` }, createdBy: root.name });
      expect(await card.locator('.settings').innerText()).toContain(`${dir}/out.txt`);
      expect(await card.locator('.type-usage').count()).toBe(0);
      expect(await check(card, name, 'passed')).toMatchObject({ ok: true, failure: null });

      await change(page, card, async () => {
        expect(await dialog(page).locator('.fixed dd').allInnerTexts()).toEqual([name, 'File']);
        expect(await dialog(page).locator('#resource-path').inputValue()).toBe(`${dir}/out.txt`);
        await dialog(page).locator('#resource-path').fill(`${dir}/other.txt`);
      });
      expect(await resource(name)).toMatchObject({ settings: { path: `${dir}/other.txt` }, lastCheck: null, updatedBy: root.name });
      await expect.poll(() => card.locator('.last-check').innerText()).toContain('Never checked');

      await card.getByRole('button', { name: 'Delete' }).click();
      await dialog(page).locator('.preview').waitFor();
      await dialog(page).getByRole('button', { name: 'Delete the resource' }).click();
      await card.waitFor({ state: 'detached' });
      expect((await api(root, `/api/v1/resources/${name}`)).status).toBe(404);
      expect(problems.csp).toEqual([]);
    } finally {
      await context.close();
      await remove(name);
    }
  }, 90_000);
});

describe('the form of a jdbc-pool', () => {
  test('defines a pool of a real PostgreSQL with the alias of its password, says what the pool comes to and what it refuses, passes and fails its check, and shows the connections a real run holds', async () => {
    const name = 'demo-db';
    await remove(name);
    const { contentHash } = await putJar('demo-usage');
    const leaks = new Leaks();
    const context = await newContext(browser, 'en-US');
    let runId: string | null = null;
    try {
      const { page, problems } = await signedIn(context, root, '/resources', leaks);
      await defineForm(page, 'Database pool (JDBC)', name);
      // The kinds the Engine tells (WI-55), each as it names it.
      const kinds = (await json(await api(root, '/api/v1/resource-types'))).types.find((t: any) => t.type === 'jdbc-pool').databases.map((d: any) => d.kind);
      expect(kinds).toContain('postgresql');
      expect(await dialog(page).locator('#jdbc-kind option').allInnerTexts()).toEqual(kinds);
      await dialog(page).locator('#jdbc-host').fill(dbHost);
      await dialog(page).locator('#jdbc-port').fill(dbPort);
      await dialog(page).locator('#jdbc-database').fill('orders');
      await dialog(page).locator('#jdbc-username').fill('reader');
      await chooseAlias(page, 'db-pass');
      const aliases = await dialog(page).locator('#resource-secret option').evaluateAll((o) => o.map((e) => (e as HTMLOptionElement).value));
      expect(aliases).toEqual(expect.arrayContaining(['', 'db-pass', 'llm-key', 'other-pass']));
      expect(await dialog(page).locator('input[type="password"], textarea').count()).toBe(0);
      await dialog(page).locator('#resource-capacity').fill('2');
      await dialog(page).locator('#jdbc-per-run').fill('2');
      expect(await dialog(page).locator('.pool-size').innerText()).toContain('capacity × connections per run = 2 × 2 = 4');
      await dialog(page).getByRole('button', { name: 'Add a property' }).click();
      await dialog(page).locator('#jdbc-properties .pair input.name').fill('password');
      await dialog(page).locator('#jdbc-properties .pair input.value').fill('x');
      await dialog(page).getByRole('button', { name: 'Define' }).click();
      await expect.poll(() => errorAt(page, 'jdbc-properties').innerText()).toContain('not allowed');
      expect((await api(root, `/api/v1/resources/${name}`)).status).toBe(404);

      await dialog(page).locator('#jdbc-properties .pair input.name').fill('ApplicationName');
      await dialog(page).locator('#jdbc-properties .pair input.value').fill('runline-e2e');
      await dialog(page).locator('#jdbc-statement-ms').fill('30000');
      await shot(page, 'wi50-jdbc-form-en');
      await dialog(page).getByRole('button', { name: 'Define' }).click();
      await dialog(page).waitFor({ state: 'detached' });
      const card = cardOf(page, name);
      await card.waitFor();
      expect(await resource(name)).toMatchObject({
        type: 'jdbc-pool',
        capacity: 2,
        settings: {
          kind: 'postgresql',
          host: dbHost,
          port: Number(dbPort),
          database: 'orders',
          username: 'reader',
          connectionsPerRun: 2,
          timeouts: { connectMs: 10000, statementMs: 30000, quotaWaitMs: 60000 },
          properties: { ApplicationName: 'runline-e2e' },
        },
        secretAlias: 'db-pass',
        secretStatus: 'found',
        concurrencyLimit: 4,
        usage: { activeConnections: 0 },
      });
      expect(await card.locator('.secret .alias').innerText()).toBe('db-pass');
      expect(await card.locator('.type-usage').innerText()).toBe('Connections in use: 0 of 4');
      expect(await check(card, name, 'passed')).toMatchObject({ ok: true, failure: null });

      // Another secret is not the account's password: the database refuses it.
      await change(page, card, () => chooseAlias(page, 'other-pass'));
      expect(await resource(name)).toMatchObject({ secretAlias: 'other-pass', lastCheck: null });
      const refused = await check(card, name, 'failed');
      expect(refused).toMatchObject({ ok: false, failure: 'rejected' });
      expect(await card.locator('.last-check .outcome').innerText()).toBe('Failed: the credentials were refused');
      await change(page, card, () => chooseAlias(page, 'db-pass'));
      const passed = await check(card, name, 'passed');

      // A real run keeps the connection it used until it ends: the card shows it with the page.
      runId = (
        await json(
          await api(ada, '/api/v1/runs', {
            method: 'POST',
            body: JSON.stringify({ contentHash, pipeline: 'demo-pool-usage', parameters: { holdSeconds: '40' } }),
          }),
        )
      ).runId;
      await expect.poll(() => card.locator('.type-usage').innerText(), { timeout: 30_000 }).toBe('Connections in use: 1 of 4');
      expect((await resource(name)).usage).toEqual({ activeConnections: 1 });
      expect((await resource(name)).lastCheck).toEqual(passed);
      await shot(page, 'wi50-jdbc-usage-en');
      await api(ada, `/api/v1/runs/${runId}/cancel`, { method: 'POST' });
      runId = null;
      await expect.poll(() => card.locator('.type-usage').innerText(), { timeout: 30_000 }).toBe('Connections in use: 0 of 4');
      expect((await resource(name)).lastCheck).toEqual(passed);
      expect(problems.csp).toEqual([]);
      await leaks.expectNone(context);
    } finally {
      if (runId) await api(ada, `/api/v1/runs/${runId}/cancel`, { method: 'POST' });
      await context.close();
      await expect.poll(async () => (await remove(name)).status, { timeout: 30_000 }).not.toBe(409);
    }
  }, 180_000);
});

describe('the form of an openai-compatible service', () => {
  test('defines a service with its endpoints, parameters and the alias of its key, says what a lock and a ceiling are and what it refuses, passes and fails its check, and shows a request a real run has in flight', async () => {
    const name = 'demo-llm';
    await remove(name);
    const { contentHash } = await putJar('demo-usage');
    const leaks = new Leaks();
    const context = await newContext(browser, 'en-US');
    let runId: string | null = null;
    try {
      const { page, problems } = await signedIn(context, root, '/resources', leaks);
      await defineForm(page, 'OpenAI-compatible service', name);
      await dialog(page).locator('#openai-base-url').fill(openAiUrl);
      expect(
        await dialog(page)
          .locator('#openai-endpoints input:checked')
          .evaluateAll((boxes) => boxes.map((b) => (b as HTMLInputElement).value)),
      ).toEqual(['chat.completions', 'completions', 'embeddings', 'models.list', 'models.retrieve']);
      await dialog(page).locator('#openai-endpoints input[value="completions"]').uncheck();
      await dialog(page).locator('#openai-parameter-model').fill('fake-model');
      await dialog(page).locator('#openai-allowed-models').fill('fake-model, small');
      await dialog(page).locator('#openai-locked-temperature').check();
      await dialog(page).locator('#openai-max-max_tokens').fill('1000');
      const parameters = await dialog(page).locator('#openai-parameters').innerText();
      expect(parameters).toContain('A locked parameter cannot be given by the pipeline');
      expect(parameters).toContain('A ceiling is the highest number');
      expect(await dialog(page).locator('.request-limit').innerText()).toContain('capacity × requests per run = 1 × 1 = 1');
      await chooseAlias(page, 'llm-key');
      expect(await dialog(page).locator('input[type="password"], textarea').count()).toBe(0);
      await dialog(page).getByRole('button', { name: 'Add a header' }).click();
      await dialog(page).locator('#openai-headers .pair input.name').fill('X-Api-Key');
      await dialog(page).locator('#openai-headers .pair input.value').fill('not-a-key');
      await dialog(page).getByRole('button', { name: 'Define' }).click();
      await expect.poll(() => errorAt(page, 'openai-headers').innerText()).toContain('credential');
      expect((await api(root, `/api/v1/resources/${name}`)).status).toBe(404);

      await dialog(page).locator('#openai-headers .pair input.name').fill('X-Team');
      await dialog(page).locator('#openai-headers .pair input.value').fill('e2e');
      await shot(page, 'wi50-openai-form-en');
      await dialog(page).getByRole('button', { name: 'Define' }).click();
      await dialog(page).waitFor({ state: 'detached' });
      const card = cardOf(page, name);
      await card.waitFor();
      expect(await resource(name)).toMatchObject({
        type: 'openai-compatible',
        capacity: 1,
        settings: {
          baseUrl: openAiUrl,
          headers: { 'X-Team': 'e2e' },
          endpoints: ['chat.completions', 'embeddings', 'models.list', 'models.retrieve'],
          requestsPerRun: 1,
          defaults: { model: 'fake-model' },
          allowedModels: ['fake-model', 'small'],
          lockedParameters: ['temperature'],
          maxValues: { max_tokens: 1000 },
        },
        secretAlias: 'llm-key',
        secretStatus: 'found',
        concurrencyLimit: 1,
        usage: { inFlightRequests: 0 },
      });
      expect(await card.locator('.type-usage').innerText()).toBe('Requests in flight: 0 of 1');
      expect(await check(card, name, 'passed')).toMatchObject({ ok: true, failure: null });

      // Another secret is not the key the service asks for.
      await change(page, card, () => chooseAlias(page, 'other-pass'));
      expect(await check(card, name, 'failed')).toMatchObject({ ok: false, failure: 'rejected' });
      await change(page, card, () => chooseAlias(page, 'llm-key'));
      const passed = await check(card, name, 'passed');

      // The Fake keeps a chat completion waiting: the request is in flight while the run waits.
      runId = (await json(await api(ada, '/api/v1/runs', { method: 'POST', body: JSON.stringify({ contentHash, pipeline: 'demo-llm-usage' }) }))).runId;
      await expect.poll(() => card.locator('.type-usage').innerText(), { timeout: 30_000 }).toBe('Requests in flight: 1 of 1');
      expect((await resource(name)).usage).toEqual({ inFlightRequests: 1 });
      expect((await resource(name)).lastCheck).toEqual(passed);
      await shot(page, 'wi50-openai-usage-en');
      await expect.poll(() => card.locator('.type-usage').innerText(), { timeout: 40_000 }).toBe('Requests in flight: 0 of 1');
      const run = await json(await api(ada, `/api/v1/runs/${runId}`));
      expect(run.state).toBe('SUCCEEDED');
      runId = null;
      expect(problems.csp).toEqual([]);
      await leaks.expectNone(context);
    } finally {
      if (runId) await api(ada, `/api/v1/runs/${runId}/cancel`, { method: 'POST' });
      await context.close();
      await expect.poll(async () => (await remove(name)).status, { timeout: 30_000 }).not.toBe(409);
    }
  }, 180_000);
});

describe('the forms in zh-TW, and for a developer', () => {
  test('speak the language of the screen; a developer has no resources page and cannot define one', async () => {
    const name = `e2e-db-${stamp()}`;
    await api(root, '/api/v1/resources', {
      method: 'POST',
      body: JSON.stringify({
        name,
        type: 'jdbc-pool',
        capacity: 3,
        settings: { kind: 'postgresql', host: dbHost, port: Number(dbPort), database: 'orders', username: 'reader' },
        secretAlias: 'db-pass',
      }),
    });
    const context = await newContext(browser, 'zh-TW');
    try {
      const { page } = await signedIn(context, root, '/resources');
      const card = cardOf(page, name);
      await card.waitFor();
      expect(await card.locator('.type-usage').innerText()).toBe('使用中的連線：0 / 3');
      await page.getByRole('button', { name: '定義資源' }).first().click();
      await dialog(page).locator('#resource-type').selectOption('jdbc-pool');
      expect(await dialog(page).locator('.pool-size').innerText()).toContain('連線池大小');
      await dialog(page).locator('#resource-type').selectOption('openai-compatible');
      expect(await dialog(page).locator('#openai-parameters').innerText()).toContain('鎖定');
      await dialog(page).locator('#resource-type').selectOption('file');
      expect(await dialog(page).innerText()).toContain('資源根目錄');
      await shot(page, 'wi50-forms-zh-TW');
    } finally {
      await context.close();
      await remove(name);
    }

    const developer = await newContext(browser, 'en-US');
    try {
      const { page } = await signedIn(developer, ada, '/resources');
      await page.getByRole('heading', { name: 'Admins only' }).waitFor();
      expect(await page.getByRole('button', { name: 'Define a resource' }).count()).toBe(0);
      const refused = await api(ada, '/api/v1/resources', {
        method: 'POST',
        body: JSON.stringify({ name: `e2e-file-${stamp()}`, type: 'file', capacity: 1, settings: { path: 'x.txt' } }),
      });
      expect(refused.status).toBe(403);
    } finally {
      await developer.close();
    }
  }, 90_000);
});

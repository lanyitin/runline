// The secret non-leak rules verified as a whole in a real browser (Chrome) (WI-51, ADR-019
// decisions 6 and 12): against a real packaged Engine with its PostgreSQL and a real PKCS12
// keystore, after real runs used an `openai-compatible` resource whose key is in the keystore (the
// Fake asks for it) and a `jdbc-pool` resource whose password is, every page an admin and a
// developer can open is read, the checks and the reload of the keystore are done from the pages,
// and none of what must stay secret is in any answer the browser received, in the DOM, in the
// browser's storage or in its cookies: the key (E2E_SECRET_MARKER), the database password
// (E2E_DB_PASSWORD), the keystore password (E2E_KEYSTORE_PASSWORD) and the private keys
// (E2E_PRIVATE_KEY_MARKERS).
//
// Needs what typed-forms.e2e.ts and certificates.e2e.ts need (see e2e/README.md). It defines
// `demo-db` and `demo-llm` (the names the sample pipelines declare) and deletes them.

import { readFileSync } from 'node:fs';
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
const serviceUrl = need('E2E_OPENAI_URL', 'the base address of the HTTP Fake, which asks for llm-key');
const dbHost = need('E2E_DB_HOST', 'where the Engine reaches the PostgreSQL that has orders');
const dbPort = need('E2E_DB_PORT', 'the port of that PostgreSQL');
const secrets: Record<string, string> = {
  'the key (llm-key)': need('E2E_SECRET_MARKER', 'the value of the secret llm-key'),
  'the database password (db-pass)': need('E2E_DB_PASSWORD', 'the value of the secret db-pass'),
  'the keystore password': need('E2E_KEYSTORE_PASSWORD', 'the password of the keystore'),
};
need('E2E_PRIVATE_KEY_MARKERS', 'the base64 of the private keys in the keystore')
  .split(',')
  .forEach((key, i) => {
    // The beginning of the key, and every line of it as PEM would break it.
    secrets[`private key ${i + 1}`] = key.slice(0, 40);
    key.match(/.{1,64}/g)!.forEach((line, n) => (secrets[`private key ${i + 1}, line ${n + 1}`] = line));
  });

const api = (who: { token: string }, path: string, init: RequestInit = {}) =>
  fetch(`${engineUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/json', ...init.headers },
  });
const json = async (response: Response) => (await response.json()) as any;
const remove = (name: string) => api(root, `/api/v1/resources/${name}`, { method: 'DELETE' });

/** Everything the browser holds or was given: every answer, the DOM, the storage, the cookies. */
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
    expect(this.bodies.length).toBeGreaterThan(10);
    const held: string[] = [];
    for (const page of this.pages.filter((p) => !p.isClosed())) {
      held.push(
        await page.evaluate(
          () => document.documentElement.outerHTML + JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }),
        ),
      );
    }
    const cookies = JSON.stringify(await context.cookies());
    for (const [what, value] of Object.entries(secrets)) {
      expect(this.bodies.filter((body) => body.includes(value)), `${what} in an answer`).toEqual([]);
      expect(held.filter((text) => text.includes(value)), `${what} in a page or its storage`).toEqual([]);
      expect(cookies, `${what} in a cookie`).not.toContain(value);
    }
  }
}

let browser: Browser;
let contentHash: string;
const runs: string[] = [];

beforeAll(async () => {
  browser = await launchChrome();
  for (const name of ['demo-llm', 'demo-db']) await remove(name);
  const made = [
    await api(root, '/api/v1/resources', {
      method: 'POST',
      body: JSON.stringify({ name: 'demo-llm', capacity: 1, type: 'openai-compatible', settings: { baseUrl: serviceUrl }, secretAlias: 'llm-key' }),
    }),
    await api(root, '/api/v1/resources', {
      method: 'POST',
      body: JSON.stringify({
        name: 'demo-db',
        capacity: 1,
        type: 'jdbc-pool',
        settings: { kind: 'postgresql', host: dbHost, port: Number(dbPort), database: 'orders', username: 'reader' },
        secretAlias: 'db-pass',
      }),
    }),
  ];
  expect(made.map((r) => r.status)).toEqual([201, 201]);
  const upload = await fetch(`${engineUrl}/api/v1/artifacts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${ada.token}`, 'Content-Type': 'application/octet-stream' },
    body: readFileSync(join(jars, 'demo-usage.jar')) as unknown as BodyInit,
  });
  contentHash = (await json(upload)).contentHash;
  for (const [pipeline, parameters] of [
    ['demo-pool-usage', { holdSeconds: '0' }],
    ['demo-llm-usage', {}],
  ] as const) {
    const run = await json(await api(ada, '/api/v1/runs', { method: 'POST', body: JSON.stringify({ contentHash, pipeline, parameters }) }));
    runs.push(run.runId);
  }
  // The HTTP Fake keeps a chat completion waiting (FAKE_OPENAI_CHAT_DELAY_MS): the runs take a while.
  const deadline = Date.now() + 90_000;
  for (const runId of runs) {
    let state = '';
    while (Date.now() < deadline) {
      state = (await json(await api(ada, `/api/v1/runs/${runId}`))).state;
      if (!['QUEUED', 'WAITING_FOR_RESOURCES', 'INITIALIZING', 'RUNNING'].includes(state)) break;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    expect(state, `run ${runId}`).toBe('SUCCEEDED');
  }
}, 120_000);

afterAll(async () => {
  await browser?.close();
  for (const name of ['demo-llm', 'demo-db']) await remove(name);
});

/** Opens [path] signed in as [who] and gives the page time to read what it shows. */
async function visit(context: BrowserContext, who: { token: string }, path: string, leaks: Leaks) {
  const page = await context.newPage();
  leaks.watch(page);
  const problems = await watch(page, engineUrl);
  await page.goto(`${engineUrl}${path}`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, who.token);
  await page.locator('aside nav').waitFor();
  await page.waitForTimeout(1500);
  return { page, problems };
}

describe('what must stay secret', () => {
  test('is nowhere in the browser of an admin who opens every page, checks both resources and reloads the keystore', async () => {
    const leaks = new Leaks();
    const context = await newContext(browser, 'en-US');
    try {
      for (const path of ['/', '/pipelines', `/pipelines/${contentHash}`, '/runs', ...runs.map((r) => `/runs/${r}`), '/upload', '/engine', '/triggers', '/allowlist']) {
        const { problems } = await visit(context, root, path, leaks);
        expect(problems.csp, path).toEqual([]);
      }
      const { page } = await visit(context, root, '/resources', leaks);
      for (const name of ['demo-llm', 'demo-db']) {
        const card = page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${name}$`) }) });
        await card.getByRole('button', { name: 'Check' }).click();
        await card.locator('.last-check .outcome.passed').waitFor({ timeout: 30_000 });
      }
      await page.getByRole('button', { name: 'Reload the keystore' }).click();
      await page.getByText(/^Reloaded: the keystore has/).waitFor({ timeout: 20_000 });
      await leaks.expectNone(context);
    } finally {
      await context.close();
    }
  }, 180_000);

  test('is nowhere in the browser of a developer who opens every page and the runs that used the resources', async () => {
    const leaks = new Leaks();
    const context = await newContext(browser, 'zh-TW');
    try {
      for (const path of ['/', '/pipelines', `/pipelines/${contentHash}`, '/runs', ...runs.map((r) => `/runs/${r}`), '/engine']) {
        const { problems } = await visit(context, ada, path, leaks);
        expect(problems.csp, path).toEqual([]);
      }
      await leaks.expectNone(context);
    } finally {
      await context.close();
    }
  }, 120_000);
});

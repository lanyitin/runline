// The choices of the resource forms in a real browser (Chrome), against a real packaged Engine with
// its PostgreSQL and a real PKCS12 keystore (WI-55, ADR-021): the endpoints of `openai-compatible`
// (by group, with the marks of the entries a new resource has and of those that change what the
// service keeps), its request parameters and the kinds of database of `jdbc-pool` with what each
// allows are exactly what `GET /api/v1/resource-types` of that Engine tells; the catalog is read
// once when the page is opened and not with the page's polling; a developer is refused it, and so is
// nobody; the marker of the keystore (E2E_SECRET_MARKER, the value of `llm-key`) is not in it. The
// page in zh-TW says the marks in its language.
//
// Needs what resources.e2e.ts needs: E2E_ENGINE_URL, E2E_TOKENS, E2E_SECRET_MARKER. It defines
// nothing. That the forms of the two types are not offered when the catalog cannot be read is held
// by the component tests against the Fake Engine, which can fail it; the real Engine cannot be made
// to fail this one route.

import type { Browser, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { launchChrome, newContext, settled, signInWith, watch } from './browser';

const need = (name: string, why: string) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set: ${why}`);
  return value;
};
const engineUrl = need('E2E_ENGINE_URL', 'the tests need a running Engine');
const callers = parseCallers(need('E2E_TOKENS', 'name:role:token of a developer and an admin'));
const ada = callers.find((c) => c.role === 'developer')!;
const root = callers.find((c) => c.role === 'admin')!;
const marker = need('E2E_SECRET_MARKER', 'the value of the secret llm-key of the keystore the Engine has');

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
});
afterAll(async () => {
  await browser?.close();
});

const dialog = (page: Page) => page.getByRole('dialog');
const catalogOf = async (token: string | null) =>
  fetch(`${engineUrl}/api/v1/resource-types`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });

async function resourcesPage(locale: string, languages?: string[]) {
  const context = await newContext(browser, locale, languages);
  const page = await context.newPage();
  const problems = await watch(page, engineUrl);
  const asked: string[] = [];
  const bodies: string[] = [];
  page.on('request', (request) => asked.push(`${request.method()} ${new URL(request.url()).pathname}`));
  page.on('response', async (response) => {
    try {
      bodies.push(await response.text());
    } catch {
      // An answer the page left: it has no body to read.
    }
  });
  await page.goto(`${engineUrl}/resources`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, root.token);
  await page.locator('.resource, .rl-empty').first().waitFor();
  return { context, page, problems, asked, bodies };
}

describe('the choices of the resource forms', () => {
  test('are what the Engine tells: the endpoints by group with their marks, the request parameters, the kinds of database with what each allows', async () => {
    const told = (await (await catalogOf(root.token)).json()) as { types: Array<Record<string, any>> };
    const openAi = told.types.find((t) => t.type === 'openai-compatible')!;
    const jdbc = told.types.find((t) => t.type === 'jdbc-pool')!;
    const { context, page, problems } = await resourcesPage('en-US');
    try {
      await page.getByRole('button', { name: 'Define a resource' }).first().click();
      await dialog(page).locator('#resource-type').selectOption({ label: 'OpenAI-compatible service' });

      const shown = await dialog(page)
        .locator('#openai-endpoints label.endpoint')
        .evaluateAll((labels) =>
          labels.map((label) => {
            const box = label.querySelector('input') as HTMLInputElement;
            return {
              id: box.value,
              group: label.closest('.group')!.getAttribute('data-group'),
              checked: box.checked,
              defaultEnabled: label.querySelector('.default') !== null,
              stateful: label.querySelector('.stateful') !== null,
              text: label.textContent!.replace(/\s+/g, ' '),
            };
          }),
        );
      expect(shown.map(({ id, group, checked, defaultEnabled, stateful }) => ({ id, group, checked, defaultEnabled, stateful }))).toEqual(
        openAi.endpoints.map((e: any) => ({ id: e.id, group: e.group, checked: e.defaultEnabled, defaultEnabled: e.defaultEnabled, stateful: e.stateful })),
      );
      for (const [index, entry] of openAi.endpoints.entries()) expect(shown[index].text).toContain(`${entry.method} ${entry.path}`);
      expect(await dialog(page).locator('#openai-endpoints .group-name').allInnerTexts()).toEqual([...new Set(openAi.endpoints.map((e: any) => e.group))]);
      expect(await dialog(page).locator('#openai-parameters tbody tr td:first-child').allInnerTexts()).toEqual(
        openAi.requestParameters.map((p: any) => p.name),
      );
      for (const parameter of openAi.requestParameters) {
        expect([parameter.name, await dialog(page).locator(`#openai-max-${parameter.name}`).count()]).toEqual([parameter.name, parameter.ceiling ? 1 : 0]);
      }

      await dialog(page).locator('#resource-type').selectOption({ label: 'Database pool (JDBC)' });
      const kinds = await dialog(page).locator('#jdbc-kind option').evaluateAll((options) => options.map((o) => (o as HTMLOptionElement).value));
      expect(kinds).toEqual(jdbc.databases.map((d: any) => d.kind));
      for (const database of jdbc.databases) {
        await dialog(page).locator('#jdbc-kind').selectOption(database.kind);
        const allowed = await dialog(page).locator('#jdbc-properties .allowed').innerText();
        for (const property of database.properties) expect(allowed).toContain(property.name);
      }
      expect(problems.console).toEqual([]);
      expect(problems.foreign).toEqual([]);
    } finally {
      await context.close();
    }
  });

  test('are read once when the page is opened, not each time the page reads the resources again', async () => {
    const { context, page, asked } = await resourcesPage('en-US');
    try {
      // The page reads the resources every 3 s; three of them, at least.
      await expect.poll(() => asked.filter((r) => r === 'GET /api/v1/resources').length, { timeout: 20_000 }).toBeGreaterThanOrEqual(3);
      expect(asked.filter((r) => r === 'GET /api/v1/resource-types')).toHaveLength(1);
      await page.getByRole('button', { name: 'Define a resource' }).first().click();
      await dialog(page).locator('#resource-type').selectOption({ label: 'OpenAI-compatible service' });
      expect(asked.filter((r) => r === 'GET /api/v1/resource-types')).toHaveLength(1);
    } finally {
      await context.close();
    }
  });

  test('are for an admin only, and hold no secret', async () => {
    const asDeveloper = await catalogOf(ada.token);
    expect([asDeveloper.status, ((await asDeveloper.json()) as any).error]).toEqual([403, 'forbidden']);
    expect((await catalogOf(null)).status).toBe(401);

    const { context, bodies } = await resourcesPage('en-US');
    try {
      const told = await (await catalogOf(root.token)).text();
      expect(told).not.toContain(marker);
      expect(told).not.toContain('llm-key');
      expect(bodies.length).toBeGreaterThan(0);
      expect(bodies.filter((body) => body.includes(marker))).toEqual([]);
    } finally {
      await context.close();
    }
  });

  test('say their marks in zh-TW', async () => {
    const { context, page } = await resourcesPage('zh-TW', ['zh-TW']);
    try {
      await page.getByRole('button', { name: '定義資源' }).first().click();
      await dialog(page).locator('#resource-type').selectOption('openai-compatible');
      await expect.poll(() => dialog(page).locator('#openai-endpoints .stateful').first().innerText()).toContain('服務端');
      expect(await dialog(page).locator('#openai-endpoints .default').first().innerText()).toContain('新資源');
      expect(await dialog(page).getByRole('button', { name: '全部啟用' }).count()).toBeGreaterThan(0);
    } finally {
      await context.close();
    }
  });
});

// The resource type `trace-export` in a real browser (Chrome), against a real packaged Engine with
// its PostgreSQL and a real PKCS12 keystore (#52, ADR-46, ADR-021): the type is in the form because
// the Engine's catalog has it, its capacity is the one the catalog fixes and cannot be edited, it is
// defined, changed, turned off, checked and deleted from the page, a check without the alias in the
// keystore says so, the marker of the keystore (E2E_SECRET_MARKER, the value of `llm-key`) is
// nowhere on the page or in what the page reads, and the form and the card fit a phone.
//
// Needs what resources.e2e.ts needs: E2E_ENGINE_URL, E2E_TOKENS, E2E_SECRET_MARKER, and the alias
// `llm-key` in the keystore. It makes one resource and deletes it at the end.

import type { Browser, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { horizontalOverflow, launchChrome, newContext, PHONE_390, settled, signInWith, watch } from './browser';
import { dialogIn, trouble } from './phone';

const need = (name: string, why: string) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set: ${why}`);
  return value;
};
const engineUrl = need('E2E_ENGINE_URL', 'the tests need a running Engine');
const callers = parseCallers(need('E2E_TOKENS', 'name:role:token of a developer and an admin'));
const root = callers.find((c) => c.role === 'admin')!;
const marker = need('E2E_SECRET_MARKER', 'the value of the secret llm-key of the keystore the Engine has');

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
});
afterAll(async () => {
  await browser?.close();
});

const api = (path: string, init: RequestInit = {}) =>
  fetch(`${engineUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${root.token}`, 'Content-Type': 'application/json' },
  });
const dialog = (page: Page) => page.getByRole('dialog');

describe('the trace-export type on the resources page', () => {
  test('is defined, changed, checked, turned off and deleted; its capacity is the Engine\'s and cannot be edited; no secret value is anywhere', async () => {
    const told = (await (await api('/api/v1/resource-types')).json()) as { types: Array<Record<string, any>> };
    const { fixedCapacity } = told.types.find((t) => t.type === 'trace-export')!;
    const name = `e2e-trace-${Date.now().toString(36)}`;
    const context = await newContext(browser, 'en-US');
    const page = await context.newPage();
    const problems = await watch(page, engineUrl);
    const bodies: string[] = [];
    page.on('response', async (response) => {
      try {
        bodies.push(await response.text());
      } catch {
        // An answer the page left: it has no body to read.
      }
    });
    try {
      await page.goto(`${engineUrl}/resources`);
      if ((await settled(page)) === 'sign-in') await signInWith(page, root.token);
      await page.locator('.resource, .rl-empty').first().waitFor();

      await page.getByRole('button', { name: 'Define a resource' }).first().click();
      await dialog(page).locator('#resource-type').selectOption({ label: 'Trace export (OTLP)' });
      await dialog(page).locator('#resource-name').fill(name);
      const capacity = dialog(page).locator('#resource-capacity');
      expect(await capacity.inputValue()).toBe(String(fixedCapacity));
      expect(await capacity.isEditable()).toBe(false);
      await dialog(page).locator('#trace-endpoint').fill('http://127.0.0.1:9/v1/traces');
      await dialog(page).locator('#trace-service-name').fill('runline-e2e');
      await dialog(page).locator('#resource-secret').selectOption('llm-key');
      await dialog(page).getByRole('button', { name: 'Define' }).click();

      const card = page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${name}$`) }) });
      await card.waitFor();
      expect(await card.locator('.settings dd').allInnerTexts()).toEqual(['http://127.0.0.1:9/v1/traces', 'runline-e2e']);
      expect(await card.locator('.secret .alias').innerText()).toBe('llm-key');
      expect(await card.locator('progress').count()).toBe(0);
      const made = (await (await api(`/api/v1/resources/${name}`)).json()) as any;
      expect(made).toMatchObject({ type: 'trace-export', capacity: fixedCapacity, secretAlias: 'llm-key' });

      await card.getByRole('button', { name: 'Change' }).click();
      expect(await dialog(page).locator('#resource-capacity').isEditable()).toBe(false);
      await dialog(page).locator('#trace-service-name').fill('runline-e2e-2');
      await dialog(page).getByRole('button', { name: 'Save' }).click();
      await expect.poll(async () => ((await (await api(`/api/v1/resources/${name}`)).json()) as any).settings.serviceName).toBe('runline-e2e-2');

      // Nothing listens at the endpoint: the check says it could not connect, and what the Engine says is shown.
      await card.getByRole('button', { name: 'Check' }).click();
      await expect.poll(async () => ((await (await api(`/api/v1/resources/${name}`)).json()) as any).lastCheck?.failure).toBe('connection_failed');
      expect(await card.locator('.last-check .outcome').innerText()).toContain('could not be reached');

      await card.getByRole('button', { name: 'Change' }).click();
      await dialog(page).locator('#resource-enabled').uncheck();
      await dialog(page).getByRole('button', { name: 'Save' }).click();
      await expect.poll(async () => ((await (await api(`/api/v1/resources/${name}`)).json()) as any).enabled).toBe(false);

      const held = await page.evaluate(() => ({ dom: document.documentElement.outerHTML, storage: JSON.stringify({ ...localStorage, ...sessionStorage }) }));
      expect(held.dom).not.toContain(marker);
      expect(held.storage).not.toContain(marker);
      expect(bodies.filter((body) => body.includes(marker))).toEqual([]);

      await card.getByRole('button', { name: 'Delete' }).click();
      await dialog(page).getByRole('button', { name: 'Delete the resource' }).click();
      await card.waitFor({ state: 'detached' });
      expect((await api(`/api/v1/resources/${name}`)).status).toBe(404);
      expect(problems.console).toEqual([]);
      expect(problems.foreign).toEqual([]);
    } finally {
      await api(`/api/v1/resources/${name}`, { method: 'DELETE' });
      await context.close();
    }
  });

  test('on a phone the form and the card fit: nothing reaches past the window and what is touched is large enough', async () => {
    const name = `e2e-trace-phone-${Date.now().toString(36)}`;
    const created = await api('/api/v1/resources', {
      method: 'POST',
      body: JSON.stringify({
        name,
        type: 'trace-export',
        capacity: ((await (await api('/api/v1/resource-types')).json()) as any).types.find((t: any) => t.type === 'trace-export').fixedCapacity,
        settings: { endpoint: 'https://trace.example.invalid/api/public/otel/v1/traces', serviceName: 'runline-e2e' },
        secretAlias: 'llm-key',
      }),
    });
    expect(created.status).toBe(201);
    const context = await newContext(browser, 'en-US', undefined, PHONE_390);
    const page = await context.newPage();
    try {
      await page.goto(`${engineUrl}/resources`);
      if ((await settled(page)) === 'sign-in') await signInWith(page, root.token);
      const card = page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${name}$`) }) });
      await card.waitFor();
      expect(await horizontalOverflow(page)).toBe(0);

      await card.getByRole('button', { name: 'Change' }).click();
      await dialogIn(page, PHONE_390);
      expect(await trouble(page, '[role=dialog]')).toEqual([]);
    } finally {
      await api(`/api/v1/resources/${name}`, { method: 'DELETE' });
      await context.close();
    }
  });
});

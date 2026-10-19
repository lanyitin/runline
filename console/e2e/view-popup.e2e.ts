// The popup of a view (#29), in a real browser against a real packaged Engine: the data section of a
// pipeline lists its views, and the one chosen is in a popup of about 95% of the window that is open
// exactly when the address names a view; below 768px (ADR-048) it is the whole screen.

import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import type { Browser, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import {
  DESKTOP,
  PHONE_390,
  horizontalOverflow,
  launchChrome,
  newContext,
  settled,
  signInWith,
  watch,
} from './browser';

const engineUrl = process.env.E2E_ENGINE_URL;
if (!engineUrl) throw new Error('E2E_ENGINE_URL is not set: the tests need a running Engine');
const tokens = process.env.E2E_TOKENS;
if (!tokens) throw new Error('E2E_TOKENS is not set: name:role:token of a developer and an admin');
const jars = process.env.E2E_JARS;
if (!jars) throw new Error('E2E_JARS is not set: the directory of the sample jars');
const callers = parseCallers(tokens);
const ada = callers.find((c) => c.role === 'developer')!;
const root = callers.find((c) => c.role === 'admin')!;

async function api(who: { token: string }, method: string, path: string, body?: unknown) {
  const isBytes = body instanceof Uint8Array;
  const response = await fetch(`${engineUrl}/api/v1${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${who.token}`,
      ...(body === undefined ? {} : { 'Content-Type': isBytes ? 'application/octet-stream' : 'application/json' }),
    },
    body: body === undefined ? undefined : isBytes ? (body as unknown as BodyInit) : JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, body: text === '' ? null : JSON.parse(text) };
}

const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT'];
async function finished(runId: string) {
  for (let i = 0; i < 300; i++) {
    const run = await api(ada, 'GET', `/runs/${runId}`);
    if (TERMINAL.includes(run.body.state)) return run.body.state as string;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`run ${runId} did not end`);
}

const seeded = { hash: '', uploader: '' };
let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
  const uploaded = await api(ada, 'POST', '/artifacts', readFileSync(join(jars, 'demo-views.jar')));
  expect(uploaded.status).toBeLessThan(300);
  seeded.hash = uploaded.body.contentHash;
  seeded.uploader = (await api(ada, 'GET', `/artifacts/${seeded.hash}`)).body.uploader;

  const resource = `popup-db-${Date.now().toString(36)}`;
  expect(
    (await api(root, 'POST', '/resources', { name: resource, capacity: 1, type: 'jdbc-pool', settings: { kind: 'sqlite', path: `${resource}.db` } })).status,
  ).toBe(201);
  expect((await api(ada, 'PUT', `/definitions/${seeded.hash}/demo-views/resource-bindings/view-db`, { resource })).status).toBe(200);
  const filled = await api(ada, 'POST', '/runs', { contentHash: seeded.hash, pipeline: 'demo-views' });
  expect(await finished(filled.body.runId)).toBe('SUCCEEDED');
}, 120_000);
afterAll(async () => {
  await browser?.close();
});

type Viewport = { width: number; height: number };
const pipelinePath = (extra = '') =>
  `/pipelines/${seeded.hash}?pipeline=demo-views&uploader=${seeded.uploader}${extra ? `&${extra}` : ''}`;

async function open(viewport: Viewport, path: string) {
  const context = await newContext(browser, 'en-US', undefined, viewport);
  const page = await context.newPage();
  const problems = await watch(page, engineUrl!);
  await page.goto(`${engineUrl}${path}`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, ada.token);
  await page.locator('main h1').first().waitFor();
  return { page, problems, context };
}

const dialog = (page: Page) => page.getByRole('dialog');
const rows = (page: Page) => dialog(page).locator('.rl-table-wrap table tbody tr');
const viewOf = (page: Page) => new URL(page.url()).searchParams.get('view');

describe('the popup of a view on a desktop window', () => {
  test('is not there until a view is chosen; choosing opens it at about 95% of the window, centred, and the page behind does not scroll', async () => {
    const { page, problems, context } = await open(DESKTOP, pipelinePath());
    await page.locator('li.view[data-view="articles"]').waitFor();
    expect(await dialog(page).count()).toBe(0);
    expect(await page.locator('section.views table').count()).toBe(0);

    await page.locator('li.view[data-view="articles"] button.open').click();
    await rows(page).first().waitFor();

    const box = (await dialog(page).boundingBox())!;
    expect(box.width / DESKTOP.width).toBeGreaterThan(0.93);
    expect(box.width / DESKTOP.width).toBeLessThan(0.97);
    expect(box.height / DESKTOP.height).toBeGreaterThan(0.93);
    expect(box.height / DESKTOP.height).toBeLessThan(0.97);
    expect(Math.abs(box.x + box.width / 2 - DESKTOP.width / 2)).toBeLessThan(2);
    expect(Math.abs(box.y + box.height / 2 - DESKTOP.height / 2)).toBeLessThan(2);
    expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).toBe('hidden');
    expect(await page.getByRole('dialog', { name: 'Articles' }).count()).toBe(1);
    expect(problems.console).toEqual([]);
    await context.close();
  });

  test('is open with the table when its address is opened directly, and again after a reload', async () => {
    const { page, context } = await open(DESKTOP, pipelinePath('view=articles'));
    await rows(page).first().waitFor();
    const first = await rows(page).first().innerText();

    await page.reload();
    await rows(page).first().waitFor();

    expect(viewOf(page)).toBe('articles');
    expect(await rows(page).first().innerText()).toBe(first);
    await context.close();
  });

  test('is closed by Escape: the address loses the view and the keyboard is back on the item that opened it', async () => {
    const { page, context } = await open(DESKTOP, pipelinePath());
    const opener = page.locator('li.view[data-view="articles"] button.open');
    await opener.click();
    await rows(page).first().waitFor();

    await page.keyboard.press('Escape');

    await dialog(page).waitFor({ state: 'detached' });
    expect(viewOf(page)).toBeNull();
    expect(await opener.evaluate((el) => el === document.activeElement)).toBe(true);
    await context.close();
  });

  test('keeps the keyboard inside while open', async () => {
    const { page, context } = await open(DESKTOP, pipelinePath('view=articles'));
    await rows(page).first().waitFor();

    for (let i = 0; i < 30; i++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null)).toBe(true);
    }
    await context.close();
  });

  test('stays open through a drill, and goes back to the view it left with its values', async () => {
    const { page, context } = await open(DESKTOP, pipelinePath('view=feeds'));
    await rows(page).first().waitFor();

    await rows(page).first().locator('button.drill').click();
    await page.waitForFunction(() => new URL(location.href).searchParams.get('view') === 'articles');
    expect(await dialog(page).count()).toBe(1);
    await rows(page).first().waitFor();

    await dialog(page).locator('button.back').click();
    await page.waitForFunction(() => new URL(location.href).searchParams.get('view') === 'feeds');
    expect(await dialog(page).count()).toBe(1);
    expect(await dialog(page).locator('button.back').count()).toBe(0);
    await context.close();
  });

  test('is closed by the back button of the browser', async () => {
    const { page, context } = await open(DESKTOP, pipelinePath());
    await page.locator('li.view[data-view="articles"] button.open').click();
    await rows(page).first().waitFor();

    await page.goBack();

    await dialog(page).waitFor({ state: 'detached' });
    expect(viewOf(page)).toBeNull();
    await context.close();
  });
});

describe('the popup of a view on a phone', () => {
  test('is the whole screen, the page does not scroll sideways, the controls stay in view and the table scrolls in its own container', async () => {
    const { page, context } = await open(PHONE_390, pipelinePath('view=articles'));
    await rows(page).first().waitFor();

    const box = (await dialog(page).boundingBox())!;
    expect(Math.round(box.x)).toBe(0);
    expect(Math.round(box.y)).toBe(0);
    expect(Math.round(box.width)).toBe(PHONE_390.width);
    expect(Math.round(box.height)).toBe(PHONE_390.height);
    expect(await horizontalOverflow(page)).toBe(0);
    const close = (await dialog(page).locator('button.close').boundingBox())!;
    expect(close.x + close.width).toBeLessThanOrEqual(PHONE_390.width);
    expect(close.width).toBeGreaterThanOrEqual(44);
    expect(close.height).toBeGreaterThanOrEqual(44);
    const held = await rows(page).first().locator('td, th').first().evaluate((el) => getComputedStyle(el).position);
    expect(held).toBe('sticky');
    await context.close();
  });
});

// The pages of administration, the forms and the dialogs (WI-63, ADR-048) on a phone, in a real
// browser, against a real packaged Engine: the triggers (list, page, form), the allow-list, the
// resources with their typed forms and the secrets, the Engine, the upload, the form of a run, the
// dialogs (the questions, the preview of the deletion of a version, the secret of a webhook) and the
// pages that need no sign-in or no rights (the sign-in, the page of a path that is none, the page of
// a path that is refused). At 360px, 390px and 768px (and 767px, the last of the narrow layout) none
// of them scrolls sideways, what is touched is 44px high or can be hit within 44px, a table keeps to
// its own container with the first column held, and a dialog is in the window with its buttons.
// The pages of the users need an Engine with a provider: oidc.e2e.ts (npm run e2e:oidc) has them.

import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import type { Browser, Locator, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { uniqueJar } from '../test-support/zip';
import {
  NARROWEST_WIDE,
  PHONE_360,
  PHONE_390,
  WIDEST_NARROW,
  horizontalOverflow,
  launchChrome,
  newContext,
  settled,
  signInWith,
  watch,
} from './browser';
import { dialogIn, trouble, type Viewport } from './phone';

const engineUrl = process.env.E2E_ENGINE_URL;
if (!engineUrl) throw new Error('E2E_ENGINE_URL is not set: the tests need a running Engine');
const tokens = process.env.E2E_TOKENS;
if (!tokens) throw new Error('E2E_TOKENS is not set: name:role:token of a developer and an admin');
const jars = process.env.E2E_JARS;
if (!jars) throw new Error('E2E_JARS is not set: the directory of the sample jars');
const callers = parseCallers(tokens);
const ada = callers.find((c) => c.role === 'developer')!;
const root = callers.find((c) => c.role === 'admin')!;

const stamp = Date.now().toString(36);
// Names that cannot be broken at a space: they are what pushes a page wider than a phone.
const LONG_TRIGGER = `e2e.long-trigger-name-${stamp}-${'x'.repeat(40)}`;
const LONG_RESOURCE = `e2e-long-resource-name-${stamp}-${'y'.repeat(40)}`;

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

const jarBytes = (name: string) => readFileSync(join(jars, `${name}.jar`));
const upload = async (bytes: Uint8Array) => {
  const answer = await api(ada, 'POST', '/artifacts', bytes);
  expect(answer.status).toBeLessThan(300);
  return answer.body.contentHash as string;
};

const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT'];
async function finished(runId: string) {
  for (let i = 0; i < 300; i++) {
    const run = await api(ada, 'GET', `/runs/${runId}`);
    if (TERMINAL.includes(run.body.state)) return run.body.state as string;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`run ${runId} did not end`);
}

const seeded = { slowHash: '', intakeHash: '', versionHash: '', uploader: '' };

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
  seeded.slowHash = await upload(jarBytes('demo-slow'));
  seeded.uploader = (await api(ada, 'GET', `/artifacts/${seeded.slowHash}`)).body.uploader;
  seeded.intakeHash = await upload(jarBytes('demo-intake'));
  // A version of its own with a run that has ended: its deletion takes the run along.
  seeded.versionHash = await upload(uniqueJar(jarBytes('demo-slow'), `wi63-${stamp}`) as Uint8Array);
  const run = await api(ada, 'POST', '/runs', {
    contentHash: seeded.versionHash,
    pipeline: 'demo-slow',
    parameters: { steps: '1', delayMillis: '10' },
  });
  expect(await finished(run.body.runId)).toBe('SUCCEEDED');

  const trigger = (name: string, kind: string, extra: object) =>
    api(root, 'POST', '/triggers', { name, kind, contentHash: seeded.slowHash, pipeline: 'demo-slow', parameters: { steps: '1' }, ...extra });
  expect((await trigger(LONG_TRIGGER, 'webhook', {})).status).toBeLessThan(300);
  expect((await trigger(`${LONG_TRIGGER}-cron`, 'cron', { cron: '0 3 * * *' })).status).toBeLessThan(300);
  expect((await api(root, 'POST', '/resources', { name: LONG_RESOURCE, capacity: 1, type: 'file', settings: { path: `wi63/${'z'.repeat(80)}.txt` } })).status).toBe(201);
  expect(
    (await api(root, 'POST', '/resources', { name: `${LONG_RESOURCE}-db`, capacity: 1, type: 'jdbc-pool', settings: { kind: 'sqlite', path: `${'w'.repeat(90)}.db` } })).status,
  ).toBe(201);
}, 120_000);
afterAll(async () => {
  // The version of its own and its run go with it: the other scripts count the versions of demo-slow.
  await api(root, 'DELETE', `/artifacts/${seeded.versionHash}?uploader=${ada.name}&runs=1`);
  await api(root, 'DELETE', `/triggers/${LONG_TRIGGER}`);
  await api(root, 'DELETE', `/triggers/${LONG_TRIGGER}-cron`);
  await api(root, 'DELETE', `/resources/${LONG_RESOURCE}`);
  await api(root, 'DELETE', `/resources/${LONG_RESOURCE}-db`);
  await browser?.close();
});

async function open(viewport: Viewport, path: string, who: { token: string } | null = root, locale = 'en-US') {
  const context = await newContext(browser, locale, undefined, viewport);
  const page = await context.newPage();
  const problems = await watch(page, engineUrl!);
  await page.goto(`${engineUrl}${path}`);
  if (who !== null && (await settled(page)) === 'sign-in') await signInWith(page, who.token);
  return { page, problems, context };
}

const viewports: [string, Viewport][] = [
  ['360px', PHONE_360],
  ['390px', PHONE_390],
  ['767px', WIDEST_NARROW],
  ['768px', NARROWEST_WIDE],
];

const pipelinePath = (hash: string, name: string) => `/pipelines/${hash}?pipeline=${name}&uploader=${seeded.uploader}`;

/** The pages, and what each needs on screen before it is measured. */
const PAGES: [string, () => string, (page: Page) => Locator][] = [
  ['the list of triggers', () => '/triggers', (page) => page.locator('main table tbody tr').first()],
  ['a webhook trigger', () => `/triggers/detail?name=${LONG_TRIGGER}`, (page) => page.locator('main h1')],
  ['a cron trigger', () => `/triggers/detail?name=${LONG_TRIGGER}-cron`, (page) => page.locator('main h1')],
  ['the form of a trigger', () => `/triggers/new?contentHash=${seeded.slowHash}&pipeline=demo-slow`, (page) => page.locator('#trigger-name')],
  ['the form of a trigger with the caller to give a parameter', () => `/triggers/new?contentHash=${seeded.intakeHash}&pipeline=demo-intake`, (page) => page.locator('#trigger-name')],
  ['the change of a trigger', () => `/triggers/edit?name=${LONG_TRIGGER}-cron`, (page) => page.locator('main form')],
  ['the allow-list', () => '/allowlist', (page) => page.locator('main table tbody tr').first()],
  ['the resources', () => '/resources', (page) => page.locator('.resource').first()],
  ['the Engine', () => '/engine', (page) => page.locator('main h1')],
  ['the upload', () => '/upload', (page) => page.locator('main h1')],
  ['the form of a run', () => `/runs/new?contentHash=${seeded.slowHash}&pipeline=demo-slow&uploader=${seeded.uploader}`, (page) => page.locator('main form')],
  ['a version to delete', () => pipelinePath(seeded.versionHash, 'demo-slow'), (page) => page.getByRole('button', { name: 'Delete this version' })],
];

describe.each(viewports)('the pages of administration at %s', (_name, viewport) => {
  test.each(PAGES)('%s does not scroll sideways, and what is touched can be touched', async (_page, path, ready) => {
    const { page, problems, context } = await open(viewport, path());
    await ready(page).waitFor();
    await page.waitForTimeout(300);

    expect(await horizontalOverflow(page)).toBe(0);
    // The size of what is touched is a rule of the narrow layout.
    if (viewport.width < 768) expect(await trouble(page, 'main')).toEqual([]);
    expect(problems.console).toEqual([]);
    await context.close();
  });

  test('the sign-in, the page of a path that is none and the page that is refused do not scroll sideways', async () => {
    const signIn = await open(viewport, '/', null);
    await signIn.page.locator('form input').waitFor();
    expect(await horizontalOverflow(signIn.page)).toBe(0);
    if (viewport.width < 768) expect(await trouble(signIn.page, 'body')).toEqual([]);
    await signIn.context.close();

    const none = await open(viewport, '/no/such/page');
    await none.page.locator('main h1').waitFor();
    expect(await horizontalOverflow(none.page)).toBe(0);
    if (viewport.width < 768) expect(await trouble(none.page, 'main')).toEqual([]);
    await none.context.close();

    const refused = await open(viewport, '/triggers', ada);
    await refused.page.locator('main h1').waitFor();
    expect(await refused.page.locator('main table').count()).toBe(0);
    expect(await horizontalOverflow(refused.page)).toBe(0);
    if (viewport.width < 768) expect(await trouble(refused.page, 'main')).toEqual([]);
    await refused.context.close();
  });
});

const narrow = describe.each([
  ['360px', PHONE_360],
  ['390px', PHONE_390],
  ['767px', WIDEST_NARROW],
]);

const containerOf = (table: Locator) =>
  table.evaluate((el) => {
    let node: HTMLElement | null = el.parentElement;
    while (node && getComputedStyle(node).overflowX === 'visible') node = node.parentElement;
    return node === null ? null : node.tagName === 'MAIN' || node.tagName === 'BODY' ? 'page' : 'own';
  });
const heldFirst = (table: Locator) =>
  table.locator('tbody tr').first().locator('td, th').first().evaluate((el) => getComputedStyle(el).position);

narrow('the tables of administration, below 768px,', (_name, viewport) => {
  test.each([
    ['the triggers', '/triggers', 'main table'],
    ['the allow-list', '/allowlist', 'main table'],
    ['a trigger with its firings', `/triggers/detail?name=${LONG_TRIGGER}`, 'main table'],
  ])('%s hold the first column while they scroll inside their own container', async (_what, path, selector) => {
    const { page, context } = await open(viewport, path);
    const tables = page.locator(selector);
    await tables.first().waitFor();

    for (const table of await tables.all()) {
      expect(await containerOf(table)).toBe('own');
      expect(await heldFirst(table)).toBe('sticky');
    }
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });

  test('the secrets of the keystore do too', async () => {
    const { page, context } = await open(viewport, '/resources');
    await page.locator('.resource').first().waitFor();
    for (const table of await page.locator('section.secrets table').all()) {
      expect(await containerOf(table)).toBe('own');
      expect(await heldFirst(table)).toBe('sticky');
    }
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });

  test('the actions of a row can be pressed: the page of the trigger opens from the list', async () => {
    const { page, context } = await open(viewport, '/triggers');
    const row = page.locator('main table tbody tr').filter({ hasText: LONG_TRIGGER }).first();
    await row.waitFor();
    await row.getByRole('link', { name: 'Details' }).click();
    await page.waitForURL(/\/triggers\/detail\?name=/);
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });

  test('a form is one column: the labels and the notes are whole, the fields are in the window, the buttons are there', async () => {
    const { page, context } = await open(viewport, `/triggers/new?contentHash=${seeded.intakeHash}&pipeline=demo-intake`);
    await page.locator('#trigger-name').waitFor();

    const fields = await page.locator('main form .rl-field').evaluateAll((els) =>
      els.map((el) => ({ x: Math.round(el.getBoundingClientRect().x), right: el.getBoundingClientRect().right })),
    );
    expect(fields.length).toBeGreaterThan(2);
    for (const field of fields) expect(field.right).toBeLessThanOrEqual(viewport.width);
    for (const control of await page.locator('main form button.submit, main form a.cancel').all()) {
      const r = (await control.boundingBox())!;
      expect(r.x).toBeGreaterThanOrEqual(0);
      expect(r.x + r.width).toBeLessThanOrEqual(viewport.width);
      expect(r.height).toBeGreaterThanOrEqual(44);
    }
    const clipped = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('main label, main .rl-help, main legend')]
        .filter((el) => el.getBoundingClientRect().width > 0 && el.scrollWidth > el.clientWidth + 1)
        .map((el) => (el.textContent ?? '').slice(0, 30)),
    );
    expect(clipped).toEqual([]);
    await context.close();
  });

  test('a long default value of a parameter is shown in full and can be edited in the form of a run', async () => {
    const { page, context } = await open(viewport, `/runs/new?contentHash=${seeded.slowHash}&pipeline=demo-slow&uploader=${seeded.uploader}`);
    const field = page.locator('main form input[type=text], main form textarea').first();
    await field.waitFor();
    const long = 'word'.repeat(120);
    await field.fill(long);
    expect(await field.inputValue()).toBe(long);
    const r = (await field.boundingBox())!;
    expect(r.x + r.width).toBeLessThanOrEqual(viewport.width);
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });
});

narrow('the dialogs, below 768px,', (_name, viewport) => {
  test.each([
    ['counter', 'Counter'],
    ['file', 'File'],
    ['jdbc-pool', 'Database pool (JDBC)'],
    ['openai-compatible', 'OpenAI-compatible service'],
  ])('the form to define a resource of the type %s is in the window', async (_type, label) => {
    const { page, context } = await open(viewport, '/resources');
    await page.locator('.resource').first().waitFor();
    await page.getByRole('button', { name: 'Define a resource' }).first().click();
    await page.locator('#resource-type').selectOption({ label });
    await page.locator('#resource-name').fill(LONG_RESOURCE);

    await dialogIn(page, viewport);
    await context.close();
  });

  test('the form of a long name, filled and refused, stays in the window', async () => {
    const { page, context } = await open(viewport, '/resources');
    const card = page.locator('.resource').filter({ hasText: LONG_RESOURCE }).first();
    await card.waitFor();
    await card.getByRole('button', { name: 'Change' }).first().click();
    await dialogIn(page, viewport);
    await context.close();
  });

  test('the question of the deletion of a resource is in the window', async () => {
    const { page, context } = await open(viewport, '/resources');
    const card = page.locator('.resource').filter({ hasText: LONG_RESOURCE }).first();
    await card.waitFor();
    await card.getByRole('button', { name: 'Delete' }).click();
    await page.getByRole('dialog').locator('.preview').waitFor();
    await dialogIn(page, viewport);
    await context.close();
  });

  test('the form of an entry of the allow-list, and its preview, are in the window', async () => {
    const { page, context } = await open(viewport, '/allowlist');
    await page.locator('main table tbody tr').first().waitFor();
    await page.locator('.rl-page-head button.primary').click();
    const dialog = await dialogIn(page, viewport);
    await dialog.locator('input.rl-input').first().fill(`wi63.${stamp}.${'p'.repeat(60)}`);
    await dialog.locator('footer button.primary').click();
    await dialog.locator('[data-step="preview"]').waitFor();
    await dialogIn(page, viewport);
    await context.close();
  });

  test('the question of the deletion of a trigger is in the window', async () => {
    const { page, context } = await open(viewport, `/triggers/detail?name=${LONG_TRIGGER}-cron`);
    await page.getByRole('button', { name: 'Delete' }).click();
    await dialogIn(page, viewport);
    await context.close();
  });

  test('the preview of the deletion of a version with a run is in the window, and the buttons stay when the window is low', async () => {
    const { page, context } = await open(viewport, pipelinePath(seeded.versionHash, 'demo-slow'));
    await page.getByRole('button', { name: 'Delete this version' }).click();
    await page.getByRole('dialog').locator('.impact, .preview, table').first().waitFor();
    await dialogIn(page, viewport);

    // A window that is low (a phone on its side, a keyboard open): the body of the dialog scrolls, the buttons stay.
    await page.setViewportSize({ width: viewport.width, height: 320 });
    await dialogIn(page, { width: viewport.width, height: 320 });
    await context.close();
  });

  test('the secret of a webhook, shown once, is in the window', async () => {
    const name = `e2e.wi63-hook-${stamp}-${viewport.width}`;
    const { page, context } = await open(viewport, `/triggers/new?contentHash=${seeded.slowHash}&pipeline=demo-slow`);
    await page.locator('input[name="kind"][value="webhook"]').check();
    await page.locator('#trigger-name').fill(name);
    await page.locator('#param-steps').fill('1');
    await page.locator('button.submit').click();
    await dialogIn(page, viewport);
    await page.getByRole('dialog').locator('input[type=checkbox]').check();
    await page.getByRole('dialog').getByRole('button', { name: 'Done' }).click();
    await api(root, 'DELETE', `/triggers/${name}`);
    await context.close();
  });
});

narrow('the upload, below 768px,', (_name, viewport) => {
  test('a file of a long name is chosen and uploaded, and the result with its links is in the window', async () => {
    const { page, context } = await open(viewport, '/upload');
    await page.locator('input[type=file]').waitFor({ state: 'attached' });
    await page.locator('input[type=file]').setInputFiles({
      name: `${'a-file-with-a-long-name-'.repeat(8)}.jar`,
      mimeType: 'application/java-archive',
      buffer: Buffer.from(jarBytes('demo-slow')),
    });
    await page.locator('.chosen').waitFor();
    expect(await horizontalOverflow(page)).toBe(0);
    expect(await trouble(page, 'main')).toEqual([]);

    await page.locator('button.submit').click();
    await page.locator('section.result').waitFor();
    expect(await horizontalOverflow(page)).toBe(0);
    expect(await trouble(page, 'main')).toEqual([]);
    // What the page uploaded as the admin is taken back, so that no other script meets a second uploader of the same version.
    const hash = await page.locator('section.result .hash').getAttribute('title');
    expect((await api(root, 'DELETE', `/artifacts/${hash}?uploader=${root.name}`)).status).toBeLessThan(300);
    await context.close();
  });
});

narrow('the controls of a form, below 768px,', (_name, viewport) => {
  test('a tick box and a choice can be hit within 44px', async () => {
    const { page, context } = await open(viewport, `/triggers/new?contentHash=${seeded.intakeHash}&pipeline=demo-intake`);
    await page.locator('#trigger-name').waitFor();
    expect(await trouble(page, 'main form')).toEqual([]);
    await context.close();
  });
});

describe('the pages of administration, from 768px,', () => {
  test('keep the tables and the dialogs as they were', async () => {
    const { page, context } = await open(NARROWEST_WIDE, '/triggers');
    const row = page.locator('main table tbody tr').first();
    await row.waitFor();
    expect(await row.evaluate((el) => getComputedStyle(el).display)).toBe('table-row');
    expect(await heldFirst(page.locator('main table').first())).toBe('static');
    await context.close();
  });
});

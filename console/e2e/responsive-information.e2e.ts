// The pages that show information (WI-62, ADR-048) on a phone, in a real browser, against a real
// packaged Engine: the lists of runs and pipelines and the overview are cards below 768px and the
// table above; the run (with a long log line and a failure), the pipeline and its view do not make
// the page scroll sideways at any phone size; wide tables scroll inside their own container with
// the first column held; what is touched is reachable. The other routes are WI-63 and WI-64.

import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import type { Browser, Locator, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
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

const engineUrl = process.env.E2E_ENGINE_URL;
if (!engineUrl) throw new Error('E2E_ENGINE_URL is not set: the tests need a running Engine');
const tokens = process.env.E2E_TOKENS;
if (!tokens) throw new Error('E2E_TOKENS is not set: name:role:token of a developer and an admin');
const jars = process.env.E2E_JARS;
if (!jars) throw new Error('E2E_JARS is not set: the directory of the sample jars');
const callers = parseCallers(tokens);
const ada = callers.find((c) => c.role === 'developer')!;
const root = callers.find((c) => c.role === 'admin')!;

// Words that cannot be broken at a space: they are what pushes a page wider than a phone.
const LONG = 'long'.repeat(100);

const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT'];

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

const upload = async (jar: string) => {
  const answer = await api(ada, 'POST', '/artifacts', readFileSync(join(jars, jar)));
  expect(answer.status).toBeLessThan(300);
  return answer.body.contentHash as string;
};

async function finished(runId: string) {
  for (let i = 0; i < 300; i++) {
    const run = await api(ada, 'GET', `/runs/${runId}`);
    if (TERMINAL.includes(run.body.state)) return run.body.state as string;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`run ${runId} did not end`);
}

const seeded = {
  slowHash: '',
  /** A run that ended, with a log line that has no space in it. */
  logged: '',
  /** A run that failed with a message and a trace that have no space in them. */
  failed: '',
  /** A run still going, for the cancel. */
  running: '',
  viewsHash: '',
  /** The name the Engine knows the developer by: the token's, whatever the test calls it. */
  uploader: '',
};

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
  seeded.slowHash = await upload('demo-slow.jar');
  seeded.uploader = (await api(ada, 'GET', `/artifacts/${seeded.slowHash}`)).body.uploader;
  const failingHash = await upload('demo-failing.jar');
  seeded.viewsHash = await upload('demo-views.jar');

  const logged = await api(ada, 'POST', '/runs', {
    contentHash: seeded.slowHash,
    pipeline: 'demo-slow',
    parameters: { label: LONG, steps: '2', delayMillis: '50' },
  });
  seeded.logged = logged.body.runId;
  const failed = await api(ada, 'POST', '/runs', {
    contentHash: failingHash,
    pipeline: 'demo-failing',
    parameters: { reason: LONG },
  });
  seeded.failed = failed.body.runId;

  // The data of a view: a database of its own, bound to the role, filled by a run of the pipeline.
  const resource = `views-db-${Date.now().toString(36)}`;
  expect(
    (await api(root, 'POST', '/resources', { name: resource, capacity: 1, type: 'jdbc-pool', settings: { kind: 'sqlite', path: `${resource}.db` } })).status,
  ).toBe(201);
  expect((await api(ada, 'PUT', `/definitions/${seeded.viewsHash}/demo-views/resource-bindings/view-db`, { resource })).status).toBe(200);
  const filled = await api(ada, 'POST', '/runs', { contentHash: seeded.viewsHash, pipeline: 'demo-views' });
  expect(await finished(filled.body.runId)).toBe('SUCCEEDED');

  expect(await finished(seeded.logged)).toBe('SUCCEEDED');
  expect(await finished(seeded.failed)).toBe('FAILED');

  const running = await api(ada, 'POST', '/runs', {
    contentHash: seeded.slowHash,
    pipeline: 'demo-slow',
    parameters: { steps: '120', delayMillis: '1000' },
  });
  seeded.running = running.body.runId;
}, 120_000);
afterAll(async () => {
  if (seeded.running) await api(ada, 'POST', `/runs/${seeded.running}/cancel`);
  await browser?.close();
});

type Viewport = { width: number; height: number };

async function open(viewport: Viewport, path: string, locale = 'en-US') {
  const context = await newContext(browser, locale, undefined, viewport);
  const page = await context.newPage();
  const problems = await watch(page, engineUrl!);
  await page.goto(`${engineUrl}${path}`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, ada.token);
  await page.locator('main h1').first().waitFor();
  return { page, problems, context };
}

const pipelinePath = (hash: string, name: string) => `/pipelines/${hash}?pipeline=${name}&uploader=${seeded.uploader}`;

/** The pages of information and what each needs on screen before it can be measured. */
const PAGES: [string, () => string, (page: Page) => Locator][] = [
  ['the overview', () => '/', (page) => page.locator('main table tbody tr').first()],
  ['the list of runs', () => '/runs', (page) => page.locator('main table tbody tr').first()],
  ['a run with a long log line', () => `/runs/${seeded.logged}`, (page) => page.locator('.panel .line').first()],
  ['a run that failed', () => `/runs/${seeded.failed}`, (page) => page.locator('section.failure')],
  ['a run that goes on', () => `/runs/${seeded.running}`, (page) => page.locator('button.cancel')],
  ['the list of pipelines', () => '/pipelines', (page) => page.locator('main table tbody tr').first()],
  ['a pipeline', () => pipelinePath(seeded.slowHash, 'demo-slow'), (page) => page.locator('main table').first()],
  ['the views of a pipeline', () => pipelinePath(seeded.viewsHash, 'demo-views'), (page) => page.locator('li.view[data-view="articles"]')],
];

describe.each([
  ['360px', PHONE_360],
  ['390px', PHONE_390],
  ['767px', WIDEST_NARROW],
  ['768px', NARROWEST_WIDE],
])('the pages of information at %s', (_name, viewport) => {
  test.each(PAGES)('%s does not scroll sideways', async (_page, path, ready) => {
    const { page, problems, context } = await open(viewport, path());
    await ready(page).waitFor();

    expect(await horizontalOverflow(page)).toBe(0);
    expect(problems.console).toEqual([]);
    await context.close();
  });

  test('a table of a view does not scroll the page either', async () => {
    const { page, context } = await open(viewport, pipelinePath(seeded.viewsHash, 'demo-views'));
    await page.locator('li.view[data-view="articles"] button.open').click();
    await page.locator('.rl-table-wrap table tbody tr').first().waitFor();

    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });
});

const cardsAt = describe.each([
  ['360px', PHONE_360],
  ['390px', PHONE_390],
  ['767px', WIDEST_NARROW],
]);

/** How a row is laid out: as a row of a table, or as a block of its own. */
const rowDisplay = (row: Locator) => row.evaluate((el) => getComputedStyle(el).display);
/** The words a card puts before each of its cells: the generated text of the cells, which innerText does not have. */
const labelsOf = (row: Locator) =>
  row.locator(':scope > td').evaluateAll((cells) =>
    cells.map((cell) => getComputedStyle(cell, '::before').content.replace(/^"|"$/g, '')),
  );
const headVisible = (page: Page) =>
  page.locator('main table.rl-cards thead').first().evaluate((el) => el.getBoundingClientRect().height > 0);

cardsAt('the lists, below 768px,', (_name, viewport) => {
  test('are cards: a run shows its state, pipeline, source, duration and time, and all of it opens the run', async () => {
    const { page, context } = await open(viewport, '/runs');
    const row = page.locator('main table tbody tr').filter({ hasText: 'demo-failing' }).first();
    await row.waitFor();

    expect(await rowDisplay(row)).not.toBe('table-row');
    expect(await headVisible(page)).toBe(false);
    await row.scrollIntoViewIfNeeded();
    const box = (await row.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
    // What the table says, the card says, each part with its name.
    const text = (await row.innerText()).toLowerCase();
    for (const part of ['demo-failing', 'failed']) expect(text).toContain(part);
    expect(await labelsOf(row)).toEqual(['Run', 'Pipeline', 'State', 'Source', 'Duration', 'Created']);
    // The whole card is the way in: a point of it that is not the link leads to the run too.
    await page.mouse.click(box.x + box.width - 8, box.y + box.height - 8);
    await page.waitForURL(/\/runs\/[0-9a-f-]{36}$/);
    await context.close();
  });

  test('can be filtered and paged', async () => {
    const { page, context } = await open(viewport, '/runs');
    await page.locator('main table tbody tr').first().waitFor();

    for (const control of [page.locator('#runs-pipeline'), page.locator('#runs-state'), page.locator('button.refresh')]) {
      const rect = (await control.boundingBox())!;
      expect(rect.x).toBeGreaterThanOrEqual(0);
      expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width);
      expect(rect.height).toBeGreaterThanOrEqual(44);
    }
    await page.selectOption('#runs-state', 'FAILED');
    const rows = page.locator('main table tbody tr');
    await rows.first().waitFor();
    for (const state of await rows.locator('.badge').allInnerTexts()) expect(state.toLowerCase()).toContain('failed');
    await context.close();
  });

  test('are cards for the pipelines too, with the name, verdict, version, who and when, and the two links', async () => {
    const { page, context } = await open(viewport, '/pipelines');
    const row = page.locator('main table tbody tr').filter({ hasText: 'demo-slow' }).first();
    await row.waitFor();

    expect(await rowDisplay(row)).not.toBe('table-row');
    expect(await headVisible(page)).toBe(false);
    const text = (await row.innerText()).toLowerCase();
    for (const part of ['demo-slow', 'safe', 'details', 'run']) expect(text).toContain(part);
    expect(await labelsOf(row)).toEqual(['Pipeline', 'Verdict', 'Version (hash)', 'Uploaded by', 'Uploaded', 'Allow-list', 'Actions']);
    await row.scrollIntoViewIfNeeded();
    const box = (await row.boundingBox())!;
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
    for (const link of await row.locator('a').all()) expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await context.close();
  });

  test('are cards in the overview', async () => {
    const { page, context } = await open(viewport, '/');
    const row = page.locator('main table tbody tr').first();
    await row.waitFor();

    expect(await rowDisplay(row)).not.toBe('table-row');
    await row.scrollIntoViewIfNeeded();
    const box = (await row.boundingBox())!;
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
    await page.mouse.click(box.x + box.width - 8, box.y + box.height - 8);
    await page.waitForURL(/\/runs\/[0-9a-f-]{36}$/);
    await context.close();
  });

  test('say their words in Chinese too', async () => {
    const { page, context } = await open(viewport, '/runs', 'zh-TW');
    const row = page.locator('main table tbody tr').first();
    await row.waitFor();
    expect(await labelsOf(row)).toEqual(['Run', 'Pipeline', '狀態', '來源', '耗時', '建立時間']);
    await context.close();
  });
});

describe('the lists, from 768px,', () => {
  test.each([
    ['the runs', '/runs'],
    ['the pipelines', '/pipelines'],
    ['the overview', '/'],
  ])('stay a table: %s', async (_name, path) => {
    const { page, context } = await open(NARROWEST_WIDE, path);
    const row = page.locator('main table tbody tr').first();
    await row.waitFor();

    expect(await rowDisplay(row)).toBe('table-row');
    expect(await page.locator('main table thead').first().evaluate((el) => el.getBoundingClientRect().height)).toBeGreaterThan(0);
    expect((await labelsOf(row)).every((content) => content === '' || content === 'none' || content === 'normal')).toBe(true);
    await context.close();
  });
});

cardsAt('a run, below 768px,', (_name, viewport) => {
  test('has its facts in one column, and a log line and a failure that stay in their own containers', async () => {
    const { page, context } = await open(viewport, `/runs/${seeded.failed}`);
    await page.locator('section.failure').waitFor();

    const columns = await page.locator('section dl.facts dt').evaluateAll((els) => new Set(els.map((el) => el.getBoundingClientRect().x)).size);
    const facts = await page.locator('dl.facts').first().evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
    expect(columns).toBe(1);
    expect(facts).toBe(1);
    // The message is a word of 400 letters: it is wrapped inside its card, in full.
    const message = page.locator('section.failure dd').filter({ hasText: LONG });
    const rect = (await message.boundingBox())!;
    expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width);
    expect(await message.innerText()).toContain(LONG);
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });

  test('keeps a long line of the log in the panel, which holds the whole of it', async () => {
    const { page, context } = await open(viewport, `/runs/${seeded.logged}`);
    const line = page.locator('.panel .line').filter({ hasText: LONG }).first();
    await line.waitFor();

    const panel = (await page.locator('.panel').boundingBox())!;
    expect(panel.x + panel.width).toBeLessThanOrEqual(viewport.width);
    const text = line.locator('.text');
    const rect = (await text.boundingBox())!;
    expect(rect.x + rect.width).toBeLessThanOrEqual(panel.x + panel.width + 1);
    expect(await text.innerText()).toContain(LONG);
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });

  test('can be cancelled with a tap, and the log goes on', async () => {
    const { page, context } = await open(viewport, `/runs/${seeded.running}`);
    const cancel = page.locator('button.cancel');
    await cancel.waitFor();

    const rect = (await cancel.boundingBox())!;
    expect(rect.x).toBeGreaterThanOrEqual(0);
    expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width);
    expect(rect.height).toBeGreaterThanOrEqual(44);
    await cancel.click();
    await page.locator('.confirm').waitFor();
    for (const button of [page.locator('button.confirm-cancel'), page.locator('button.keep')]) {
      const r = (await button.boundingBox())!;
      expect(r.x + r.width).toBeLessThanOrEqual(viewport.width);
      expect(r.height).toBeGreaterThanOrEqual(44);
    }
    await page.locator('button.keep').click();
    await page.locator('.panel .line').first().waitFor();
    await context.close();
  });
});

cardsAt('a pipeline and its views, below 768px,', (_name, viewport) => {
  test('hold the first column of the table of parameters while it scrolls inside its container', async () => {
    const { page, context } = await open(viewport, pipelinePath(seeded.slowHash, 'demo-slow'));
    const table = page.locator('main section table').first();
    await table.waitFor();

    const facts = await page.locator('dl.facts').first().evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
    expect(facts).toBe(1);
    const held = await table.locator('tbody tr').first().locator('td, th').first().evaluate((el) => getComputedStyle(el).position);
    expect(held).toBe('sticky');
    const container = await table.evaluate((el) => {
      let node: HTMLElement | null = el.parentElement;
      while (node && getComputedStyle(node).overflowX === 'visible') node = node.parentElement;
      return node === null ? null : node.tagName === 'MAIN' || node.tagName === 'BODY' ? 'page' : 'own';
    });
    expect(container).toBe('own');
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });

  test('show the table of a view in a container of its own, the first column held, the form in one column', async () => {
    const { page, context } = await open(viewport, pipelinePath(seeded.viewsHash, 'demo-views'));
    await page.locator('li.view[data-view="articles"] button.open').click();
    const form = page.locator('form.view-params');
    await form.waitFor();
    await page.locator('.rl-table-wrap table tbody tr').first().waitFor();

    const xs = await form.locator('.rl-field').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().x));
    expect(xs.length).toBeGreaterThan(1);
    expect(new Set(xs).size).toBe(1);
    for (const field of await form.locator('input, select').all()) {
      const rect = (await field.boundingBox())!;
      expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width);
    }
    const held = await page.locator('.rl-table-wrap table tbody tr').first().locator('td, th').first().evaluate((el) => getComputedStyle(el).position);
    expect(held).toBe('sticky');
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });
});

cardsAt('what is touched on these pages, below 768px,', (_name, viewport) => {
  /** Whether a point 20px from the centre of the element, up and down, still lands on it. */
  const reachable = (page: Page, selector: string) =>
    page.evaluate((sel) => {
      const el = document.querySelector<HTMLElement>(sel)!;
      el.scrollIntoView({ block: 'center' });
      const rect = el.getBoundingClientRect();
      const x = rect.x + rect.width / 2;
      const y = rect.y + rect.height / 2;
      const owns = (px: number, py: number) =>
        document.elementsFromPoint(px, py).some((hit) => hit === el || el.contains(hit) || (el as HTMLInputElement).labels?.[0]?.contains(hit));
      return [owns(x, y - 20), owns(x, y + 20)];
    }, selector);

  test('a link in a sentence and a checkbox can be hit within 44px', async () => {
    const views = await open(viewport, pipelinePath(seeded.viewsHash, 'demo-views'));
    await views.page.locator('li.view[data-view="articles"]').waitFor();
    expect(await reachable(views.page, 'p.back a')).toEqual([true, true]);
    await views.context.close();

    const runs = await open(viewport, '/runs');
    await runs.page.locator('input.auto').waitFor();
    expect(await reachable(runs.page, 'input.auto')).toEqual([true, true]);
    await runs.context.close();

    const run = await open(viewport, `/runs/${seeded.logged}`);
    await run.page.locator('input.follow').waitFor();
    expect(await reachable(run.page, 'input.follow')).toEqual([true, true]);
    await run.context.close();
  });
});

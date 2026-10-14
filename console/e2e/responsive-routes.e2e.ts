// Every route of the Console on a phone (WI-64, ADR-048), in a real browser, against a real packaged
// Engine, as each role sees it. CASES names each route of `ROUTES` with the states it is verified in
// (with data, with nothing found, refused); the first test fails when a route is added and not named
// here, so that no page escapes the verification. For each case, at 360px, 390px and 768px: the page
// does not scroll sideways, the navigation opens and closes (below 768px; from 768px the sidebar is
// there), every link of the navigation can be pressed, and the main action of the page can be pressed.
// The page of users needs an Engine with a provider and is verified by oidc.e2e.ts (npm run e2e:oidc).

import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import type { Browser, Locator, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { ROUTES, navGroups, type RouteId } from '../src/app/routes';
import {
  NARROWEST_WIDE,
  PHONE_360,
  PHONE_390,
  horizontalOverflow,
  launchChrome,
  navigation,
  newContext,
  settled,
  signInWith,
  watch,
} from './browser';
import { trouble, type Viewport } from './phone';

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
const TRIGGER = `e2e.routes-trigger-${stamp}`;
const NOTHING = '0'.repeat(64);

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

const seeded = { slowHash: '', uploader: '', ended: '', running: '' };

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
  const uploaded = await api(ada, 'POST', '/artifacts', readFileSync(join(jars, 'demo-slow.jar')));
  expect(uploaded.status).toBeLessThan(300);
  seeded.slowHash = uploaded.body.contentHash;
  seeded.uploader = (await api(ada, 'GET', `/artifacts/${seeded.slowHash}`)).body.uploader;

  const ended = await api(ada, 'POST', '/runs', {
    contentHash: seeded.slowHash,
    pipeline: 'demo-slow',
    parameters: { steps: '2', delayMillis: '50' },
  });
  seeded.ended = ended.body.runId;
  for (let i = 0; i < 300; i++) {
    const run = await api(ada, 'GET', `/runs/${seeded.ended}`);
    if (TERMINAL.includes(run.body.state)) break;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  const running = await api(ada, 'POST', '/runs', {
    contentHash: seeded.slowHash,
    pipeline: 'demo-slow',
    parameters: { steps: '3600', delayMillis: '1000' },
  });
  seeded.running = running.body.runId;

  expect(
    (await api(root, 'POST', '/triggers', { name: TRIGGER, kind: 'webhook', contentHash: seeded.slowHash, pipeline: 'demo-slow', parameters: { steps: '1' } })).status,
  ).toBeLessThan(300);
}, 120_000);
afterAll(async () => {
  if (seeded.running) await api(ada, 'POST', `/runs/${seeded.running}/cancel`);
  await api(root, 'DELETE', `/triggers/${TRIGGER}`);
  await browser?.close();
});

type Role = 'developer' | 'admin';
const WHO: Record<Role, { token: string }> = { developer: ada, admin: root };

interface Case {
  route: RouteId;
  /** What the page shows: with its data, nothing found, or refused to the role. */
  state: string;
  path: () => string;
  /** Who sees it, and what a developer is shown instead where the route is for admins. */
  roles: Role[];
  /** On screen before the page is measured. */
  ready: (page: Page) => Locator;
  /** The main thing to do on the page; null where the page has nothing to press. */
  action: ((page: Page) => Locator) | null;
}

const BOTH: Role[] = ['developer', 'admin'];
const ADMIN: Role[] = ['admin'];
const pipelinePath = () => `/pipelines/${seeded.slowHash}?pipeline=demo-slow&uploader=${seeded.uploader}`;
const main = (page: Page, selector: string) => page.locator(`main ${selector}`).first();
const heading = (page: Page) => page.locator('main h1').first();
const notice = (page: Page) => page.locator('main .rl-notice').first();
const way = (page: Page) => page.locator('main p a').first();

const CASES: Case[] = [
  { route: 'overview', state: 'with runs', path: () => '/', roles: BOTH, ready: (p) => main(p, 'table tbody tr'), action: (p) => main(p, 'table tbody tr a') },
  { route: 'pipelines', state: 'with pipelines', path: () => '/pipelines', roles: BOTH, ready: (p) => main(p, 'table tbody tr'), action: (p) => main(p, 'table tbody tr a') },
  { route: 'runs', state: 'with runs', path: () => '/runs', roles: BOTH, ready: (p) => main(p, 'table tbody tr'), action: (p) => main(p, '.rl-btn.primary') },
  { route: 'upload', state: 'ready for a file', path: () => '/upload', roles: BOTH, ready: heading, action: (p) => main(p, 'label:has(input[type=file])') },
  { route: 'engine', state: 'with its facts', path: () => '/engine', roles: BOTH, ready: heading, action: null },
  { route: 'pipeline', state: 'with its version', path: pipelinePath, roles: BOTH, ready: (p) => main(p, 'table'), action: (p) => main(p, '.rl-btn.primary') },
  { route: 'pipeline', state: 'no such version', path: () => `/pipelines/${NOTHING}?pipeline=demo-slow&uploader=${ada.name}`, roles: BOTH, ready: notice, action: way },
  { route: 'runNew', state: 'with a pipeline chosen', path: () => `/runs/new?contentHash=${seeded.slowHash}&pipeline=demo-slow&uploader=${seeded.uploader}`, roles: BOTH, ready: (p) => main(p, 'form'), action: (p) => main(p, 'button.submit') },
  { route: 'run', state: 'a run that ended', path: () => `/runs/${seeded.ended}`, roles: BOTH, ready: (p) => p.locator('.panel .line').first(), action: (p) => main(p, 'input.follow') },
  { route: 'run', state: 'a run that goes on', path: () => `/runs/${seeded.running}`, roles: BOTH, ready: (p) => p.locator('button.cancel'), action: (p) => p.locator('button.cancel') },
  { route: 'run', state: 'no such run', path: () => '/runs/00000000-0000-0000-0000-000000000000', roles: BOTH, ready: notice, action: way },
  { route: 'triggers', state: 'with a trigger', path: () => '/triggers', roles: ADMIN, ready: (p) => main(p, 'table tbody tr'), action: (p) => main(p, '.rl-btn.primary') },
  { route: 'triggerNew', state: 'the form', path: () => `/triggers/new?contentHash=${seeded.slowHash}&pipeline=demo-slow`, roles: ADMIN, ready: (p) => p.locator('#trigger-name'), action: (p) => main(p, 'button.submit') },
  { route: 'triggerEdit', state: 'the form', path: () => `/triggers/edit?name=${TRIGGER}`, roles: ADMIN, ready: (p) => main(p, 'form'), action: (p) => main(p, 'button.submit') },
  { route: 'triggerEdit', state: 'no such trigger', path: () => '/triggers/edit?name=no.such.trigger', roles: ADMIN, ready: notice, action: way },
  { route: 'trigger', state: 'with its firings', path: () => `/triggers/detail?name=${TRIGGER}`, roles: ADMIN, ready: heading, action: (p) => main(p, '.rl-btn') },
  { route: 'trigger', state: 'no such trigger', path: () => '/triggers/detail?name=no.such.trigger', roles: ADMIN, ready: notice, action: way },
  { route: 'allowlist', state: 'with entries', path: () => '/allowlist', roles: ADMIN, ready: heading, action: (p) => main(p, '.rl-btn.primary:enabled') },
  { route: 'resources', state: 'with resources', path: () => '/resources', roles: ADMIN, ready: heading, action: (p) => main(p, '.rl-btn.primary') },
];

/** The pages that are for admins, as a developer meets them: refused in words, with the way home. */
const ADMIN_ROUTES = ROUTES.filter((route) => route.adminOnly && !route.needsOidc);
const refusedPath = (id: RouteId) => ({ triggerNew: '/triggers/new', triggerEdit: '/triggers/edit', trigger: '/triggers/detail' })[id as 'trigger'] ?? ROUTES.find((r) => r.id === id)!.path;

/** Routes verified by another script, with the reason. */
const ELSEWHERE: Partial<Record<RouteId, string>> = {
  users: 'oidc.e2e.ts: the page exists only on an Engine with login with a provider (npm run e2e:oidc)',
};

describe('the inventory of routes', () => {
  test('names every route of the Console, here or in the script that has what it needs', () => {
    const named = new Set<string>([...CASES.map((c) => c.route), ...Object.keys(ELSEWHERE)]);
    expect(ROUTES.map((route) => route.id).filter((id) => !named.has(id))).toEqual([]);
  });

  test('gives every route for admins a refusal as the developer meets it', () => {
    expect(ADMIN_ROUTES.map((route) => route.id).sort()).toEqual(['allowlist', 'resources', 'trigger', 'triggerEdit', 'triggerNew', 'triggers']);
  });
});

async function open(viewport: Viewport, path: string, role: Role | null, locale = 'en-US') {
  const context = await newContext(browser, locale, undefined, viewport);
  const page = await context.newPage();
  const problems = await watch(page, engineUrl!);
  await page.goto(`${engineUrl}${path}`);
  if (role !== null && (await settled(page)) === 'sign-in') await signInWith(page, WHO[role].token);
  return { page, problems, context };
}

/** What the page is checked for, whatever the page: the shared checks of WI-61 to WI-63. */
async function sweep(page: Page, viewport: Viewport, role: Role | null, action: Locator | null) {
  expect(await horizontalOverflow(page)).toBe(0);
  if (viewport.width < 768) expect(await trouble(page, 'main')).toEqual([]);
  if (action) {
    await action.scrollIntoViewIfNeeded();
    const rect = (await action.boundingBox())!;
    expect(rect.x).toBeGreaterThanOrEqual(0);
    expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width + 0.5);
    // Actionable: shown, still, enabled and not covered by anything else, which is what a tap needs.
    await action.click({ trial: true });
  }
  if (role === null) return;

  const links = navGroups(role).flatMap((group) => group.routes.map((route) => route.path));
  const nav = navigation(page);
  if (viewport.width >= 768) {
    expect(await nav.drawer.isVisible()).toBe(true);
    expect(await nav.menu.isVisible()).toBe(false);
  } else {
    expect(await nav.drawer.isVisible()).toBe(false);
    await nav.open();
    expect(await horizontalOverflow(page)).toBe(0);
  }
  expect(await nav.drawer.locator('nav a').evaluateAll((els) => els.map((el) => new URL((el as HTMLAnchorElement).href).pathname))).toEqual(links);
  // The last link is in reach whatever the height of the window, and the whole of the drawer is touchable.
  await nav.drawer.locator('nav a').last().click({ trial: true });
  if (viewport.width < 768) {
    expect(await trouble(page, 'aside')).toEqual([]);
    await page.keyboard.press('Escape');
    await nav.drawer.waitFor({ state: 'hidden' });
    expect(await nav.menu.getAttribute('aria-expanded')).toBe('false');
    // And a choice in the drawer leads there and closes it.
    await nav.open();
    await nav.drawer.locator('nav a').last().click();
    await nav.drawer.waitFor({ state: 'hidden' });
    expect(new URL(page.url()).pathname).toBe(links[links.length - 1]);
  }
}

describe.each([
  ['360px', PHONE_360],
  ['390px', PHONE_390],
  ['768px', NARROWEST_WIDE],
])('every route at %s', (_name, viewport) => {
  const rows = CASES.flatMap((c) => c.roles.map((role) => [c.route, c.state, role, c] as const));
  test.each(rows)('%s, %s, as %s', async (_route, _state, role, c) => {
    const { page, problems, context } = await open(viewport, c.path(), role);
    await c.ready(page).waitFor();
    await page.waitForTimeout(300);

    await sweep(page, viewport, role, c.action?.(page) ?? null);
    // A failure the page was asked to show is a 404 of the Engine, which the browser lists.
    if (!c.state.startsWith('no such')) expect(problems.console).toEqual([]);
    await context.close();
  });

  test.each(ADMIN_ROUTES.map((route) => [route.id] as const))('%s, refused, as developer', async (id) => {
    const { page, problems, context } = await open(viewport, refusedPath(id), 'developer');
    await page.locator('main h1', { hasText: 'Admins only' }).waitFor();
    expect(await page.locator('main table, main form').count()).toBe(0);

    await sweep(page, viewport, 'developer', main(page, 'a'));
    expect(problems.console).toEqual([]);
    await context.close();
  });

  test('a path that is no page, as developer and as admin', async () => {
    for (const role of BOTH) {
      const { page, problems, context } = await open(viewport, '/no/such/page', role);
      await heading(page).waitFor();
      await sweep(page, viewport, role, main(page, 'a'));
      expect(problems.console).toEqual([]);
      await context.close();
    }
  });

  test('the sign-in, before anyone is in', async () => {
    const { page, context } = await open(viewport, '/', null);
    await page.locator('form input').waitFor();
    await sweep(page, viewport, null, page.locator('form button[type="submit"]'));
    await context.close();
  });

  test('nothing found after a filter, in the runs', async () => {
    const { page, problems, context } = await open(viewport, '/runs', 'developer');
    await main(page, 'table tbody tr').waitFor();
    await page.selectOption('#runs-pipeline', { label: 'demo-slow' }).catch(() => undefined);
    const states = await page.locator('#runs-state option').evaluateAll((els) => els.map((el) => (el as HTMLOptionElement).value));
    // A state no run of the Engine is in: the first one of the list that leaves the table empty.
    let emptied = false;
    for (const state of states.filter((value) => value !== '')) {
      await page.selectOption('#runs-state', state);
      await page.waitForTimeout(400);
      if ((await main(page, 'table tbody tr').count()) === 0) {
        emptied = true;
        break;
      }
    }
    expect(emptied).toBe(true);
    await sweep(page, viewport, 'developer', page.locator('#runs-state'));
    expect(problems.console).toEqual([]);
    await context.close();
  });
});

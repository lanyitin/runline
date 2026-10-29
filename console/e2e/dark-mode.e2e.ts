// The dark mode of the Console (WI-71, ADR-70), in a real browser, against a real, running, packaged
// Engine: with the system preference emulated as dark, every route uses the dark tokens, no block of
// the page is left light, every text on the page reaches its contrast against the background it is
// on, and a change of the preference takes effect on the open page without a reload. CASES names
// each route with a state it is verified in; the first test fails when a route is added and not
// named here, so that no page escapes the verification.

import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import type { Browser, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { ROUTES, type RouteId } from '../src/app/routes';
import { launchChrome, settled, signInWith } from './browser';

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
const TRIGGER = `e2e.dark-trigger-${stamp}`;
const NOTHING = '0'.repeat(64);

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

const seeded = { hash: '', uploader: '', ended: '' };
const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT'];

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
  const uploaded = await api(ada, 'POST', '/artifacts', readFileSync(join(jars, 'demo-slow.jar')));
  expect(uploaded.status).toBeLessThan(300);
  seeded.hash = uploaded.body.contentHash;
  seeded.uploader = (await api(ada, 'GET', `/artifacts/${seeded.hash}`)).body.uploader;
  const ended = await api(ada, 'POST', '/runs', {
    contentHash: seeded.hash,
    pipeline: 'demo-slow',
    parameters: { steps: '2', delayMillis: '50' },
  });
  seeded.ended = ended.body.runId;
  for (let i = 0; i < 300; i++) {
    const run = await api(ada, 'GET', `/runs/${seeded.ended}`);
    if (TERMINAL.includes(run.body.state)) break;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  expect(
    (await api(root, 'POST', '/triggers', { name: TRIGGER, kind: 'webhook', contentHash: seeded.hash, pipeline: 'demo-slow', parameters: { steps: '1' } })).status,
  ).toBeLessThan(300);
}, 120_000);
afterAll(async () => {
  await api(root, 'DELETE', `/triggers/${TRIGGER}`);
  await browser?.close();
});

type Role = 'developer' | 'admin';
const WHO: Record<Role, { token: string }> = { developer: ada, admin: root };

interface Case {
  route: RouteId;
  state: string;
  path: () => string;
  role: Role;
  /** A selector on screen before the page is inspected. */
  ready: string;
}

const CASES: Case[] = [
  { route: 'overview', state: 'with runs', path: () => '/', role: 'developer', ready: 'main table tbody tr' },
  { route: 'pipelines', state: 'with pipelines', path: () => '/pipelines', role: 'developer', ready: 'main table tbody tr' },
  { route: 'runs', state: 'with runs', path: () => '/runs', role: 'developer', ready: 'main table tbody tr' },
  { route: 'upload', state: 'ready for a file', path: () => '/upload', role: 'developer', ready: 'main h1' },
  { route: 'engine', state: 'with its facts', path: () => '/engine', role: 'developer', ready: 'main h1' },
  { route: 'pipeline', state: 'with its version', path: () => `/pipelines/${seeded.hash}?pipeline=demo-slow&uploader=${seeded.uploader}`, role: 'developer', ready: 'main table' },
  { route: 'pipeline', state: 'no such version', path: () => `/pipelines/${NOTHING}?pipeline=demo-slow&uploader=${ada.name}`, role: 'developer', ready: 'main .rl-notice' },
  { route: 'runNew', state: 'with a pipeline chosen', path: () => `/runs/new?contentHash=${seeded.hash}&pipeline=demo-slow&uploader=${seeded.uploader}`, role: 'developer', ready: 'main form' },
  { route: 'run', state: 'a run that ended, with its log', path: () => `/runs/${seeded.ended}`, role: 'developer', ready: '.panel .line' },
  { route: 'run', state: 'no such run', path: () => '/runs/00000000-0000-0000-0000-000000000000', role: 'developer', ready: 'main .rl-notice' },
  { route: 'triggers', state: 'with a trigger', path: () => '/triggers', role: 'admin', ready: 'main table tbody tr' },
  { route: 'triggers', state: 'refused to a developer', path: () => '/triggers', role: 'developer', ready: 'main h1' },
  { route: 'triggerNew', state: 'the form', path: () => `/triggers/new?contentHash=${seeded.hash}&pipeline=demo-slow`, role: 'admin', ready: '#trigger-name' },
  { route: 'triggerEdit', state: 'the form', path: () => `/triggers/edit?name=${TRIGGER}`, role: 'admin', ready: 'main form' },
  { route: 'trigger', state: 'with its firings', path: () => `/triggers/detail?name=${TRIGGER}`, role: 'admin', ready: 'main h1' },
  { route: 'allowlist', state: 'with entries', path: () => '/allowlist', role: 'admin', ready: 'main h1' },
  { route: 'resources', state: 'with resources', path: () => '/resources', role: 'admin', ready: 'main h1' },
];

/** Routes verified by another script, with the reason. */
const ELSEWHERE: Partial<Record<RouteId, string>> = {
  users: 'oidc.e2e.ts: the page exists only on an Engine with login with a provider (npm run e2e:oidc)',
};

describe('the inventory of routes', () => {
  test('names every route of the Console, here or in the script that has what it needs', () => {
    const named = new Set<string>([...CASES.map((c) => c.route), ...Object.keys(ELSEWHERE)]);
    expect(ROUTES.map((route) => route.id).filter((id) => !named.has(id))).toEqual([]);
  });
});

type Scheme = 'dark' | 'light';

async function open(scheme: Scheme, path: string, role: Role | null) {
  const context = await browser.newContext({ locale: 'en-US', viewport: { width: 1440, height: 900 }, colorScheme: scheme });
  const page = await context.newPage();
  await page.goto(`${engineUrl}${path}`);
  if (role !== null && (await settled(page)) === 'sign-in') await signInWith(page, WHO[role].token);
  return { page, context };
}

/** The value of a token as the page resolves it, as `rgb(r, g, b)`. */
const resolved = (page: Page, token: string) =>
  page.evaluate((name) => {
    const probe = document.createElement('i');
    probe.style.color = `var(${name})`;
    document.body.append(probe);
    const value = getComputedStyle(probe).color;
    probe.remove();
    return value;
  }, token);

/**
 * What is wrong with the colours on the page, as words: a block with a light opaque background, and a
 * text that does not reach its contrast (4.5:1; 3:1 for large text) against the background it is
 * on, which is the backgrounds above it laid over each other. Empty when all is well.
 */
const colourTrouble = (page: Page) =>
  page.evaluate(() => {
    type Rgba = [number, number, number, number];
    const parse = (value: string): Rgba => {
      const m = /^rgba?\(([^)]+)\)$/.exec(value);
      if (!m) throw new Error(`a colour the check cannot read: ${value}`);
      const parts = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
      return [parts[0], parts[1], parts[2], parts[3] ?? 1];
    };
    const over = (top: Rgba, below: Rgba): Rgba => {
      const a = top[3] + below[3] * (1 - top[3]);
      if (a === 0) return [0, 0, 0, 0];
      const mix = (i: number) => (top[i] * top[3] + below[i] * below[3] * (1 - top[3])) / a;
      return [mix(0), mix(1), mix(2), a];
    };
    const lum = (c: Rgba) => {
      const f = (v: number) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      };
      return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
    };
    const ratio = (a: Rgba, b: Rgba) => {
      const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
      return (x + 0.05) / (y + 0.05);
    };
    const canvas = parse(getComputedStyle(document.body).backgroundColor);
    const backdrop = (el: Element): Rgba => {
      const layers: Rgba[] = [];
      for (let node: Element | null = el; node; node = node.parentElement) {
        layers.push(parse(getComputedStyle(node).backgroundColor));
      }
      return layers.reduceRight((below, layer) => over(layer, below), canvas.concat() as Rgba);
    };
    const name = (el: Element) =>
      `${el.tagName.toLowerCase()}${el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : ''}`;
    const found: string[] = [];
    for (const el of document.body.querySelectorAll('*')) {
      const style = getComputedStyle(el);
      const rect = el.getBoundingClientRect();
      if (rect.width <= 1 || rect.height <= 1 || style.visibility === 'hidden' || style.display === 'none') continue;
      const bg = parse(style.backgroundColor);
      // A light block is a pale, unsaturated one; the saturated lights (the accent of a button, the
      // green of a status dot) are tokens of the dark theme as well.
      const pale = Math.max(bg[0], bg[1], bg[2]) - Math.min(bg[0], bg[1], bg[2]) < 60;
      if (bg[3] >= 0.99 && pale && lum(bg) > 0.4) found.push(`${name(el)} has a light background ${style.backgroundColor}`);
      // Decorative text (a separator) is hidden from assistive technology and is not read.
      if (el.closest('[aria-hidden="true"]')) continue;
      const ownText = [...el.childNodes].some((n) => n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim() !== '');
      if (!ownText) continue;
      // A disabled control, or one still fading in, is not held to the contrast of text.
      let faded = false;
      for (let node: Element | null = el; node; node = node.parentElement) {
        if (getComputedStyle(node).opacity !== '1' || node.matches(':disabled, [aria-disabled="true"]')) faded = true;
      }
      if (faded) continue;
      const size = parseFloat(style.fontSize);
      const bold = Number(style.fontWeight) >= 700;
      const needed = size >= 24 || (size >= 18.66 && bold) ? 3 : 4.5;
      const back = backdrop(el);
      const fore = over(parse(style.color), back);
      const got = ratio(fore, back);
      if (got < needed) found.push(`${name(el)} "${(el.textContent ?? '').trim().slice(0, 24)}" is ${got.toFixed(2)}:1, needs ${needed}:1`);
    }
    return found;
  });

describe('every route with the system preference dark', () => {
  test.each(CASES.map((c) => [c.route, c.state, c.role, c] as const))('%s, %s, as %s', async (_route, _state, _role, c) => {
    const { page, context } = await open('dark', c.path(), c.role);
    await page.locator(c.ready).first().waitFor();
    await page.waitForTimeout(400);

    // The page uses the dark tokens: the body is the dark background, the text the dark text.
    const body = await page.evaluate(() => {
      const style = getComputedStyle(document.body);
      return { background: style.backgroundColor, color: style.color, scheme: getComputedStyle(document.documentElement).colorScheme };
    });
    expect(body.background).toBe(await resolved(page, '--bg'));
    expect(body.color).toBe(await resolved(page, '--text'));
    expect(body.scheme).toBe('light dark');
    expect(await page.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches)).toBe(true);
    expect(await colourTrouble(page)).toEqual([]);
    await context.close();
  });

  test('the sign-in, before anyone is in', async () => {
    const { page, context } = await open('dark', '/', null);
    await page.locator('form input').waitFor();
    expect(await colourTrouble(page)).toEqual([]);
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe(await resolved(page, '--bg'));
    await context.close();
  });

  test('a path that is no page', async () => {
    const { page, context } = await open('dark', '/no/such/page', 'developer');
    await page.locator('main h1').waitFor();
    expect(await colourTrouble(page)).toEqual([]);
    await context.close();
  });

  test('the log panel is dark and apart from the surface around it', async () => {
    const { page, context } = await open('dark', `/runs/${seeded.ended}`, 'developer');
    await page.locator('.panel .line').first().waitFor();
    const panel = await page.locator('.panel').evaluate((el) => {
      const style = getComputedStyle(el);
      return { background: style.backgroundColor, shadow: style.boxShadow };
    });
    expect(panel.background).toBe(await resolved(page, '--terminal-bg'));
    expect(panel.shadow).toContain(await resolved(page, '--terminal-border'));
    await context.close();
  });
});

describe('the system preference light', () => {
  test('is the Console as it was: the light tokens, and the log panel dark', async () => {
    const { page, context } = await open('light', `/runs/${seeded.ended}`, 'developer');
    await page.locator('.panel .line').first().waitFor();
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(247, 249, 252)');
    expect(await page.evaluate(() => getComputedStyle(document.body).color)).toBe('rgb(15, 23, 42)');
    expect(await page.locator('.panel').evaluate((el) => getComputedStyle(el).backgroundColor)).toBe('rgb(11, 18, 32)');
    expect(await page.locator('main .rl-card').first().evaluate((el) => getComputedStyle(el).backgroundColor)).toBe('rgb(255, 255, 255)');
    await context.close();
  });
});

describe('a change of the system preference', () => {
  test('takes effect on the open page, in both directions, without a reload', async () => {
    const { page, context } = await open('light', `/runs/${seeded.ended}`, 'developer');
    await page.locator('.panel .line').first().waitFor();
    await page.evaluate(() => ((window as unknown as { __same: boolean }).__same = true));
    const background = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    const lightBackground = await background();
    expect(lightBackground).toBe('rgb(247, 249, 252)');

    // The buttons and links fade between the colours (--motion): measured when they have settled.
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.waitForTimeout(400);
    expect(await background()).toBe(await resolved(page, '--bg'));
    expect(await background()).not.toBe(lightBackground);
    expect(await colourTrouble(page)).toEqual([]);

    await page.emulateMedia({ colorScheme: 'light' });
    expect(await background()).toBe(lightBackground);
    // The same document throughout: nothing was reloaded.
    expect(await page.evaluate(() => (window as unknown as { __same?: boolean }).__same)).toBe(true);
    await context.close();
  });
});

describe('the icon of the tab (WI-81)', () => {
  // Chrome gives a page no computed colour of its tab icon, so this is a substitute: the file that
  // the page's <link rel="icon"> names is loaded as an image (the way an icon is drawn; opened as a
  // document, the Engine's style-src would block the icon's own style) under the emulated preference,
  // and the pixels of the tile and of the symbol are read from a canvas. It does not look at the icon
  // on the tab itself.
  const iconColours = async (scheme: Scheme) => {
    const { page, context } = await open(scheme, '/', null);
    await page.locator('form input').waitFor();
    const href = await page.locator('link[rel="icon"]').getAttribute('href');
    expect(href).not.toBeNull();
    const accent = await resolved(page, '--accent-solid');
    const onAccent = await resolved(page, '--on-accent');
    const colours = await page.evaluate(async (src) => {
      const image = new Image();
      image.src = src;
      await image.decode();
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 32;
      const draw = canvas.getContext('2d')!;
      draw.drawImage(image, 0, 0, 32, 32);
      const at = (x: number, y: number) => {
        const [r, g, b, a] = draw.getImageData(x, y, 1, 1).data;
        return a === 255 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${a})`;
      };
      // The tile away from the symbol; the middle of the dash of the symbol.
      return { tile: at(16, 4), mark: at(20, 21) };
    }, new URL(href!, page.url()).toString());
    await context.close();
    return { colours, accent, onAccent };
  };

  test.each(['light', 'dark'] as const)('has the colours of the buttons with the system preference %s', async (scheme) => {
    const { colours, accent, onAccent } = await iconColours(scheme);
    expect(colours).toEqual({ tile: accent, mark: onAccent });
  });

  test('differs between the two preferences', async () => {
    expect((await iconColours('dark')).colours).not.toEqual((await iconColours('light')).colours);
  });
});

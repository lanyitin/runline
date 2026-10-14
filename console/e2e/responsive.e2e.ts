// The foundation of the narrow layout (WI-61, ADR-048) in a real browser, against a real packaged
// Engine: the one breakpoint, the drawer of the navigation, the top bar, the size of what is touched,
// and the shared checks (no sideways scroll; the navigation opens and closes) on the first page after
// sign-in and on a page with a table. The other routes are verified the same way by WI-64.

import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import type { Browser, BrowserContext, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import {
  DESKTOP,
  NARROWEST_WIDE,
  PHONE_360,
  PHONE_390,
  WIDEST_NARROW,
  horizontalOverflow,
  launchChrome,
  navigation,
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
const ada = parseCallers(tokens).find((c) => c.role === 'developer')!;

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
  // A version in the list of pipelines, so that its page has a table.
  const response = await fetch(`${engineUrl}/api/v1/artifacts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${ada.token}`, 'Content-Type': 'application/octet-stream' },
    body: readFileSync(join(jars, 'demo-slow.jar')),
  });
  expect(response.status).toBeLessThan(300);
});
afterAll(async () => {
  await browser?.close();
});

async function signedIn(context: BrowserContext, path = '/') {
  const page = await context.newPage();
  const problems = await watch(page, engineUrl!);
  await page.goto(`${engineUrl}${path}`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, ada.token);
  await page.locator('aside nav').waitFor({ state: 'attached' });
  return { page, problems };
}

const box = async (locator: ReturnType<Page['locator']>) => (await locator.boundingBox())!;

describe.each([
  ['360px', PHONE_360],
  ['390px', PHONE_390],
  ['767px', WIDEST_NARROW],
])('the narrow layout at %s', (_name, viewport) => {
  test.each([
    ['the first page after sign-in', '/'],
    ['a page with a table', '/pipelines'],
  ])('%s does not scroll sideways', async (_page, path) => {
    const context = await newContext(browser, 'en-US', undefined, viewport);
    const { page, problems } = await signedIn(context, path);
    await page.locator('main h1').waitFor();
    if (path === '/pipelines') await page.locator('main table').waitFor();

    expect(await horizontalOverflow(page)).toBe(0);
    expect(problems.console).toEqual([]);
    await context.close();
  });

  test('has no sidebar but a menu button, and the language and sign-out stay in reach', async () => {
    const context = await newContext(browser, 'zh-TW', undefined, viewport);
    const { page } = await signedIn(context);
    const nav = navigation(page);

    expect(await nav.drawer.isVisible()).toBe(false);
    expect(await nav.menu.isVisible()).toBe(true);
    for (const control of [nav.menu, page.locator('header .switcher button').first(), page.locator('header .sign-out')]) {
      const rect = await box(control);
      expect(rect.x).toBeGreaterThanOrEqual(0);
      expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width);
      expect(rect.height).toBeGreaterThanOrEqual(44);
    }
    await context.close();
  });
});

describe('the drawer of the navigation, on a phone', () => {
  test('opens and closes: the keyboard goes in, Escape, the scrim and a choice close it', async () => {
    const context = await newContext(browser, 'en-US', undefined, PHONE_390);
    const { page } = await signedIn(context);
    const nav = navigation(page);
    expect(await nav.menu.getAttribute('aria-label')).toBe('Menu');
    expect(await nav.menu.getAttribute('aria-expanded')).toBe('false');

    await nav.open();
    expect(await nav.drawer.isVisible()).toBe(true);
    expect(await nav.menu.getAttribute('aria-expanded')).toBe('true');
    expect(await nav.keyboardInside()).toBe(true);
    expect(await horizontalOverflow(page)).toBe(0);

    await page.keyboard.press('Escape');
    await nav.drawer.waitFor({ state: 'hidden' });
    expect(await nav.keyboardOnMenu()).toBe(true);

    await nav.open();
    await page.mouse.click(PHONE_390.width - 5, 300);
    await nav.drawer.waitFor({ state: 'hidden' });
    expect(await nav.keyboardOnMenu()).toBe(true);

    await nav.open();
    await nav.drawer.locator('a[href="/runs"]').click();
    await nav.drawer.waitFor({ state: 'hidden' });
    expect(new URL(page.url()).pathname).toBe('/runs');
    expect(await nav.menu.getAttribute('aria-expanded')).toBe('false');
    await context.close();
  });

  test('keeps the keyboard inside while open, and out of the links of the drawer while closed', async () => {
    const context = await newContext(browser, 'en-US', undefined, PHONE_390);
    const { page } = await signedIn(context);
    const nav = navigation(page);

    // Closed: tabbing through the whole page never lands in the navigation.
    await nav.menu.focus();
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press('Tab');
      expect(await nav.keyboardInside()).toBe(false);
    }

    await nav.open();
    for (let i = 0; i < 30; i++) {
      await page.keyboard.press('Tab');
      expect(await nav.keyboardInside()).toBe(true);
    }
    for (let i = 0; i < 30; i++) {
      await page.keyboard.press('Shift+Tab');
      expect(await nav.keyboardInside()).toBe(true);
    }
    await context.close();
  });

  test('is named in Chinese too', async () => {
    const context = await newContext(browser, 'zh-TW', undefined, PHONE_390);
    const { page } = await signedIn(context);
    expect(await navigation(page).menu.getAttribute('aria-label')).toBe('選單');
    await context.close();
  });
});

describe('what is touched, on a phone', () => {
  test('buttons and links of the chrome are 44px high at least, the fields have 16px type', async () => {
    const context = await newContext(browser, 'en-US', undefined, PHONE_390);
    const { page } = await signedIn(context, '/upload');
    const nav = navigation(page);
    await nav.open();

    const heights = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('aside a.item, header button, main button, main input:not([type=file])')]
        .filter((el) => el.getBoundingClientRect().width > 0)
        .map((el) => ({ what: el.outerHTML.slice(0, 60), height: el.getBoundingClientRect().height })),
    );
    expect(heights.length).toBeGreaterThan(5);
    expect(heights.filter((h) => h.height < 44)).toEqual([]);

    await page.keyboard.press('Escape');
    await page.goto(`${engineUrl}/runs/new`);
    await page.locator('main select, main input:not([type=file]), main textarea').first().waitFor();
    const sizes = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('main input:not([type=file]):not([type=checkbox]):not([type=radio]), main select, main textarea')].map(
        (el) => parseFloat(getComputedStyle(el).fontSize),
      ),
    );
    expect(sizes.length).toBeGreaterThan(0);
    expect(sizes.filter((size) => size < 16)).toEqual([]);
    await context.close();
  });
});

describe('wide content, on a phone', () => {
  test('scrolls or wraps inside its own container, with the shared classes, and not the page', async () => {
    const context = await newContext(browser, 'en-US', undefined, PHONE_360);
    const { page } = await signedIn(context);
    await page.locator('main h1').waitFor();

    const result = await page.evaluate(() => {
      const main = document.querySelector('main')!;
      const scroller = document.createElement('div');
      scroller.className = 'rl-scroll-x';
      // Through the DOM, not markup: the Engine's content security policy has no inline styles.
      const wide = document.createElement('div');
      wide.style.width = '2000px';
      wide.style.height = '10px';
      scroller.append(wide);
      const breaker = document.createElement('p');
      breaker.className = 'rl-break';
      breaker.textContent = 'a'.repeat(400);
      main.append(scroller, breaker);
      return {
        scrollerScrolls: scroller.scrollWidth > scroller.clientWidth,
        breakerFits: breaker.scrollWidth <= breaker.clientWidth,
      };
    });

    expect(result).toEqual({ scrollerScrolls: true, breakerFits: true });
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });
});

describe('the wide layout', () => {
  test.each([
    ['1440px', DESKTOP],
    ['768px', NARROWEST_WIDE],
  ])('at %s keeps its sidebar, with no menu button and no scrim', async (_name, viewport) => {
    const context = await newContext(browser, 'en-US', undefined, viewport);
    const { page } = await signedIn(context, '/pipelines');
    const nav = navigation(page);

    expect(await nav.drawer.isVisible()).toBe(true);
    expect(await nav.menu.isVisible()).toBe(false);
    expect(await page.locator('.rl-nav-scrim').count()).toBe(0);
    expect((await box(nav.drawer)).width).toBe(240);
    expect(await horizontalOverflow(page)).toBe(0);
    await context.close();
  });
});

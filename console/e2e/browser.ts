import { chromium, type Browser, type BrowserContext, type Page } from 'playwright-core';

/** Chrome of this machine (no browser is downloaded by the build); CHROME_PATH names another. */
const CHROME =
  process.env.CHROME_PATH ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

export const launchChrome = (): Promise<Browser> =>
  chromium.launch({ executablePath: CHROME, headless: true });

/** What a page reports about itself that must stay empty: CSP violations and console errors. */
export interface Problems {
  csp: string[];
  console: string[];
  /** Requests to anywhere but the origin of the page. */
  foreign: string[];
}

export async function watch(page: Page, origin: string): Promise<Problems> {
  const problems: Problems = { csp: [], console: [], foreign: [] };
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (event) => {
      (window as unknown as { __csp: string[] }).__csp ??= [];
      (window as unknown as { __csp: string[] }).__csp.push(
        `${event.violatedDirective} blocked ${event.blockedURI}`,
      );
    });
  });
  page.on('console', (message) => {
    if (message.type() === 'error') problems.console.push(message.text());
  });
  page.on('response', (response) => {
    if (response.status() >= 400) problems.console.push(`${response.status()} ${response.url()}`);
  });
  page.on('pageerror', (error) => problems.console.push(String(error)));
  page.on('request', (request) => {
    if (!request.url().startsWith(origin) && !request.url().startsWith('data:')) {
      problems.foreign.push(request.url());
    }
  });
  return problems;
}

/** Everything the page writes to its console, whatever the level: for what must never be in it. */
export function consoleLog(page: Page): string[] {
  const lines: string[] = [];
  page.on('console', (message) => lines.push(message.text()));
  page.on('pageerror', (error) => lines.push(String(error)));
  return lines;
}

export const violations = (page: Page): Promise<string[]> =>
  page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? []);

/** The window of a desktop, which every script has, and of the phones the narrow layout is verified at (ADR-048). */
export const DESKTOP = { width: 1440, height: 900 };
export const PHONE_360 = { width: 360, height: 740 };
export const PHONE_390 = { width: 390, height: 844 };
/** The widest window that is still the narrow layout, and the narrowest of the wide one. */
export const NARROWEST_WIDE = { width: 768, height: 1024 };
export const WIDEST_NARROW = { width: 767, height: 1024 };

export async function newContext(
  browser: Browser,
  locale: string,
  languages?: string[],
  viewport: { width: number; height: number } = DESKTOP,
): Promise<BrowserContext> {
  const context = await browser.newContext({ locale, viewport });
  if (languages) {
    await context.addInitScript((list) => {
      Object.defineProperty(navigator, 'languages', { get: () => list });
    }, languages);
  }
  return context;
}

/** Types [token] into the sign-in of the token method and sends it. */
export async function signInWith(page: Page, token: string): Promise<void> {
  await page.locator('form input').fill(token);
  await page.locator('form button[type="submit"]').click();
}

/** The tab of the Console that has opened, signed in or at the sign-in: waits for either. */
export async function settled(page: Page): Promise<'signed-in' | 'sign-in'> {
  const shell = page.locator('aside nav');
  const form = page.locator('form input');
  // Attached, not visible: on a narrow screen the navigation is a drawer that is closed.
  await shell.or(form).first().waitFor({ state: 'attached', timeout: 10_000 });
  return (await shell.count()) > 0 ? 'signed-in' : 'sign-in';
}

/** What the tab keeps of its session: the content of its sessionStorage, as a record. */
export const sessionContent = (page: Page): Promise<Record<string, string>> =>
  page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage)));

/**
 * How far the page reaches past the right edge of the window, in pixels: 0 when it does not scroll
 * sideways. The shared check of "no horizontal overflow" (WI-61): every route, at every phone size.
 */
export const horizontalOverflow = (page: Page): Promise<number> =>
  page.evaluate(() => {
    const root = document.documentElement;
    return Math.max(root.scrollWidth, document.body.scrollWidth) - root.clientWidth;
  });

/** The navigation on a narrow screen: the menu button of the top bar and the drawer it opens. */
export function navigation(page: Page) {
  const menu = page.locator('header button.menu');
  const drawer = page.locator('aside');
  return {
    menu,
    drawer,
    open: async () => {
      await menu.click();
      await page.locator('aside[data-open="true"]').waitFor();
    },
    isOpen: async () => (await menu.getAttribute('aria-expanded')) === 'true',
    /** Whether the keyboard is inside the drawer. */
    keyboardInside: () => page.evaluate(() => !!document.activeElement?.closest('aside')),
    /** Whether the keyboard is on the menu button. */
    keyboardOnMenu: () => page.evaluate(() => document.activeElement?.matches('header button.menu') ?? false),
  };
}

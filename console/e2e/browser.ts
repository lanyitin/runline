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

export async function newContext(
  browser: Browser,
  locale: string,
  languages?: string[],
): Promise<BrowserContext> {
  const context = await browser.newContext({ locale, viewport: { width: 1440, height: 900 } });
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
  await shell.or(form).first().waitFor({ timeout: 10_000 });
  return (await shell.count()) > 0 ? 'signed-in' : 'sign-in';
}

/** What the tab keeps of its session: the content of its sessionStorage, as a record. */
export const sessionContent = (page: Page): Promise<Record<string, string>> =>
  page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage)));

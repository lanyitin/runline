// The Console in a real browser (Chrome), against a real packaged Engine: the production build as
// the Engine serves it, with the Engine's content security policy in force. See the Console section of the README.

import { mkdirSync } from 'node:fs';
import type { Browser } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { launchChrome, newContext, violations, watch } from './browser';
import { startPreviewServer, type PreviewServer } from './preview-server';

const engineUrl = process.env.E2E_ENGINE_URL;
if (!engineUrl) throw new Error('E2E_ENGINE_URL is not set: the tests need a running Engine');
const screenshots = process.env.E2E_SCREENSHOTS;
if (screenshots) mkdirSync(screenshots, { recursive: true });

let browser: Browser;
let preview: PreviewServer;
let info: { version: string; commitHash: string };

beforeAll(async () => {
  browser = await launchChrome();
  preview = await startPreviewServer(engineUrl);
  info = await (await fetch(`${engineUrl}/api/v1/info`)).json();
});
afterAll(async () => {
  await browser?.close();
  await preview?.stop();
});

const shortHash = () => info.commitHash.slice(0, 7);

describe('before sign-in, on the Engine itself', () => {
  test('shows the Engine version and commit hash, with no policy violation and nothing foreign', async () => {
    const context = await newContext(browser, 'zh-TW');
    const page = await context.newPage();
    const problems = await watch(page, engineUrl);

    await page.goto(`${engineUrl}/`);
    await page.getByText(`v${info.version}`).first().waitFor();

    expect(await page.locator('footer').innerText()).toContain(shortHash());
    expect(await violations(page)).toEqual([]);
    expect(problems).toEqual({ csp: [], console: [], foreign: [] });
    if (screenshots) await page.screenshot({ path: `${screenshots}/pre-login-zh-TW.png` });
    await context.close();
  });

  test('the fonts that come with the Console are loaded from the Engine', async () => {
    const context = await newContext(browser, 'en-US');
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await page.getByText(`v${info.version}`).first().waitFor();

    const loaded = await page.evaluate(async () => {
      await document.fonts.ready;
      return [...document.fonts].filter((f) => f.status === 'loaded').map((f) => f.family);
    });
    expect(loaded.some((family) => family.includes('Inter'))).toBe(true);
    expect(loaded.some((family) => family.includes('JetBrains Mono'))).toBe(true);
    await context.close();
  });

  test.each([
    [['zh-TW'], 'zh-TW'],
    [['zh-HK'], 'zh-TW'],
    [['en-US'], 'en'],
    [['en-GB', 'zh-TW'], 'en'],
    [['ja', 'fr'], 'zh-TW'],
  ])('a browser that prefers %j gets %s', async (languages, expected) => {
    const context = await newContext(browser, languages[0], languages);
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    expect(await page.evaluate(() => document.documentElement.lang)).toBe(expected);
    await context.close();
  });

  test('switching the language takes effect without a reload, is kept, and is shared by the tabs', async () => {
    const context = await newContext(browser, 'zh-TW');
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await page.getByText('登入功能尚未提供').waitFor();
    await page.evaluate(() => ((window as unknown as { __mark: number }).__mark = 42));

    await page.getByRole('button', { name: 'English' }).click();

    await page.getByText('Signing in is not available yet.').waitFor();
    expect(await page.evaluate(() => document.documentElement.lang)).toBe('en');
    expect(await page.evaluate(() => (window as unknown as { __mark: number }).__mark)).toBe(42);
    expect(await page.evaluate(() => localStorage.getItem('runline.locale'))).toBe('en');
    if (screenshots) await page.screenshot({ path: `${screenshots}/pre-login-en.png` });

    const other = await context.newPage();
    await other.goto(`${engineUrl}/`);
    await other.getByText('Signing in is not available yet.').waitFor();

    await page.getByRole('button', { name: '繁體中文' }).click();
    await other.getByText('登入功能尚未提供').waitFor(); // the other tab follows, without a reload
    await context.close();
  });

  test('the keyboard reaches the skip link, the language switch and the Engine details', async () => {
    const context = await newContext(browser, 'en-US');
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await page.getByText(`v${info.version}`).first().waitFor();

    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => document.activeElement?.textContent)).toContain('Skip to content');

    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter'); // the English button, pressed with the keyboard
    expect(await page.evaluate(() => document.documentElement.lang)).toBe('en');

    await page.getByRole('button', { name: new RegExp(`v${info.version}`) }).focus();
    await page.keyboard.press('Enter');
    const details = page.getByRole('dialog');
    await details.waitFor();
    expect(await details.innerText()).toContain(info.commitHash);

    await page.keyboard.press('Escape');
    await details.waitFor({ state: 'detached' });
    expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-expanded'))).toBe('false');
    await context.close();
  });

  test('copying the commit hash puts it on the clipboard', async () => {
    const context = await newContext(browser, 'en-US');
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: engineUrl });
    const page = await context.newPage();
    await page.goto(`${engineUrl}/`);
    await page.getByRole('button', { name: new RegExp(`v${info.version}`) }).click();
    await page.getByRole('button', { name: 'Copy the commit hash' }).click();
    await page.getByText('Copied').waitFor();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(info.commitHash);
    await context.close();
  });
});

describe('the signed-in shell, built as production, behind the Engine headers', () => {
  const open = async (path: string, locale = 'en-US') => {
    const context = await newContext(browser, locale);
    const page = await context.newPage();
    const problems = await watch(page, preview.url);
    await page.goto(`${preview.url}${path}`);
    return { context, page, problems };
  };

  test('a developer sees the workspace, no admin page, and the Engine twice', async () => {
    const { context, page, problems } = await open('/runs?as=developer');
    await page.getByRole('heading', { name: 'Runs', level: 1 }).waitFor();
    await page.locator('aside').getByText(shortHash()).waitFor();

    const links = await page.locator('aside nav a').allInnerTexts();
    expect(links.map((l) => l.trim())).toEqual(['Overview', 'Pipelines', 'Runs', 'Upload']);
    await page.locator('header').getByText(shortHash()).waitFor();
    expect(await violations(page)).toEqual([]);
    expect(problems).toEqual({ csp: [], console: [], foreign: [] });
    if (screenshots) await page.screenshot({ path: `${screenshots}/shell-developer-en.png` });
    await context.close();
  });

  test('an admin has the admin pages marked, and a developer is turned away from them', async () => {
    const admin = await open('/allowlist?as=admin');
    await admin.page.getByRole('heading', { name: 'Allow-list', level: 1 }).waitFor();
    expect(await admin.page.locator('aside nav .admin-tag').count()).toBe(4);
    if (screenshots) await admin.page.screenshot({ path: `${screenshots}/shell-admin-en.png` });
    await admin.context.close();

    const developer = await open('/allowlist?as=developer');
    await developer.page.getByRole('heading', { name: 'Admins only', level: 1 }).waitFor();
    await developer.context.close();
  });

  test('the keyboard moves through the navigation, and a page change keeps the page alive', async () => {
    const { context, page } = await open('/?as=admin');
    await page.getByRole('heading', { name: 'Overview', level: 1 }).waitFor();
    await page.evaluate(() => ((window as unknown as { __mark: number }).__mark = 7));

    await page.getByRole('link', { name: 'Runs', exact: true }).focus();
    await page.keyboard.press('Enter');

    await page.getByRole('heading', { name: 'Runs', level: 1 }).waitFor();
    expect(new URL(page.url()).pathname).toBe('/runs');
    expect(await page.evaluate(() => (window as unknown as { __mark: number }).__mark)).toBe(7);
    expect(await page.evaluate(() => document.activeElement?.id)).toBe('main');

    await page.goBack();
    await page.getByRole('heading', { name: 'Overview', level: 1 }).waitFor();
    await context.close();
  });

  test('an unknown path is a clear not-found page, the Engine still in view', async () => {
    const { context, page } = await open('/no/such/page?as=developer');
    await page.getByRole('heading', { name: 'Page not found', level: 1 }).waitFor();
    await page.locator('aside').getByText(shortHash()).waitFor();
    await context.close();
  });

  test('the whole shell changes language at once, and the page lang follows', async () => {
    const { context, page } = await open('/pipelines?as=admin', 'en-US');
    await page.getByRole('heading', { name: 'Pipelines', level: 1 }).waitFor();

    await page.getByRole('button', { name: '繁體中文' }).click();

    await page.getByRole('heading', { name: 'Pipelines 定義集', level: 1 }).waitFor();
    expect(await page.locator('aside nav h2').allInnerTexts()).toEqual(['工作區', '自動化', '管理']);
    expect(await page.evaluate(() => document.documentElement.lang)).toBe('zh-TW');
    if (screenshots) await page.screenshot({ path: `${screenshots}/shell-admin-zh-TW.png` });
    await context.close();
  });
});

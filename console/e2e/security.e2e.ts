// The security check of the Console (WI-37, ADR-015, ADR-017): is what the design promises true in
// a real browser (Chrome), against a real packaged Engine with its PostgreSQL, and real compiled
// pipeline jars whose every string is hostile (dev/sample-pipelines, `adversarial*.jar`, named by
// E2E_JARS)? Three questions.
//
//  1. Untrusted content: names, parameters, log lines (control sequences too), failure message and
//     trace, strings in a jar, the Engine's own message that says a hostile name back. On every
//     screen it is text: no script runs, no markup is read, no request is made for it.
//  2. The policy: the Engine's security headers are there on the entry page and on the files, and
//     the browser really enforces them (an injected inline script, handler, third-party image,
//     script and request are blocked; a page of another origin cannot frame the Console).
//  3. The token: only in the sessionStorage of the tabs and in the Authorization header to the
//     Engine's own /api/v1; in no address, no history, no log, no localStorage, no cookie; gone from
//     every tab after a sign-out; the log is polled, never a WebSocket.
//
// Not part of `check`: it needs a browser and a running Engine. `dev/verify-console-security.sh`
// starts both and runs it; see the README. No assertion here is to be loosened to make it pass.

import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { join } from 'node:path';
import type { Browser, BrowserContext, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { launchChrome, newContext, settled, signInWith, violations, watch } from './browser';
import type { Problems } from './browser';

const engineUrl = process.env.E2E_ENGINE_URL;
if (!engineUrl) throw new Error('E2E_ENGINE_URL is not set: the tests need a running Engine');
const origin = new URL(engineUrl).origin;
const tokens = process.env.E2E_TOKENS;
if (!tokens) throw new Error('E2E_TOKENS is not set: name:role:token of a developer and an admin');
const callers = parseCallers(tokens);
const ada = callers.find((c) => c.role === 'developer')!;
const root = callers.find((c) => c.role === 'admin')!;
if (!ada || !root) throw new Error('E2E_TOKENS must have a developer and an admin');
const jars = process.env.E2E_JARS;
if (!jars) throw new Error('E2E_JARS is not set: the directory of the sample jars');
const jarBytes = (name: string) => readFileSync(join(jars, `${name}.jar`));
const stamp = () => Date.now().toString(36);

// What the adversarial jars carry (dev/sample-pipelines/src/main/kotlin/samples/adversarial/).
const PARAM_NAME = '<i id="pwn-param">p</i>';
const PARAM_DEFAULT = "<script>window.__xss='default'</script>";
const NETWORK_ENTRY = '<b id="pwn-host">evil.invalid</b>';
const RESOURCE_NAME = '<u id="pwn-resource" onclick="window.__xss=\'resource\'">r</u>';
const PIPELINE_NAME = '<img id="pwn-name" src="http://evil.invalid/name.png" onerror="window.__xss=\'name\'">';
const HELPER_CLASS = 'samples.adversarial.UnreadableHelper id="pwn-class" onerror=pwned(1)';
const LOG_LINES = [
  "<script>window.__xss='log'</script>",
  '<img id="pwn-log" src="http://evil.invalid/log.png" onerror="window.__xss=\'log-img\'">',
  '</div><div id="pwn-break">closed the line</div>',
  '\u001b[31mred? \u001b[1;5mblink\u001b[0m \u001b[2J\u001b[H cleared?',
  "javascript:window.__xss='url' http://evil.invalid/autolink <a href=\"http://evil.invalid/a\">a</a>",
  '<svg id="pwn-stderr" onload="window.__xss=\'stderr\'"></svg>',
];
const FAILURE_MESSAGE =
  '<img id="pwn-failure" src="http://evil.invalid/failure.png" onerror="window.__xss=\'failure\'">';
const TYPED = '<svg id="pwn-typed" onload="window.__xss=\'typed\'"></svg>';

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
});
afterAll(async () => {
  await browser?.close();
});

// ---- what a context saw ----------------------------------------------------------------------

interface Traffic {
  urls: string[];
  origins: Set<string>;
  /** The addresses of the requests that carried an Authorization header. */
  authorized: string[];
  websockets: string[];
  /** The requests that did not get an answer, with why. */
  failed: string[];
  /** The addresses that were answered. */
  responded: string[];
  settle(): Promise<void>;
}

/** Every request of every tab of [context], and the WebSockets they opened. */
function record(context: BrowserContext): Traffic {
  const pending: Promise<void>[] = [];
  const traffic: Traffic = {
    urls: [],
    origins: new Set(),
    authorized: [],
    websockets: [],
    failed: [],
    responded: [],
    // A request of a tab that was closed has no headers to read, and its promise may never end.
    settle: async () =>
      void (await Promise.race([Promise.all(pending), new Promise((resolve) => setTimeout(resolve, 3000))])),
  };
  const track = (page: Page) => page.on('websocket', (ws) => traffic.websockets.push(ws.url()));
  context.on('page', track);
  context.on('response', (response) => traffic.responded.push(response.url()));
  context.on('requestfailed', (request) =>
    traffic.failed.push(`${request.url()} ${request.failure()?.errorText}`),
  );
  context.on('request', (request) => {
    const url = request.url();
    const parsed = new URL(url);
    if (!/^(https?|wss?):$/.test(parsed.protocol)) return;
    traffic.urls.push(url);
    traffic.origins.add(parsed.origin);
    pending.push(
      request
        .headerValue('authorization')
        .then((value) => {
          if (value !== null) traffic.authorized.push(url);
        })
        // A request still open when its context is closed has no headers to read any more.
        .catch(() => undefined),
    );
  });
  return traffic;
}

interface Tab {
  page: Page;
  problems: Problems;
  /** Everything the tab wrote to its console and every error it raised, at any level. */
  log: string[];
  pageErrors: string[];
}

async function openTab(context: BrowserContext, path: string): Promise<Tab> {
  const page = await context.newPage();
  const problems = await watch(page, origin);
  const log: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (message) => log.push(message.text()));
  page.on('pageerror', (error) => {
    log.push(String(error));
    pageErrors.push(String(error));
  });
  await page.goto(`${engineUrl}${path}`);
  return { page, problems, log, pageErrors };
}

async function signedIn(context: BrowserContext, who: { token: string }, path = '/'): Promise<Tab> {
  const tab = await openTab(context, path);
  if ((await settled(tab.page)) === 'sign-in') await signInWith(tab.page, who.token);
  await tab.page.locator('aside nav').waitFor();
  return tab;
}

const api = (who: { token: string }, path: string, init: RequestInit = {}) =>
  fetch(`${engineUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/json', ...init.headers },
  });

async function putJar(name: string): Promise<string> {
  const response = await fetch(`${engineUrl}/api/v1/artifacts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${ada.token}`, 'Content-Type': 'application/octet-stream' },
    body: jarBytes(name) as unknown as BodyInit,
  });
  expect(response.status, `uploading ${name}`).toBeLessThan(300);
  return ((await response.json()) as { contentHash: string }).contentHash;
}

// ---- the checks that every screen goes through ------------------------------------------------

/** The console errors that are not the browser saying "the Engine answered 4xx" (some tests make it). */
const unexpectedConsole = (problems: Problems) =>
  problems.console.filter((line) => !/^4\d\d http/.test(line) && !/Failed to load resource/.test(line));

/**
 * The page shows [shown] as it is, and nothing of it has become anything else: no script has run,
 * no element that the hostile strings describe exists, no handler attribute, no inline script, no
 * link that is not the Console's own, nothing was asked of another origin, no policy was violated.
 */
async function expectInert(tab: Tab, shown: string[] = []) {
  const { page, problems } = tab;
  const found = await page.evaluate(() => ({
    xss: (window as unknown as { __xss?: unknown }).__xss ?? null,
    injected: [
      ...document.querySelectorAll(
        '[id^="pwn"], img[src*="evil"], [onerror], [onload], [onclick], [onmouseover]',
      ),
    ].map((element) => element.outerHTML.slice(0, 160)),
    inlineScripts: [...document.scripts].filter((script) => !script.src).length,
    markupInText: [...document.querySelectorAll('.plain')]
      .filter((element) => element.children.length > 0)
      .map((element) => element.outerHTML.slice(0, 160)),
    links: [...document.querySelectorAll('a[href]')]
      .map((a) => a.getAttribute('href')!)
      .filter((href) => !/^(\/|#)/.test(href)),
    text: document.body.innerText,
  }));
  expect(found.xss, 'a script of the hostile content ran').toBeNull();
  expect(found.injected, 'elements that the hostile strings describe').toEqual([]);
  expect(found.inlineScripts).toBe(0);
  expect(found.markupInText, 'a plain text value that has elements in it').toEqual([]);
  expect(found.links, 'links that do not stay in the Console').toEqual([]);
  for (const text of shown) expect(found.text, `shows ${text}`).toContain(text);
  expect(problems.csp).toEqual([]);
  expect(await violations(page)).toEqual([]);
  expect(problems.foreign, 'requests to another origin').toEqual([]);
  expect(tab.pageErrors).toEqual([]);
  expect(unexpectedConsole(problems)).toEqual([]);
}

const noHostileRequest = (traffic: Traffic) =>
  expect(traffic.urls.filter((url) => /evil|pwn/.test(url)), 'a request made for hostile content').toEqual([]);

const runIdOf = (page: Page) => new URL(page.url()).pathname.split('/').pop()!;
const dialog = (page: Page) => page.getByRole('dialog');

// ---- 1. untrusted content ---------------------------------------------------------------------

describe('untrusted content is text, on every screen', () => {
  let context: BrowserContext;
  let traffic: Traffic;
  let hash = '';

  beforeAll(async () => {
    context = await newContext(browser, 'en-US');
    traffic = record(context);
    hash = await putJar('adversarial');
  });
  afterAll(async () => {
    await traffic.settle();
    noHostileRequest(traffic);
    expect([...traffic.origins]).toEqual([origin]);
    await context.close();
  });

  test('uploading the jar: the result names its pipelines, as text', async () => {
    const tab = await signedIn(context, ada, '/upload');
    await tab.page.setInputFiles('input[type="file"]', join(jars, 'adversarial.jar'));
    await tab.page.locator('button.submit').click();
    await tab.page.locator('.result').waitFor();
    await expectInert(tab, ['adversarial-hostile', 'adversarial-resource']);
    await tab.page.close();
  });

  test('the list and the page of a pipeline: parameter name, default, network entry', async () => {
    const tab = await signedIn(context, ada, '/pipelines');
    await tab.page.locator('tbody tr', { hasText: 'adversarial-hostile' }).first().waitFor();
    await expectInert(tab, ['adversarial-hostile', 'adversarial-resource']);

    await tab.page.goto(`${engineUrl}/pipelines/${hash}?pipeline=adversarial-hostile`);
    await tab.page.getByRole('heading', { name: 'adversarial-hostile', level: 1 }).waitFor();
    await expectInert(tab, [PARAM_NAME, PARAM_DEFAULT, NETWORK_ENTRY]);
    await tab.page.close();
  });

  test('the page of a pipeline that declares a resource of that name: the entry and the warning', async () => {
    const tab = await signedIn(context, ada, `/pipelines/${hash}?pipeline=adversarial-resource`);
    await tab.page.getByRole('heading', { name: 'adversarial-resource', level: 1 }).waitFor();
    await expectInert(tab, [RESOURCE_NAME]);
    await tab.page.close();
  });

  test('a run of it is refused, and the refusal says the name: as text', async () => {
    const tab = await signedIn(context, ada, `/runs/new?contentHash=${hash}&pipeline=adversarial-resource`);
    await tab.page.locator('button.submit').click();
    await tab.page.getByRole('alert').waitFor();
    expect(await tab.page.getByRole('alert').textContent()).toContain('resources_unavailable');
    await expectInert(tab);
    await tab.page.close();
  });

  test('a jar whose class cannot be read: the reasons carry the name of the class and its path', async () => {
    const unreadable = await putJar('adversarial-unreadable');
    const tab = await signedIn(context, ada, `/pipelines/${unreadable}?pipeline=adversarial-unreadable`);
    await tab.page.getByRole('heading', { name: 'adversarial-unreadable', level: 1 }).waitFor();
    await expectInert(tab, [HELPER_CLASS]);
    expect(await tab.page.locator('.reasons').innerText()).toContain('samples.adversarial.UnreadablePipeline');
    await tab.page.close();
  });

  test('an upload the Engine refuses and says the hostile name back in its message: as text, in the message and the code', async () => {
    const tab = await signedIn(context, ada, '/upload');
    await tab.page.setInputFiles('input[type="file"]', join(jars, 'adversarial-name.jar'));
    await tab.page.locator('button.submit').click();
    const alert = tab.page.getByRole('alert');
    await alert.waitFor();
    expect(await alert.innerText()).toContain('422 invalid_pipeline_name');
    await expectInert(tab, [PIPELINE_NAME]);
    await tab.page.close();
  });

  test('a run: hostile values typed into the form, hostile log lines and failure; the run, the log, the failure, the lists', async () => {
    const tab = await signedIn(context, ada, `/runs/new?contentHash=${hash}&pipeline=adversarial-hostile`);
    const inputs = tab.page.locator('input[name^="param."]');
    // the default that the pipeline declared is an attribute value (the placeholder) and a text
    expect(await inputs.first().getAttribute('placeholder')).toBe(PARAM_DEFAULT);
    await expectInert(tab, [PARAM_NAME, PARAM_DEFAULT]);
    await tab.page.locator('input[name="param.plain"]').fill(TYPED);
    await tab.page.locator('button.submit').click();
    await tab.page.waitForURL(/\/runs\/[0-9a-f-]{36}$/);
    const runId = runIdOf(tab.page);

    await tab.page.locator('.head .badge').filter({ hasText: 'FAILED' }).waitFor({ timeout: 40_000 });
    await tab.page.getByText('The run has ended and the whole log was read.').waitFor();
    const lines = await tab.page.locator('.log .line .text').allTextContents();
    for (const line of LOG_LINES) expect(lines, `the log shows ${line}`).toContain(line);
    expect(lines.some((line) => line.includes(`param plain=${TYPED}`))).toBe(true);
    // no element in a line, none made of the "links" and "colours" that it says
    expect(await tab.page.locator('.log .line .text .plain *').count()).toBe(0);
    expect(await tab.page.locator('.log a, .log [style*="color"]').count()).toBe(0);
    // the line the control sequences are in is shown whole, not interpreted
    const control = lines.find((line) => line.includes('red?'))!;
    expect(control).toContain('\u001b[31m');
    expect(control).toContain('cleared?');
    await expectInert(tab, [FAILURE_MESSAGE, TYPED, PARAM_DEFAULT, 'IllegalStateException']);
    expect(await tab.page.locator('.failure').innerText()).toContain(FAILURE_MESSAGE);

    // what the Engine kept is what is shown: the page changed nothing
    const stored = (await (await api(ada, `/api/v1/runs/${runId}/log`)).json()) as {
      entries: { line: string }[];
    };
    expect(stored.entries.map((entry) => entry.line)).toEqual(lines);

    for (const path of ['/runs', '/', '/pipelines']) {
      await tab.page.goto(`${engineUrl}${path}`);
      await tab.page.locator('main').waitFor();
      await tab.page.waitForTimeout(500);
      await expectInert(tab);
    }
    await tab.page.close();
  });

  test('the admin pages: trigger form and detail with hostile values, the pipeline page, the allow-list and the resources with hostile input, the Engine page', async () => {
    const tab = await signedIn(context, root, `/triggers/new?contentHash=${hash}&pipeline=adversarial-hostile`);
    const name = `sec-${stamp()}`;
    try {
      await tab.page.locator('#trigger-name').fill(name);
      await tab.page.locator('#trigger-cron').fill('0 0 1 1 *');
      await tab.page.locator('input[id="param-plain"]').fill(TYPED);
      await expectInert(tab, [PARAM_NAME]);
      await tab.page.locator('button.submit').click();
      await tab.page.waitForURL(/\/triggers\/detail\?name=/);
      await tab.page.getByRole('heading', { name, level: 1 }).waitFor();
      await expectInert(tab, [TYPED, 'adversarial-hostile']);

      for (const path of ['/triggers', `/pipelines/${hash}?pipeline=adversarial-hostile`, '/engine']) {
        await tab.page.goto(`${engineUrl}${path}`);
        await tab.page.locator('main').waitFor();
        await tab.page.waitForTimeout(500);
        await expectInert(tab);
      }

      // the allow-list: what an admin types is refused, and the refusal does not repeat it as markup
      await tab.page.goto(`${engineUrl}/allowlist`);
      await tab.page.getByRole('button', { name: 'Add an entry' }).click();
      await dialog(tab.page).locator('input[name="entry-kind"][value="class"]').check();
      await dialog(tab.page).locator('#entry-name').fill(PIPELINE_NAME);
      await dialog(tab.page).getByRole('button', { name: 'Preview the effect' }).click();
      await dialog(tab.page).locator('.rl-field-error, .refusal, [role="alert"]').first().waitFor();
      // refused, in words of the Console: the typed text is in the field, and nowhere as markup
      await dialog(tab.page).getByText('The name is not valid.').waitFor();
      await expectInert(tab);
      expect(await dialog(tab.page).locator('#entry-name').inputValue()).toBe(PIPELINE_NAME);
      await dialog(tab.page).getByRole('button', { name: 'Cancel' }).click();

      await tab.page.goto(`${engineUrl}/resources`);
      await tab.page.getByRole('button', { name: 'Define a resource' }).click();
      await dialog(tab.page).locator('#resource-name').fill(RESOURCE_NAME);
      await dialog(tab.page).locator('#resource-capacity').fill('1');
      await dialog(tab.page).locator('button[type="submit"], button.primary').last().click();
      await dialog(tab.page).locator('.rl-field-error, [role="alert"]').first().waitFor();
      await expectInert(tab);
      expect(await dialog(tab.page).locator('#resource-name').inputValue()).toBe(RESOURCE_NAME);
    } finally {
      await api(root, `/api/v1/triggers/${name}`, { method: 'DELETE' });
    }
    await tab.page.close();
  });

  test('the check itself is not blind: markup that does get into a page, and a script that does run, fail it', async () => {
    const tab = await signedIn(context, ada, '/pipelines');
    await expectInert(tab);
    await tab.page.evaluate(() => {
      // what an {@html} would have done with the name of a pipeline
      document.querySelector('main')!.insertAdjacentHTML('beforeend', '<b id="pwn-control">x</b>');
    });
    await expect(expectInert(tab)).rejects.toThrow(/elements that the hostile strings describe/);
    await tab.page.evaluate(() => {
      document.getElementById('pwn-control')!.remove();
      (window as unknown as { __xss: string }).__xss = 'control';
    });
    await expect(expectInert(tab)).rejects.toThrow(/a script of the hostile content ran/);
    await tab.page.close();
  });

  test('the same screens in zh-TW', async () => {
    const zh = await newContext(browser, 'zh-TW');
    const zhTraffic = record(zh);
    const tab = await signedIn(zh, ada, `/pipelines/${hash}?pipeline=adversarial-hostile`);
    await tab.page.getByRole('heading', { name: 'adversarial-hostile', level: 1 }).waitFor();
    await expectInert(tab, [PARAM_NAME, PARAM_DEFAULT, NETWORK_ENTRY]);
    await zhTraffic.settle();
    noHostileRequest(zhTraffic);
    await zh.close();
  });
});

// ---- 2. the policy ----------------------------------------------------------------------------

const POLICY =
  "default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; " +
  "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

describe('the content security policy and the headers', () => {
  const assertHeaders = (response: Response, what: string) => {
    expect(response.headers.get('content-security-policy'), `${what}: policy`).toBe(POLICY);
    expect(response.headers.get('x-content-type-options'), `${what}: nosniff`).toBe('nosniff');
    expect(response.headers.get('x-frame-options'), `${what}: frame options`).toBe('DENY');
    expect(response.headers.get('referrer-policy'), `${what}: referrer`).toBe('no-referrer');
  };

  test('the entry page, a path the router takes, and every file it names carry them; the entry page has nothing inline and nothing foreign', async () => {
    const index = await fetch(`${engineUrl}/`);
    assertHeaders(index, '/');
    const html = await index.text();
    assertHeaders(await fetch(`${engineUrl}/pipelines`), '/pipelines (fallback)');
    expect(html).not.toMatch(/<script(?![^>]*\bsrc=)/i);
    expect(html).not.toMatch(/\son[a-z]+\s*=/i);
    expect(html).not.toMatch(/<style|\sstyle\s*=/i);
    expect(html).not.toMatch(/(?:src|href)\s*=\s*["']?(?:https?:)?\/\//i);

    const files = [...html.matchAll(/(?:src|href)="(\/[^"]+)"/g)].map((match) => match[1]);
    expect(files.some((file) => file.endsWith('.js'))).toBe(true);
    expect(files.some((file) => file.endsWith('.css'))).toBe(true);
    for (const file of files) {
      const response = await fetch(`${engineUrl}${file}`);
      expect(response.status, file).toBe(200);
      assertHeaders(response, file);
      if (file.endsWith('.css')) {
        const css = await response.text();
        expect(css, `${file} imports or loads something foreign`).not.toMatch(
          /@import|url\(\s*["']?(?:https?:)?\/\//i,
        );
        // the fonts it loads are files of the Engine, with the same headers
        for (const [, font] of css.matchAll(/url\(["']?([^"')]+\.woff2?)["']?\)/g)) {
          assertHeaders(await fetch(new URL(font, `${engineUrl}${file}`)), font);
        }
      }
    }
    assertHeaders(await fetch(`${engineUrl}/favicon.svg`), '/favicon.svg');
  });

  test('the browser enforces it: an inline script, a handler, a third-party image, script and request are blocked and reported', async () => {
    const context = await newContext(browser, 'en-US');
    const traffic = record(context);
    const tab = await signedIn(context, ada);
    const outcome = await tab.page.evaluate(async () => {
      const window_ = window as unknown as Record<string, unknown>;
      const script = document.createElement('script');
      script.textContent = 'window.__injected = "inline script"';
      document.body.append(script);

      const button = document.createElement('button');
      button.setAttribute('onclick', 'window.__injected = "handler"');
      document.body.append(button);
      button.click();

      const image = document.createElement('img');
      image.src = 'https://evil.invalid/third-party.png';
      document.body.append(image);

      const foreign = document.createElement('script');
      foreign.src = 'https://evil.invalid/third-party.js';
      document.body.append(foreign);

      const styled = document.createElement('div');
      styled.setAttribute('style', 'background: url(https://evil.invalid/style.png)');
      document.body.append(styled);

      let fetched = 'fetched';
      try {
        await fetch('https://evil.invalid/request');
      } catch {
        fetched = 'refused';
      }
      await new Promise((resolve) => setTimeout(resolve, 500));
      return { injected: window_.__injected ?? null, fetched };
    });
    expect(outcome.injected).toBeNull();
    expect(outcome.fetched).toBe('refused');
    const reported = (await violations(tab.page)).join('\n');
    for (const directive of ['script-src', 'img-src', 'connect-src', 'style-src']) {
      expect(reported, `a ${directive} violation is reported`).toContain(directive);
    }
    expect(reported).toContain('evil.invalid');
    await traffic.settle();
    // The browser lists what it was about to fetch, and says it was stopped by the policy: none
    // of it was answered, and nothing that was stopped is anything but what the test injected.
    const foreign = traffic.urls.filter((url) => url.includes('evil.invalid'));
    expect(foreign.length).toBeGreaterThan(0);
    for (const url of foreign) expect(traffic.failed, `${url} was blocked by the policy`).toContain(`${url} csp`);
    expect(traffic.responded.filter((url) => url.includes('evil.invalid'))).toEqual([]);
    await context.close();
  });

  test('a page of another origin cannot show the Console in a frame', async () => {
    const server = createServer((_, response) => {
      response.setHeader('Content-Type', 'text/html');
      response.end(`<!doctype html><iframe id="frame" src="${engineUrl}/"></iframe>`);
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const port = (server.address() as { port: number }).port;
    const context = await newContext(browser, 'en-US');
    try {
      const page = await context.newPage();
      const refusals: string[] = [];
      page.on('console', (message) => refusals.push(message.text()));
      await page.goto(`http://127.0.0.1:${port}/`);
      await page.waitForTimeout(2000);
      const children = page.frames().filter((frame) => frame !== page.mainFrame());
      expect(children).toHaveLength(1);
      expect(children[0].url().startsWith(engineUrl!), 'the Console loaded inside the frame').toBe(false);
      expect(await children[0].locator('#app').count()).toBe(0);
      // the browser says why: it was the Console's own headers that refused the frame
      expect(refusals.join('\n')).toMatch(/(Refused to (display|frame)|Framing) .*(X-Frame-Options|frame-ancestors)/);
    } finally {
      await context.close();
      await new Promise((resolve) => server.close(resolve));
    }
  });
});

// ---- 3. the token -----------------------------------------------------------------------------

describe('the token', () => {
  const secret = ada.token;

  test('is only in the sessionStorage of the tabs and in the Authorization header to the Engine; every tab forgets it on sign-out; the log is polled, no WebSocket', async () => {
    const context = await newContext(browser, 'en-US');
    const traffic = record(context);
    const navigated: string[] = [];
    context.on('page', (page) => page.on('framenavigated', (frame) => navigated.push(frame.url())));

    const first = await signedIn(context, ada, '/');
    for (const path of ['/pipelines', '/runs', '/engine', '/upload']) {
      await first.page.goto(`${engineUrl}${path}`);
      await first.page.locator('aside nav').waitFor();
    }
    // a run that goes on while the tab looks at it: the log is read with ?after=, not by a socket
    const slow = await putJar('demo-slow');
    const started = await api(ada, '/api/v1/runs', {
      method: 'POST',
      body: JSON.stringify({
        contentHash: slow,
        pipeline: 'demo-slow',
        parameters: { steps: '4', delayMillis: '500' },
      }),
    });
    expect(started.status).toBeLessThan(300);
    const runId = ((await started.json()) as { runId: string }).runId;
    await first.page.goto(`${engineUrl}/runs/${runId}`);
    await first.page.locator('.head .badge').filter({ hasText: 'SUCCEEDED' }).waitFor({ timeout: 40_000 });

    // more tabs: they get the session without asking for the token
    const second = await openTab(context, '/pipelines');
    const third = await openTab(context, '/runs');
    expect(await settled(second.page)).toBe('signed-in');
    expect(await settled(third.page)).toBe('signed-in');
    const tabs = [first, second, third];

    const storage = (page: Page) =>
      page.evaluate(async () => ({
        session: JSON.stringify(Object.entries(sessionStorage)),
        local: JSON.stringify(Object.entries(localStorage)),
        cookie: document.cookie,
        databases: (await indexedDB.databases()).length,
        caches: (await caches.keys()).length,
        workers: (await navigator.serviceWorker.getRegistrations()).length,
        dom: document.documentElement.outerHTML,
        address: location.href,
      }));
    for (const tab of tabs) {
      const state = await storage(tab.page);
      expect(state.session, 'the tab keeps the session in its sessionStorage').toContain(secret);
      expect(state.local, 'localStorage').not.toContain(secret);
      expect(state.cookie).toBe('');
      expect(state.databases, 'indexedDB').toBe(0);
      expect(state.caches, 'Cache storage').toBe(0);
      expect(state.workers, 'service workers').toBe(0);
      expect(state.dom, 'the page').not.toContain(secret);
      expect(state.address).not.toContain(secret);
    }
    expect(await context.cookies()).toEqual([]);
    expect(await context.storageState()).toMatchObject({ cookies: [] });
    const persistent = JSON.stringify((await context.storageState()).origins);
    expect(persistent, 'what the browser would keep of the origin').not.toContain(secret);

    await traffic.settle();
    for (const url of traffic.urls) {
      expect(url, 'an address with the token in it').not.toContain(secret);
      expect(decodeURIComponent(url)).not.toContain(secret);
    }
    for (const url of navigated) expect(url, 'history').not.toContain(secret);
    expect(navigated.length).toBeGreaterThan(5);
    expect([...traffic.origins], 'requests of the Console go to the Engine of its origin only').toEqual([origin]);
    expect(traffic.authorized.length).toBeGreaterThan(5);
    for (const url of traffic.authorized) {
      expect(new URL(url).origin).toBe(origin);
      expect(new URL(url).pathname.startsWith('/api/v1/'), `${url} carries the credential`).toBe(true);
    }
    // the log
    expect(traffic.websockets, 'WebSocket connections').toEqual([]);
    expect(traffic.urls.filter((url) => /^wss?:/.test(url) || /\/log\/stream/.test(url))).toEqual([]);
    expect(traffic.urls.filter((url) => /\/runs\/[^/]+\/log\?after=/.test(url)).length).toBeGreaterThan(0);

    // nothing the tabs wrote to their console or raised as an error has it
    for (const tab of tabs) {
      expect(tab.log.join('\n')).not.toContain(secret);
      expect(tab.pageErrors).toEqual([]);
    }

    // sign out in one tab: every tab ends, and each forgets the token
    await first.page.locator('header').getByRole('button', { name: 'Sign out' }).click();
    for (const tab of tabs) await tab.page.locator('form input').waitFor({ timeout: 10_000 });
    for (const tab of tabs) {
      const state = await storage(tab.page);
      expect(state.session, 'sessionStorage after sign-out').not.toContain(secret);
      expect(state.local).not.toContain(secret);
      expect(state.dom).not.toContain(secret);
    }
    // a tab that opens afterwards has nobody to ask
    const fourth = await openTab(context, '/');
    expect(await settled(fourth.page)).toBe('sign-in');
    expect((await storage(fourth.page)).session).not.toContain(secret);
    expect(JSON.stringify((await context.storageState()).origins)).not.toContain(secret);
    await traffic.settle();
    expect(traffic.urls.filter((url) => url.includes(secret))).toEqual([]);
    await context.close();
  }, 120_000);

  test('a token the Engine refuses is nowhere afterwards: not in the address, the page, the storage, the console', async () => {
    const wrong = `wrong-${stamp()}-token`;
    const context = await newContext(browser, 'en-US');
    const traffic = record(context);
    const tab = await openTab(context, '/');
    expect(await settled(tab.page)).toBe('sign-in');
    await signInWith(tab.page, wrong);
    await tab.page.getByRole('alert').or(tab.page.locator('.rl-field-error')).first().waitFor();
    const state = await tab.page.evaluate(() => ({
      session: JSON.stringify(Object.entries(sessionStorage)),
      local: JSON.stringify(Object.entries(localStorage)),
      address: location.href,
      html: document.documentElement.outerHTML,
    }));
    // The input still holds what was typed (a value, not an attribute of the page).
    expect(state.session).not.toContain(wrong);
    expect(state.local).not.toContain(wrong);
    expect(state.address).not.toContain(wrong);
    expect(tab.log.join('\n')).not.toContain(wrong);
    await traffic.settle();
    expect(traffic.urls.filter((url) => url.includes(wrong))).toEqual([]);
    expect(traffic.websockets).toEqual([]);
    await context.close();
  });
});

// The pages of a developer in a real browser (Chrome), against a real packaged Engine with its
// PostgreSQL, and real pipeline jars (the sample pipelines of dev/sample-pipelines, named by
// E2E_JARS): upload, the list and the page of a pipeline, creating a run, watching it and its log
// while it goes on, cancelling it, a failure, an unsafe pipeline, and what a developer may not see.
// Real clocks: the log is read as it is written, and the waits are for conditions on the page.

import { mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Browser, BrowserContext, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { launchChrome, newContext, settled, signInWith, violations, watch } from './browser';

const engineUrl = process.env.E2E_ENGINE_URL;
if (!engineUrl) throw new Error('E2E_ENGINE_URL is not set: the tests need a running Engine');
const tokens = process.env.E2E_TOKENS;
if (!tokens)
  throw new Error('E2E_TOKENS is not set: name:role:token of two developers and an admin');
const callers = parseCallers(tokens);
const developers = callers.filter((c) => c.role === 'developer');
if (developers.length < 2) throw new Error('E2E_TOKENS must have two developers');
const [ada, bob] = developers;
const root = callers.find((c) => c.role === 'admin')!;
const jars = process.env.E2E_JARS;
if (!jars) {
  throw new Error(
    'E2E_JARS is not set: the directory of the sample jars (dev/sample-pipelines/build/pipelines)',
  );
}
const jar = (name: string) => join(jars, `${name}.jar`);
const screenshots = process.env.E2E_SCREENSHOTS;
if (screenshots) mkdirSync(screenshots, { recursive: true });

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
});
afterAll(async () => {
  await browser?.close();
});

const shot = async (page: Page, name: string) => {
  if (screenshots) await page.screenshot({ path: `${screenshots}/${name}.png`, fullPage: true });
};

/** A tab of [context] at [path], signed in as [who]. */
async function signedIn(context: BrowserContext, who: { token: string }, path = '/') {
  const page = await context.newPage();
  const problems = await watch(page, engineUrl!);
  await page.goto(`${engineUrl}${path}`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, who.token);
  await page.locator('aside nav').waitFor();
  return { page, problems };
}

const api = (who: { token: string }, path: string, init: RequestInit = {}) =>
  fetch(`${engineUrl}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${who.token}`,
      'Content-Type': 'application/json',
      ...init.headers,
    },
  });

/** Uploads a sample jar as [who] straight to the Engine, for what a test needs to be there. */
async function putJar(who: { token: string }, name: string) {
  const { readFileSync } = await import('node:fs');
  const response = await fetch(`${engineUrl}/api/v1/artifacts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/octet-stream' },
    body: readFileSync(jar(name)),
  });
  return (await response.json()) as { contentHash: string };
}

const runIdOf = (page: Page) => new URL(page.url()).pathname.split('/').pop()!;
const lineTexts = (page: Page) => page.locator('.log .line .text').allTextContents();
const hasState = (page: Page, state: string) =>
  page.locator('.head .badge').filter({ hasText: state }).waitFor({ timeout: 40_000 });

describe('uploading a jar', () => {
  test('a real jar: the Engine reads it, the page says the version and each pipeline with its verdict', async () => {
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(context, ada, '/upload');

    await page.setInputFiles('input[type="file"]', jar('demo-slow'));
    await page.locator('.chosen').getByText('demo-slow.jar').waitFor();
    await shot(page, 'upload-chosen-en');
    await page.locator('button.submit').click();

    const result = page.locator('.result');
    await result.waitFor();
    // A new version the first time, the version that was there on the runs after it.
    expect(await result.innerText()).toMatch(/A new version was made|You uploaded this jar before/);
    const line = result.locator('.pipeline-line');
    expect(await line.innerText()).toContain('demo-slow');
    expect(await line.locator('.badge').innerText()).toContain('SAFE');
    await shot(page, 'upload-result-en');

    // Again: the same bytes are the version that was there (200), and no new one is made.
    await page.locator('button.another').click();
    await page.setInputFiles('input[type="file"]', jar('demo-slow'));
    await page.locator('button.submit').click();
    await result.waitFor();
    expect(await result.innerText()).toContain('You uploaded this jar before');
    expect(await violations(page)).toEqual([]);
    expect(problems.csp).toEqual([]);
    expect(problems.foreign).toEqual([]);
    await context.close();
  });

  test('refusals are said in words, with what the Engine names: not a jar, no pipeline, over the limit', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, ada, '/upload');
    const dir = join(tmpdir(), 'runline-e2e');
    mkdirSync(dir, { recursive: true });

    writeFileSync(join(dir, 'junk.jar'), 'this is not a jar');
    await page.setInputFiles('input[type="file"]', join(dir, 'junk.jar'));
    await page.locator('button.submit').click();
    await page.getByRole('alert').getByText('The file is not a valid jar.').waitFor();
    expect(await page.getByRole('alert').innerText()).toContain('422 not_a_jar');
    await shot(page, 'upload-not-a-jar-en');

    // A real zip without a pipeline in it: made with the zip of the sample jar's own format.
    const { storedZip } = await import('../test-support/zip');
    writeFileSync(join(dir, 'empty.jar'), storedZip({ 'hello.txt': 'no pipeline here' }));
    await page.setInputFiles('input[type="file"]', join(dir, 'empty.jar'));
    await page.locator('button.submit').click();
    await page.getByRole('alert').getByText('The jar declares no pipeline.').waitFor();

    writeFileSync(join(dir, 'big.jar'), Buffer.alloc(51 * 1024 * 1024));
    await page.setInputFiles('input[type="file"]', join(dir, 'big.jar'));
    await page.locator('button.submit').click();
    await page
      .getByRole('alert')
      .getByText('The file is larger than the upload limit.')
      .waitFor({ timeout: 30_000 });
    expect(await page.getByRole('alert').innerText()).toContain('413 too_large');
    await shot(page, 'upload-too-large-en');
    await context.close();
  });

  test('the progress of a large upload is seen as it goes, and the upload can be cancelled', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, ada, '/upload');
    const dir = join(tmpdir(), 'runline-e2e');
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'slow.jar'), Buffer.alloc(40 * 1024 * 1024));
    // Make the network slow enough to look at: 4 MB a second for what is sent.
    const cdp = await context.newCDPSession(page);
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 0,
      downloadThroughput: -1,
      uploadThroughput: 4 * 1024 * 1024,
    });

    await page.setInputFiles('input[type="file"]', join(dir, 'slow.jar'));
    await page.locator('button.submit').click();
    const bar = page.locator('progress');
    await bar.waitFor();
    const values: number[] = [];
    // sampled while it goes: the bar has more in it each time, and it is not full
    await expect
      .poll(
        async () => {
          values.push(await bar.evaluate((e: HTMLProgressElement) => e.value));
          return values.length >= 3 && values.at(-1)! > values[0];
        },
        { timeout: 20_000, interval: 250 },
      )
      .toBe(true);
    const max = await bar.evaluate((e: HTMLProgressElement) => e.max);
    expect(max).toBe(40 * 1024 * 1024);
    expect(values.at(-1)!).toBeLessThan(max);
    await shot(page, 'upload-progress-en');

    await page.locator('button.cancel').click();
    await page.getByText('The upload was cancelled.').waitFor();
    expect(await page.locator('progress').count()).toBe(0);
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 0,
      downloadThroughput: -1,
      uploadThroughput: -1,
    });
    await context.close();
  });
});

describe('the pipelines', () => {
  test('the list and the page of a pipeline: parameters, verdict, and for an UNSAFE one every reason with its path', async () => {
    await putJar(ada, 'demo-slow');
    await putJar(ada, 'demo-unsafe');
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(context, ada, '/pipelines');

    const slow = page.locator('tbody tr', { hasText: 'demo-slow' }).first();
    await slow.waitFor();
    expect(await slow.locator('.badge').innerText()).toContain('SAFE');
    const unsafe = page.locator('tbody tr', { hasText: 'demo-unsafe' }).first();
    expect(await unsafe.locator('.badge').innerText()).toContain('UNSAFE');
    await shot(page, 'pipelines-en');

    await slow.getByRole('link', { name: 'Details' }).click();
    await page.getByRole('heading', { name: 'demo-slow', level: 1 }).waitFor();
    const text = await page.locator('main').innerText();
    expect(text).toContain('delayMillis');
    expect(text).toContain('samples.slow.SlowPipeline');
    await shot(page, 'pipeline-safe-en');

    await page.goto(`${engineUrl}/pipelines`);
    await page
      .locator('tbody tr', { hasText: 'demo-unsafe' })
      .first()
      .getByRole('link', { name: 'Details' })
      .click();
    await page.getByRole('heading', { name: 'demo-unsafe', level: 1 }).waitFor();
    const reasons = page.locator('.reasons > li');
    expect(await reasons.count()).toBeGreaterThanOrEqual(3);
    const unlisted = reasons.filter({ hasText: 'Class outside the allow-list' });
    expect(await unlisted.innerText()).toContain('java.io.File');
    expect(await unlisted.locator('.path li').allTextContents()).toEqual([
      'samples.unsafe.UnsafePipeline',
      'java.io.File',
    ]);
    expect(await page.locator('main').innerText()).toContain(
      'has not allowed this pipeline to run',
    );
    await shot(page, 'pipeline-unsafe-en');

    expect(problems.csp).toEqual([]);
    expect(await violations(page)).toEqual([]);
    await context.close();
  });

  test('another developer does not see the jar, and its page is the same page as for one that does not exist', async () => {
    const { contentHash } = await putJar(ada, 'demo-failing');
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, bob, '/pipelines');
    await page.locator('.rl-empty, table').first().waitFor();
    expect(await page.locator('main').innerText()).not.toContain('demo-failing');

    await page.goto(`${engineUrl}/pipelines/${contentHash}?pipeline=demo-failing`);
    await page.getByText('Version not found').waitFor();
    await page.goto(`${engineUrl}/pipelines/${'0'.repeat(64)}`);
    await page.getByText('Version not found').waitFor();
    await context.close();
  });
});

describe('creating a run and watching it', () => {
  test('a run of a real pipeline: its log comes in line by line while it runs, and polling stops when it ends', async () => {
    const { contentHash } = await putJar(ada, 'demo-slow');
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(
      context,
      ada,
      `/runs/new?contentHash=${contentHash}&pipeline=demo-slow`,
    );

    await page.locator('input[name="param.steps"]').fill('8');
    await page.locator('input[name="param.delayMillis"]').fill('700');
    await page.locator('input[name="param.label"]').fill('<b>e2e</b>');
    await shot(page, 'create-run-en');
    const logRequests: number[] = [];
    page.on('request', (request) => {
      if (/\/api\/v1\/runs\/[^/]+\/log/.test(request.url())) logRequests.push(Date.now());
    });
    await page.locator('button.submit').click();
    await page.waitForURL(/\/runs\/[0-9a-f-]{36}$/);
    const runId = runIdOf(page);

    // The lines come while the run goes on: the first soon, the rest after it, not all at once.
    await page.locator('.log .line').first().waitFor();
    const firstCount = (await lineTexts(page)).length;
    await page
      .locator('.head .badge')
      .filter({ hasText: /RUNNING|INITIALIZING|QUEUED/ })
      .waitFor();
    expect(await page.locator('.log-status').innerText()).toContain('Following');
    await expect
      .poll(async () => (await lineTexts(page)).length, { timeout: 20_000 })
      .toBeGreaterThan(firstCount);
    await shot(page, 'run-live-en');
    // What the pipeline prints is text: the label has markup in it, and none of it is markup.
    expect((await lineTexts(page))[0]).toBe('[<b>e2e</b>] starting 8 steps, 700ms each');
    expect(await page.locator('.log b').count()).toBe(0);

    await hasState(page, 'SUCCEEDED');
    await page.getByText('The run has ended and the whole log was read.').waitFor();
    const lines = await lineTexts(page);
    expect(lines.at(-1)).toBe('[<b>e2e</b>] finished');
    expect(lines).toHaveLength(10);
    expect(await page.locator('.log .line .seq').allTextContents()).toEqual([
      '1',
      '2',
      '3',
      '4',
      '5',
      '6',
      '7',
      '8',
      '9',
      '10',
    ]);
    expect(await page.locator('button.cancel').count()).toBe(0);
    await shot(page, 'run-done-en');

    // Ended and read to the end: it asks nothing more.
    const asked = logRequests.length;
    await page.waitForTimeout(3500);
    expect(logRequests.length).toBe(asked);
    expect(logRequests.length).toBeGreaterThan(2);

    // The log the Engine keeps is the log the page shows.
    const stored = await (await api(ada, `/api/v1/runs/${runId}/log`)).json();
    expect(stored.entries.map((e: { line: string }) => e.line)).toEqual(lines);
    expect(problems.csp).toEqual([]);
    expect(problems.foreign).toEqual([]);
    expect(await violations(page)).toEqual([]);
    await context.close();
  });

  test('a tab that is hidden asks nothing, and goes on from where it was when it is shown again', async () => {
    const { contentHash } = await putJar(ada, 'demo-slow');
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(
      context,
      ada,
      `/runs/new?contentHash=${contentHash}&pipeline=demo-slow&param.steps=20&param.delayMillis=500`,
    );
    await page.locator('button.submit').click();
    await page.waitForURL(/\/runs\/[0-9a-f-]{36}$/);
    await page.locator('.log .line').first().waitFor();

    // A page of headless Chrome is always visible: the tab being hidden is said to the page the way
    // the browser says it, with the property and the event.
    const hide = (hidden: boolean) =>
      page.evaluate((value) => {
        Object.defineProperty(document, 'visibilityState', {
          configurable: true,
          get: () => (value ? 'hidden' : 'visible'),
        });
        document.dispatchEvent(new Event('visibilitychange'));
      }, hidden);
    const requests: string[] = [];
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/runs/')) requests.push(request.url());
    });

    await hide(true);
    await page.getByText('Paused: this tab is hidden').waitFor();
    await page.waitForTimeout(500); // anything on its way has arrived
    const seen = (await lineTexts(page)).length;
    requests.length = 0;
    await page.waitForTimeout(3000);
    expect(requests).toEqual([]);
    expect((await lineTexts(page)).length).toBe(seen);

    await hide(false);
    await expect
      .poll(async () => (await lineTexts(page)).length, { timeout: 10_000 })
      .toBeGreaterThan(seen);
    await page.getByText('Following').waitFor();
    // the line numbers are still 1, 2, 3, ...: nothing lost, nothing twice
    const numbers = (await page.locator('.log .line .seq').allTextContents()).map(Number);
    expect(numbers).toEqual(numbers.map((_, i) => i + 1));

    await page.locator('button.cancel').click();
    await page.locator('button.confirm-cancel').click();
    await hasState(page, 'CANCELLED');
    await context.close();
  });

  test('cancelling a run that goes on: asked first, then the run ends CANCELLED, and what the pipeline reported is shown', async () => {
    const { contentHash } = await putJar(ada, 'demo-slow');
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(
      context,
      ada,
      `/runs/new?contentHash=${contentHash}&pipeline=demo-slow&param.steps=60&param.delayMillis=1000`,
    );
    await page.locator('button.submit').click();
    await page.waitForURL(/\/runs\/[0-9a-f-]{36}$/);
    await page.locator('.log .line').first().waitFor();

    await page.locator('button.cancel').click();
    await page.locator('.confirm').getByText('Cancel this run?').waitFor();
    await shot(page, 'run-cancel-confirm-en');
    await page.locator('button.keep').click();
    expect(await page.locator('.confirm').count()).toBe(0);
    expect(await page.locator('.head .badge').innerText()).not.toContain('CANCELLED');

    await page.locator('button.cancel').click();
    await page.locator('button.confirm-cancel').click();
    await page
      .locator('.cancel-result')
      .filter({ hasText: /Stop requested|had not started/ })
      .waitFor();
    await hasState(page, 'CANCELLED');
    await page.locator('.failure').waitFor(); // the pipeline was interrupted: the Engine keeps what it said
    expect(await page.locator('.failure').innerText()).toContain('InterruptedException');
    expect(await page.locator('button.cancel').count()).toBe(0);
    await shot(page, 'run-cancelled-en');

    // and a run that has ended cannot be cancelled: the Engine says so
    const runId = runIdOf(page);
    const again = await api(ada, `/api/v1/runs/${runId}/cancel`, { method: 'POST' });
    expect(again.status).toBe(409);
    await context.close();
  });

  test('a failing pipeline: FAILED, with the failure as text (the message has markup in it), and the log it wrote', async () => {
    const { contentHash } = await putJar(ada, 'demo-failing');
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(context, ada, '/');
    await page.evaluate(() => ((window as unknown as { __pwned?: number }).__pwned = undefined));
    await page.goto(
      `${engineUrl}/runs/new?contentHash=${contentHash}&pipeline=demo-failing&param.reason=${encodeURIComponent('<img src=x onerror="window.__pwned=1"> boom')}`,
    );
    await page.locator('button.submit').click();
    await page.waitForURL(/\/runs\/[0-9a-f-]{36}$/);

    await hasState(page, 'FAILED');
    const failure = page.locator('.failure');
    await failure.waitFor();
    expect(await failure.innerText()).toContain('IllegalStateException');
    expect(await failure.innerText()).toContain('<img src=x onerror="window.__pwned=1"> boom');
    await failure.getByText('Stack trace').click();
    expect(await failure.locator('pre').innerText()).toContain('FailingPipeline');
    expect(await page.locator('main img').count()).toBe(0);
    expect(
      await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned),
    ).toBeUndefined();
    await page.getByText('The run has ended and the whole log was read.').waitFor();
    expect(await lineTexts(page)).toEqual(['preparing', 'about to fail']);
    await shot(page, 'run-failed-en');
    expect(await violations(page)).toEqual([]);
    expect(problems.csp).toEqual([]);

    // run it again: the form has the parameters of the run
    await page.getByRole('link', { name: 'Run again' }).click();
    await page.locator('input[name="param.reason"]').waitFor();
    expect(await page.locator('input[name="param.reason"]').inputValue()).toBe(
      '<img src=x onerror="window.__pwned=1"> boom',
    );
    await context.close();
  });

  test('what the Engine refuses to create is said: an UNSAFE pipeline, a shared resource that is not there, a parameter it does not declare', async () => {
    const unsafe = await putJar(ada, 'demo-unsafe');
    const resource = await putJar(ada, 'demo-resource');
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(
      context,
      ada,
      `/runs/new?contentHash=${unsafe.contentHash}&pipeline=demo-unsafe`,
    );

    await page.locator('.unsafe').getByText('has not allowed it to run').waitFor();
    await page.locator('button.submit').click();
    await page
      .getByRole('alert')
      .getByText('The pipeline is UNSAFE and an admin has not allowed unsafe execution.')
      .waitFor();
    await shot(page, 'create-run-unsafe-en');

    // When the admin tests defined demo-printer (they leave it defined), it is disabled here, and the
    // Engine says that instead.
    const defined = (await api(root, '/api/v1/resources/demo-printer')).status === 200;
    if (defined) {
      await api(root, '/api/v1/resources/demo-printer', {
        method: 'PATCH',
        body: JSON.stringify({ enabled: false }),
      });
    }
    await page.goto(
      `${engineUrl}/runs/new?contentHash=${resource.contentHash}&pipeline=demo-resource`,
    );
    await page.locator('button.submit').click();
    const alert = page.getByRole('alert');
    await alert
      .getByText('A shared resource the pipeline needs is not defined or is disabled.')
      .waitFor();
    expect(await alert.locator('.problems li').allTextContents()).toEqual([
      defined ? 'demo-printer: the shared resource is disabled.' : 'demo-printer: no such shared resource.',
    ]);
    await shot(page, 'create-run-resource-en');

    // a parameter the pipeline does not declare, sent by hand, is what the Engine calls invalid
    const slow = await putJar(ada, 'demo-slow');
    const refused = await api(ada, '/api/v1/runs', {
      method: 'POST',
      body: JSON.stringify({
        contentHash: slow.contentHash,
        pipeline: 'demo-slow',
        parameters: { nope: '1' },
      }),
    });
    expect(refused.status).toBe(422);
    await context.close();
  });

  test("a run that is not the developer's is not there: the same page as for a run that never was", async () => {
    const { contentHash } = await putJar(ada, 'demo-failing');
    const made = await (
      await api(ada, '/api/v1/runs', {
        method: 'POST',
        body: JSON.stringify({ contentHash, pipeline: 'demo-failing' }),
      })
    ).json();
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, bob, `/runs/${made.runId}`);
    await page.getByText('Run not found').waitFor();
    const theirs = await page.locator('main').innerText();
    await page.goto(`${engineUrl}/runs/00000000-0000-4000-8000-000000000000`);
    await page.getByText('Run not found').waitFor();
    expect(await page.locator('main').innerText()).toBe(theirs);
    await page.goto(`${engineUrl}/runs`);
    await page.locator('.rl-empty, table').first().waitFor();
    expect(await page.locator('main').innerText()).not.toContain(made.runId.slice(0, 8));
    await context.close();
  });
});

describe('the lists', () => {
  test('the runs list is the newest first, is filtered by state and pipeline, and shows a run made elsewhere without a reload', async () => {
    const slow = await putJar(ada, 'demo-slow');
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(context, ada, '/runs');
    await page.locator('.rl-empty, table').first().waitFor();
    const before = await page.locator('tbody tr').count();
    await page.evaluate(() => ((window as unknown as { __mark: number }).__mark = 7));

    const made = await (
      await api(ada, '/api/v1/runs', {
        method: 'POST',
        body: JSON.stringify({
          contentHash: slow.contentHash,
          pipeline: 'demo-slow',
          parameters: { steps: '2', delayMillis: '300' },
        }),
      })
    ).json();
    // within a few seconds, by itself
    await page
      .locator('tbody tr')
      .first()
      .locator('.run-id')
      .filter({ hasText: made.runId.slice(0, 8) })
      .waitFor({ timeout: 10_000 });
    expect(await page.locator('tbody tr').count()).toBe(before + 1);
    expect(await page.evaluate(() => (window as unknown as { __mark: number }).__mark)).toBe(7);
    await page
      .locator('tbody tr')
      .first()
      .locator('.badge')
      .filter({ hasText: 'SUCCEEDED' })
      .waitFor({ timeout: 10_000 });
    await shot(page, 'runs-en');

    await page.getByLabel('State').selectOption('FAILED');
    await expect
      .poll(async () =>
        page
          .locator('tbody tr .badge')
          .allTextContents()
          .then((all) => all.every((t) => t.includes('FAILED'))),
      )
      .toBe(true);
    expect(new URL(page.url()).search).toContain('state=FAILED');

    await page.getByLabel('State').selectOption('');
    await page.getByLabel('Pipeline').selectOption('demo-slow');
    await expect
      .poll(async () =>
        page
          .locator('tbody tr .pipeline')
          .allTextContents()
          .then((all) => all.length > 0 && all.every((t) => t === 'demo-slow')),
      )
      .toBe(true);

    await page.reload();
    expect(await page.getByLabel('Pipeline').inputValue()).toBe('demo-slow');
    expect(problems.csp).toEqual([]);
    await context.close();
  });

  test('the overview has the numbers and the recent runs', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, ada, '/');
    await page.locator('.tiles').waitFor();
    const tiles = await page.locator('.tile').allInnerTexts();
    expect(tiles.some((t) => /runs today/i.test(t))).toBe(true);
    expect(await page.locator('tbody tr').count()).toBeGreaterThan(0);
    await shot(page, 'overview-en');
    await context.close();
  });

  test('the same pages in Traditional Chinese, and an admin sees what the developers made', async () => {
    const context = await newContext(browser, 'zh-TW');
    const { page } = await signedIn(context, root, '/runs');
    await page.locator('table').waitFor();
    expect(await page.locator('main').innerText()).toContain('建立 run');
    await shot(page, 'runs-admin-zh-TW');
    await page.goto(`${engineUrl}/pipelines`);
    await page.locator('table').waitFor();
    expect(await page.locator('tbody tr').count()).toBeGreaterThan(0);
    await shot(page, 'pipelines-admin-zh-TW');
    await context.close();
  });
});

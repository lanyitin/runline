// The same jar uploaded by two people (WI-54, ADR-020), in a real browser against a real packaged
// Engine: the second uploader gets a version of their own and runs it, and an admin sees both in the
// list and binds a trigger to one of them by naming whose. Not part of `check`; run by hand with the
// variables of the other browser tests (E2E_ENGINE_URL, E2E_TOKENS with two developers and an admin,
// E2E_JARS).

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Browser, BrowserContext, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { uniqueJar } from '../test-support/zip';
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
if (!jars) throw new Error('E2E_JARS is not set: the directory of the sample jars');

let browser: Browser;
beforeAll(async () => {
  browser = await launchChrome();
});
afterAll(async () => {
  await browser?.close();
});

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

// Bytes no one has uploaded: this run of the test does not meet the versions of an earlier one.
const bytes = uniqueJar(
  readFileSync(join(jars, 'demo-slow.jar')),
  `e2e${Date.now().toString(36)}`,
) as Uint8Array;
const dir = join(process.env.TMPDIR ?? '/tmp', `runline-e2e-versions-${Date.now()}`);

describe('one jar uploaded by two people', () => {
  let hash = '';
  let runId = '';
  let triggerName = '';

  test('the first uploader has a version; the second, uploading the same bytes in the page, gets one of their own', async () => {
    const first = await fetch(`${engineUrl}/api/v1/artifacts`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${ada.token}`, 'Content-Type': 'application/octet-stream' },
      body: bytes as unknown as BodyInit,
    });
    expect(first.status).toBe(201);
    hash = ((await first.json()) as { contentHash: string }).contentHash;

    const { mkdirSync, writeFileSync } = await import('node:fs');
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'shared.jar'), bytes);
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(context, bob, '/upload');
    await page.setInputFiles('input[type="file"]', join(dir, 'shared.jar'));
    await page.locator('button.submit').click();

    const result = page.locator('.result');
    await result.waitFor();
    // As for any new jar: nothing tells that someone had these bytes before.
    expect(await result.innerText()).toContain('A new version was made');
    expect(await result.innerText()).not.toContain('before');
    expect(await result.innerText()).not.toContain(ada.name);
    expect(await violations(page)).toEqual([]);
    expect(problems.csp).toEqual([]);
    await context.close();
  });

  test('the second uploader creates a run of their version and it succeeds', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, bob, `/pipelines/${hash}?pipeline=demo-slow`);
    await page.getByRole('link', { name: 'Create run' }).click();
    await page.locator('#param-steps').fill('1');
    await page.locator('#param-delayMillis').fill('10');
    await page.locator('button.submit').click();
    await page.locator('.head .badge').waitFor();
    runId = new URL(page.url()).pathname.split('/').pop()!;
    await page.locator('.head .badge').filter({ hasText: 'SUCCEEDED' }).waitFor({ timeout: 40_000 });
    expect(await page.locator('dl.facts').innerText()).toContain(bob.name);
    await context.close();

    // The first uploader cannot see it, and the page says nothing of why.
    const hidden = await api(ada, `/api/v1/runs/${runId}`);
    expect(hidden.status).toBe(404);
  });

  test('the admin sees both versions in the list, each with its uploader, and binds a trigger to the second by naming whose', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, root, '/pipelines');
    const rows = page.locator('tbody tr').filter({ hasText: hash.slice(0, 7) });
    await rows.first().waitFor();
    expect(await rows.count()).toBe(2);
    expect((await rows.allInnerTexts()).join(' ')).toContain(ada.name);
    expect((await rows.allInnerTexts()).join(' ')).toContain(bob.name);

    // A bare link to the hash does not guess whose version is meant.
    await page.goto(`${engineUrl}/pipelines/${hash}?pipeline=demo-slow`);
    await page.locator('.choose-version').waitFor();
    expect(await page.locator('.choose-version a').allInnerTexts()).toEqual(
      expect.arrayContaining([ada.name, bob.name]),
    );

    await page.locator('.choose-version a', { hasText: bob.name }).click();
    await page.locator('section.admin').waitFor();
    await page.getByRole('link', { name: 'Bind a trigger' }).click();
    triggerName = `e2e-${Date.now().toString(36)}`;
    await page.locator('#trigger-name').fill(triggerName);
    await page.locator('#trigger-cron').fill('0 3 * * *');
    await page.locator('button.submit').click();
    await page.waitForURL(/\/triggers\/detail/);
    await page.locator('h1').filter({ hasText: triggerName }).waitFor();
    expect(await page.locator('section').filter({ hasText: 'Runs' }).first().innerText()).toContain(
      bob.name,
    );
    const trigger = await (await api(root, `/api/v1/triggers/${triggerName}`)).json();
    expect(trigger).toMatchObject({ contentHash: hash, uploader: bob.name });
    await context.close();
  });

  test('deleting the first uploader\'s version in the page leaves the second\'s, which still runs', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(
      context,
      root,
      `/pipelines/${hash}?pipeline=demo-slow&uploader=${encodeURIComponent(ada.name)}`,
    );
    await page.locator('section.admin').waitFor();
    await page.getByRole('button', { name: 'Delete this version' }).click();
    await page.getByRole('button', { name: 'Delete the version' }).click();
    await page.waitForURL(/\/pipelines$/);
    await context.close();

    expect((await api(ada, `/api/v1/artifacts/${hash}`)).status).toBe(404);
    expect((await api(bob, `/api/v1/artifacts/${hash}`)).status).toBe(200);
    const again = await api(bob, '/api/v1/runs', {
      method: 'POST',
      body: JSON.stringify({
        contentHash: hash,
        pipeline: 'demo-slow',
        parameters: { steps: '1', delayMillis: '10' },
      }),
    });
    expect(again.status).toBe(201);
  });
});

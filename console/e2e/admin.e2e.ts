// The pages of an admin in a real browser (Chrome), against a real packaged Engine with its
// PostgreSQL and the real sample jars (E2E_JARS): triggers (a cron one that really fires, a webhook
// whose secret is shown once), the allow-list (preview, apply, the verdicts that really change, the
// history), shared resources (a holder and a waiter of real runs, forced to let go), unsafe
// execution and deleting a version (409 in_use). What a test changes it puts back.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { mkdirSync } from 'node:fs';
import type { Browser, BrowserContext, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { uniqueJar } from '../test-support/zip';
import { launchChrome, newContext, settled, signInWith, violations, watch } from './browser';

const engineUrl = process.env.E2E_ENGINE_URL;
if (!engineUrl) throw new Error('E2E_ENGINE_URL is not set: the tests need a running Engine');
const tokens = process.env.E2E_TOKENS;
if (!tokens) throw new Error('E2E_TOKENS is not set: name:role:token of two developers and an admin');
const callers = parseCallers(tokens);
const ada = callers.filter((c) => c.role === 'developer')[0];
const root = callers.find((c) => c.role === 'admin')!;
const jars = process.env.E2E_JARS;
if (!jars) throw new Error('E2E_JARS is not set: the directory of the sample jars');
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
const stamp = () => Date.now().toString(36);

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
    headers: { Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/json', ...init.headers },
  });
const json = async (response: Response) => (await response.json()) as any;

async function putBytes(bytes: Uint8Array) {
  const response = await fetch(`${engineUrl}/api/v1/artifacts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${ada.token}`, 'Content-Type': 'application/octet-stream' },
    body: bytes as unknown as BodyInit,
  });
  return (await response.json()) as { contentHash: string };
}
const jarBytes = (name: string) => readFileSync(join(jars, `${name}.jar`));
const putJar = (name: string) => putBytes(jarBytes(name));
/** Another version of the same pipelines: other bytes. */
const putVariant = (name: string) => putBytes(uniqueJar(jarBytes(name), `e2e-${stamp()}`));

const webhook = (name: string, secret: string, delivery: string) =>
  fetch(`${engineUrl}/api/v1/webhooks/${name}`, {
    method: 'POST',
    headers: { 'X-Runline-Webhook-Secret': secret, 'X-Runline-Delivery-Id': delivery },
    body: '{}',
  });

const dialog = (page: Page) => page.getByRole('dialog');

describe('triggers', () => {
  test('a cron trigger is made in the form, says what it means, and really fires: the firing is on its page, with the run it made', async () => {
    const { contentHash } = await putJar('demo-slow');
    const name = `e2e-cron-${stamp()}`;
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(
      context,
      root,
      `/triggers/new?contentHash=${contentHash}&pipeline=demo-slow`,
    );

    await page.locator('#trigger-name').fill(name);
    await page.locator('#trigger-cron').fill('30 9 * * 1-5');
    await page.locator('.preview').getByText('At 09:30, Monday to Friday').waitFor();
    await page.getByRole('button', { name: 'Every minute' }).click();
    await page.locator('.preview').getByText('Every minute', { exact: true }).waitFor();
    await page.locator('#param-steps').fill('1');
    await page.locator('#param-delayMillis').fill('10');
    await shot(page, 'admin-trigger-form-en');
    await page.locator('button.submit').click();

    await page.waitForURL(/\/triggers\/detail\?name=/);
    await page.getByRole('heading', { name, level: 1 }).waitFor();
    expect(await page.locator('.words').innerText()).toBe('Every minute');
    // The scheduler fires it at the next minute: the page reads the firings by itself.
    const run = page.locator('section', { hasText: 'Firings' }).locator('tbody tr').first();
    await run.locator('.outcome').getByText('Run created').waitFor({ timeout: 100_000 });
    await shot(page, 'admin-trigger-detail-cron-en');
    await run.locator('a.run').click();
    await page.waitForURL(/\/runs\//);
    await page.locator('.head .badge').filter({ hasText: /SUCCEEDED|RUNNING/ }).waitFor({ timeout: 30_000 });
    expect(await page.locator('main').innerText()).toContain(name);
    expect(await page.locator('main').innerText()).toContain('TRIGGER');

    // delete it, after the question
    await page.goto(`${engineUrl}/triggers/detail?name=${name}`);
    await page.getByRole('button', { name: 'Delete' }).click();
    await shot(page, 'admin-trigger-delete-en');
    await dialog(page).getByRole('button', { name: 'Delete the trigger' }).click();
    await page.waitForURL(`${engineUrl}/triggers`);
    expect((await api(root, `/api/v1/triggers/${name}`)).status).toBe(404);
    expect(problems.csp).toEqual([]);
    expect(await violations(page)).toEqual([]);
    await context.close();
  }, 180_000);

  test('a webhook trigger shows its secret once, in a dialog that must be answered; the secret is nowhere afterwards; rotating shows a new one and ends the old one', async () => {
    const { contentHash } = await putJar('demo-slow');
    const name = `e2e.hook-${stamp()}`;
    const context = await newContext(browser, 'en-US');
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: engineUrl });
    const { page, problems } = await signedIn(
      context,
      root,
      `/triggers/new?contentHash=${contentHash}&pipeline=demo-slow`,
    );
    const requested: string[] = [];
    page.on('request', (request) => requested.push(request.url()));

    await page.locator('input[name="kind"][value="webhook"]').check();
    await page.locator('#trigger-name').fill(name);
    await page.locator('#param-steps').fill('1');
    await page.locator('#param-delayMillis').fill('10');
    await page.locator('button.submit').click();

    await dialog(page).waitFor();
    const secret = (await dialog(page).locator('.secret').innerText()).trim();
    expect(secret.length).toBeGreaterThan(30);
    await shot(page, 'admin-webhook-secret-en');
    // not closed by Escape, and not before it is said to be kept
    await page.keyboard.press('Escape');
    expect(await dialog(page).count()).toBe(1);
    expect(await dialog(page).getByRole('button', { name: 'Done' }).isDisabled()).toBe(true);
    await dialog(page).getByRole('button', { name: 'Copy the secret' }).click();
    await dialog(page).getByText('Copied').first().waitFor();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(secret);
    await dialog(page).locator('input[type="checkbox"]').check();
    await dialog(page).getByRole('button', { name: 'Done' }).click();
    await page.waitForURL(/\/triggers\/detail\?name=/);
    await page.getByRole('heading', { name, level: 1 }).waitFor();

    // the secret is nowhere: not on the page, not in the storage, not in an address, not after a reload
    const holdsSecret = () =>
      page.evaluate(
        (value) =>
          document.documentElement.innerHTML.includes(value) ||
          JSON.stringify({ ...sessionStorage, ...localStorage }).includes(value),
        secret,
      );
    expect(await holdsSecret()).toBe(false);
    expect(requested.filter((url) => url.includes(secret))).toEqual([]);
    expect(page.url()).not.toContain(secret);
    await page.reload();
    await page.getByRole('heading', { name, level: 1 }).waitFor();
    expect(await holdsSecret()).toBe(false);
    expect(await page.locator('pre').innerText()).toContain('X-Runline-Webhook-Secret: <secret>');
    await shot(page, 'admin-trigger-detail-webhook-en');

    // a delivery, and it is in the firings with its run; a repeat of the delivery is not another firing
    const delivery = `d-${stamp()}`;
    expect((await webhook(name, secret, delivery)).status).toBe(202);
    expect((await webhook(name, secret, delivery)).status).toBe(202);
    expect((await webhook(name, 'wrong', `d-${stamp()}`)).status).toBe(401);
    const rows = page.locator('section', { hasText: 'Firings' }).locator('tbody tr');
    await rows.first().locator('.outcome').getByText('Run created').waitFor({ timeout: 20_000 });
    expect(await rows.count()).toBe(1);
    expect(await rows.first().locator('.delivery').innerText()).toBe(delivery);

    // rotating: asked first; the new secret is shown once; the old one is refused from then on
    await page.getByRole('button', { name: 'Rotate the secret' }).click();
    await shot(page, 'admin-trigger-rotate-en');
    await dialog(page).getByText('old secret stops working at once').waitFor();
    await dialog(page).getByRole('button', { name: 'Rotate the secret' }).click();
    await dialog(page).locator('.secret').waitFor();
    const fresh = (await dialog(page).locator('.secret').innerText()).trim();
    expect(fresh).not.toBe(secret);
    await dialog(page).locator('input[type="checkbox"]').check();
    await dialog(page).getByRole('button', { name: 'Done' }).click();
    expect((await webhook(name, secret, `d-${stamp()}`)).status).toBe(401);
    expect((await webhook(name, fresh, `d-${stamp()}`)).status).toBe(202);
    expect(await page.evaluate((v) => document.documentElement.innerHTML.includes(v), fresh)).toBe(false);

    expect(problems.csp).toEqual([]);
    expect(problems.foreign).toEqual([]);
    expect(await violations(page)).toEqual([]);
    await api(root, `/api/v1/triggers/${name}`, { method: 'DELETE' });
    await context.close();
  }, 120_000);

  test('a delivery that makes no run is a refused firing, with its reason in words and the Engine\'s own words apart', async () => {
    const { contentHash } = await putJar('demo-unsafe');
    const name = `e2e-refused-${stamp()}`;
    const made = await json(
      await api(root, '/api/v1/triggers', {
        method: 'POST',
        body: JSON.stringify({ name, kind: 'webhook', contentHash, pipeline: 'demo-unsafe' }),
      }),
    );
    expect((await webhook(name, made.secret, `d-${stamp()}`)).status).toBe(202);
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, root, `/triggers/detail?name=${name}`);
    const row = page.locator('section', { hasText: 'Firings' }).locator('tbody tr').first();
    await row.locator('.outcome').getByText('Refused').waitFor({ timeout: 20_000 });
    expect(await row.locator('.reason').innerText()).toContain('UNSAFE');
    await row.locator('summary').click();
    expect(await row.locator('.detail').innerText()).toContain('unsafe');
    await shot(page, 'admin-trigger-refused-en');
    await api(root, `/api/v1/triggers/${name}`, { method: 'DELETE' });
    await context.close();
  });

  test('a developer is turned away from the admin pages, and the Engine refuses the call too', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, ada, '/triggers');
    await page.getByRole('heading', { name: 'Admins only' }).waitFor();
    expect(await page.locator('aside nav').innerText()).not.toContain('Allow-list');
    for (const path of ['/allowlist', '/resources', '/triggers/new', '/triggers/detail?name=x']) {
      await page.goto(`${engineUrl}${path}`);
      await page.getByRole('heading', { name: 'Admins only' }).waitFor();
    }
    expect((await api(ada, '/api/v1/triggers')).status).toBe(403);
    await shot(page, 'admin-only-developer-en');
    await context.close();
  });
});

describe('the allow-list', () => {
  test('an entry is added after its preview; the history says so; adding what exists or is covered is said in words', async () => {
    const className = `e2e.t${stamp()}.Thing`;
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(context, root, '/allowlist');
    const before = Number((await json(await api(root, '/api/v1/allowlist'))).version);

    await page.getByRole('button', { name: 'Add an entry' }).click();
    await dialog(page).locator('input[name="entry-kind"][value="class"]').check();
    await dialog(page).locator('#entry-name').fill(className);
    await shot(page, 'admin-allowlist-add-form-en');
    await dialog(page).getByRole('button', { name: 'Preview the effect' }).click();
    await dialog(page).getByText('Nothing has changed yet').waitFor();
    await dialog(page).getByText('No pipeline changes its verdict').waitFor();
    await shot(page, 'admin-allowlist-add-preview-en');
    expect(Number((await json(await api(root, '/api/v1/allowlist'))).version)).toBe(before);
    await dialog(page).getByRole('button', { name: 'Apply the change' }).click();
    await dialog(page).getByText(`version ${before + 1} is in force`).waitFor();
    await dialog(page).locator('footer').getByRole('button', { name: 'Close' }).click();

    await page.locator('.current .version').getByText(String(before + 1), { exact: true }).waitFor();
    expect(await page.locator('table.entries tbody tr', { hasText: className }).count()).toBe(1);
    expect(await page.locator('.history tbody tr').first().locator('.action').innerText()).toBe('Entry added');
    await shot(page, 'admin-allowlist-after-add-en');

    // the refusals, in words
    await page.getByRole('button', { name: 'Add an entry' }).click();
    await dialog(page).locator('#entry-name').fill('java.lang');
    await dialog(page).getByRole('button', { name: 'Preview the effect' }).click();
    await dialog(page).locator('.refusal').getByText('exists already').waitFor();
    await dialog(page).locator('#entry-name').fill('java.lang.invoke.Deep');
    await dialog(page).locator('input[name="entry-kind"][value="class"]').check();
    await dialog(page).getByRole('button', { name: 'Preview the effect' }).click();
    await dialog(page).locator('.refusal').getByText('already covers').waitFor();
    await shot(page, 'admin-allowlist-refused-en');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();

    // put it back: remove the entry, through its preview
    await page.locator('table.entries tbody tr', { hasText: className }).getByRole('button', { name: 'Remove' }).click();
    await dialog(page).getByRole('button', { name: 'Remove the entry' }).click();
    await dialog(page).locator('footer').getByRole('button', { name: 'Close' }).click();
    await page.locator('.current .version').getByText(String(before + 2), { exact: true }).waitFor();
    expect(problems.csp).toEqual([]);
    expect(await violations(page)).toEqual([]);
    await context.close();
  });

  test('removing an entry that pipelines need: the preview names what becomes UNSAFE and that permission is taken back; what really happens is what was said; putting it back makes them SAFE', async () => {
    const slow = await putJar('demo-slow');
    const failing = await putJar('demo-failing');
    const path = '/api/v1/allowlist/entries/class/java.io.PrintStream';
    if ((await api(root, path)).status === 404) {
      await api(root, '/api/v1/allowlist/entries', { method: 'POST', body: JSON.stringify({ kind: 'class', name: 'java.io.PrintStream' }) });
    }
    await api(root, `/api/v1/definitions/${failing.contentHash}/demo-failing/unsafe-execution`, {
      method: 'PUT',
      body: JSON.stringify({ allow: true }),
    });
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(context, root, '/allowlist');
    const verdictOf = async (contentHash: string) =>
      (await json(await api(root, `/api/v1/artifacts/${contentHash}`))).pipelines[0].verdict;
    let removed = false;
    try {
      await page.locator('table.entries tbody tr', { hasText: 'java.io.PrintStream' }).getByRole('button', { name: 'Remove' }).click();
      await dialog(page).getByText('Nothing has changed yet').waitFor();
      const said = Number(await dialog(page).locator('.stat.unsafe .n').innerText());
      expect(said).toBeGreaterThanOrEqual(2);
      const saidRevoked = Number(await dialog(page).locator('.stat.revoked .n').innerText());
      expect(saidRevoked).toBeGreaterThanOrEqual(1);
      await dialog(page).locator('li.change', { hasText: 'demo-slow' }).waitFor();
      await dialog(page).locator('li.change', { hasText: 'demo-failing' }).locator('.revoked').waitFor();
      await dialog(page).locator('.warning').getByText('taken back').waitFor();
      await shot(page, 'admin-allowlist-remove-preview-en');
      expect(await verdictOf(slow.contentHash)).toBe('SAFE');

      await dialog(page).getByRole('button', { name: 'Remove the entry' }).click();
      removed = true;
      await dialog(page).getByText('is in force').waitFor();
      await shot(page, 'admin-allowlist-remove-applied-en');
      // what was said is what happened
      expect(Number(await dialog(page).locator('.stat.unsafe .n').innerText())).toBe(said);
      expect(await verdictOf(slow.contentHash)).toBe('UNSAFE');
      expect(await verdictOf(failing.contentHash)).toBe('UNSAFE');
      const definition = (await json(await api(root, `/api/v1/artifacts/${failing.contentHash}`))).pipelines[0];
      expect(definition.allowUnsafeExecution).toBe(false);
      await dialog(page).locator('footer').getByRole('button', { name: 'Close' }).click();
      const latest = page.locator('.history tbody tr').first();
      await latest.locator('.action').getByText('Entry removed').waitFor();
      await expect.poll(() => latest.locator('.unsafe').innerText()).toBe(String(said));

      // the page of the pipeline says why
      await page.goto(`${engineUrl}/pipelines/${slow.contentHash}?pipeline=demo-slow`);
      await page.locator('.badge').filter({ hasText: 'UNSAFE' }).first().waitFor();
      expect(await page.locator('.reasons').innerText()).toContain('java.io.PrintStream');
      await shot(page, 'admin-pipeline-now-unsafe-en');
    } finally {
      if (removed) {
        await page.goto(`${engineUrl}/allowlist`);
        await page.getByRole('button', { name: 'Add an entry' }).click();
        await dialog(page).locator('input[name="entry-kind"][value="class"]').check();
        await dialog(page).locator('#entry-name').fill('java.io.PrintStream');
        await dialog(page).getByRole('button', { name: 'Preview the effect' }).click();
        await dialog(page).locator('.stat.safe .n').waitFor();
        expect(Number(await dialog(page).locator('.stat.safe .n').innerText())).toBeGreaterThanOrEqual(2);
        await dialog(page).getByRole('button', { name: 'Apply the change' }).click();
        await dialog(page).getByText('is in force').waitFor();
        await dialog(page).locator('footer').getByRole('button', { name: 'Close' }).click();
        expect(await verdictOf(slow.contentHash)).toBe('SAFE');
        await page.locator('.history tbody tr').first().locator('.action').getByText('Entry added').waitFor();
      }
    }
    expect(problems.csp).toEqual([]);
    await context.close();
  });

  test('judging again is previewed first and makes no version', async () => {
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, root, '/allowlist');
    const before = (await json(await api(root, '/api/v1/allowlist'))).version;
    await page.getByRole('button', { name: 'Judge every pipeline again' }).click();
    await dialog(page).getByText('Nothing has changed yet').waitFor();
    await dialog(page).getByRole('button', { name: 'Judge again' }).click();
    await dialog(page).getByText(`version ${before} is in force`).waitFor();
    await dialog(page).locator('footer').getByRole('button', { name: 'Close' }).click();
    expect((await json(await api(root, '/api/v1/allowlist'))).version).toBe(before);
    await context.close();
  });
});

describe('shared resources', () => {
  test('two real runs of a pipeline that needs one resource: one holds it, one waits; the holder is made to let go after a question, and the waiter holds it while the first run goes on', async () => {
    const { contentHash } = await putJar('demo-resource');
    const there = await api(root, '/api/v1/resources/demo-printer');
    if (there.status === 404) {
      await api(root, '/api/v1/resources', { method: 'POST', body: JSON.stringify({ name: 'demo-printer', capacity: 1 }) });
    } else {
      await api(root, '/api/v1/resources/demo-printer', { method: 'PATCH', body: JSON.stringify({ capacity: 1, enabled: true }) });
    }
    const start = async () =>
      (await json(await api(ada, '/api/v1/runs', { method: 'POST', body: JSON.stringify({ contentHash, pipeline: 'demo-resource' }) }))).runId as string;
    const context = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(context, root, '/resources');
    const first = await start();
    const second = await start();
    try {
      const card = page.locator('.resource', { hasText: 'demo-printer' });
      await card.locator('.holders li a').first().waitFor({ timeout: 15_000 });
      await card.locator('.waiters li').first().waitFor({ timeout: 15_000 });
      expect(await card.locator('.usage').innerText()).toContain('1 of 1');
      expect(await card.locator('.holders li a').first().getAttribute('href')).toBe(`/runs/${first}`);
      expect(await card.locator('.waiters li a').first().getAttribute('href')).toBe(`/runs/${second}`);
      await shot(page, 'admin-resources-en');

      await card.getByRole('button', { name: 'Release' }).click();
      await dialog(page).getByText('not stopped').waitFor();
      await shot(page, 'admin-resources-release-en');
      await dialog(page).getByRole('button', { name: 'Release the resource' }).click();
      await card.locator('.holders li a').first().waitFor();
      await page.waitForFunction(
        (id) => document.querySelector('.resource .holders li a')?.getAttribute('href') === `/runs/${id}`,
        second,
        { timeout: 15_000 },
      );
      expect(await card.locator('.waiters li').count()).toBe(0);
      const stillRunning = (await json(await api(ada, `/api/v1/runs/${first}`))).state;
      expect(['RUNNING', 'SUCCEEDED']).toContain(stillRunning);
      await shot(page, 'admin-resources-released-en');
    } finally {
      for (const id of [first, second]) await api(ada, `/api/v1/runs/${id}/cancel`, { method: 'POST' });
    }
    expect(problems.csp).toEqual([]);
    await context.close();
  }, 90_000);

  test('a resource is defined in the dialog and changed: its capacity, and turning it off says what that does', async () => {
    const name = `e2e-res-${stamp()}`;
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, root, '/resources');
    await page.getByRole('button', { name: 'Define a resource' }).first().click();
    await dialog(page).locator('#resource-name').fill(name);
    await dialog(page).locator('#resource-capacity').fill('0');
    await dialog(page).getByRole('button', { name: 'Define' }).click();
    await dialog(page).getByText('The capacity must be a whole number of 1 or more.').waitFor();
    await dialog(page).locator('#resource-capacity').fill('2');
    await dialog(page).getByRole('button', { name: 'Define' }).click();
    const card = page.locator('.resource', { hasText: name });
    await card.waitFor();
    expect(await card.locator('.usage').innerText()).toContain('0 of 2');

    await card.getByRole('button', { name: 'Change' }).click();
    await dialog(page).getByText('does not take the resource back').waitFor();
    await dialog(page).locator('#resource-capacity').fill('3');
    await dialog(page).locator('#resource-enabled').uncheck();
    await shot(page, 'admin-resource-change-en');
    await dialog(page).getByRole('button', { name: 'Save' }).click();
    await card.locator('.state').getByText('Disabled').waitFor();
    expect(await card.locator('.usage').innerText()).toContain('0 of 3');
    await context.close();
  });
});

describe('unsafe execution and deleting a version', () => {
  test('an admin allows an UNSAFE pipeline after a question, a developer then makes a run of it in the page and it ends; the version is then in use and cannot be deleted, and the page says by what', async () => {
    const { contentHash } = await putVariant('demo-unsafe');
    const refused = await api(ada, '/api/v1/runs', { method: 'POST', body: JSON.stringify({ contentHash, pipeline: 'demo-unsafe' }) });
    expect(refused.status).toBe(409);
    const admin = await newContext(browser, 'en-US');
    const { page, problems } = await signedIn(admin, root, `/pipelines/${contentHash}?pipeline=demo-unsafe`);
    const toggle = page.locator('section.admin input[role="switch"]');
    await toggle.waitFor();
    await toggle.click();
    await dialog(page).getByText('not checked').waitFor();
    await shot(page, 'admin-unsafe-confirm-en');
    await dialog(page).getByRole('button', { name: 'Allow unsafe execution' }).click();
    await page.locator('section.admin .state').getByText('Allowed').waitFor();
    await page.locator('section.admin .set-by').getByText('root').waitFor();
    await shot(page, 'admin-unsafe-allowed-en');

    const developer = await newContext(browser, 'en-US');
    const dev = await signedIn(developer, ada, `/runs/new?contentHash=${contentHash}&pipeline=demo-unsafe`);
    await dev.page.locator('button.submit').click();
    await dev.page.waitForURL(/\/runs\/[0-9a-f-]{36}$/);
    await dev.page.locator('.head .badge').filter({ hasText: /SUCCEEDED|FAILED/ }).waitFor({ timeout: 40_000 });
    expect(await dev.page.locator('main').innerText()).toContain('root');
    await shot(dev.page, 'admin-unsafe-run-en');

    await page.getByRole('button', { name: 'Delete this version' }).click();
    await dialog(page).getByText('cannot be undone').waitFor();
    await dialog(page).getByRole('button', { name: 'Delete the version' }).click();
    await dialog(page).locator('[role="alert"]').getByText('in use').waitFor();
    await dialog(page).locator('.in-use').getByText('1 run').waitFor();
    await shot(page, 'admin-delete-in-use-en');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    expect((await api(root, `/api/v1/artifacts/${contentHash}`)).status).toBe(200);

    await toggle.click();
    await page.locator('section.admin .state').getByText('Not allowed').waitFor();
    expect(problems.csp).toEqual([]);
    expect(await violations(page)).toEqual([]);
    await admin.close();
    await developer.close();
  }, 120_000);

  test('a version that nothing refers to is deleted after the question, and then it is gone', async () => {
    const { contentHash } = await putVariant('demo-failing');
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, root, `/pipelines/${contentHash}?pipeline=demo-failing`);
    await page.getByRole('button', { name: 'Delete this version' }).click();
    await dialog(page).getByRole('button', { name: 'Delete the version' }).click();
    await page.waitForURL(`${engineUrl}/pipelines`);
    expect((await api(root, `/api/v1/artifacts/${contentHash}`)).status).toBe(404);
    await context.close();
  });

  test('a version that a trigger is bound to names the trigger, with a link, until the trigger is deleted', async () => {
    const { contentHash } = await putVariant('demo-failing');
    const name = `e2e-bound-${stamp()}`;
    await api(root, '/api/v1/triggers', { method: 'POST', body: JSON.stringify({ name, kind: 'webhook', contentHash, pipeline: 'demo-failing' }) });
    const context = await newContext(browser, 'en-US');
    const { page } = await signedIn(context, root, `/pipelines/${contentHash}?pipeline=demo-failing`);
    await page.getByRole('button', { name: 'Delete this version' }).click();
    await dialog(page).getByRole('button', { name: 'Delete the version' }).click();
    const link = dialog(page).locator('.in-use a');
    await link.waitFor();
    expect(await link.innerText()).toBe(name);
    await api(root, `/api/v1/triggers/${name}`, { method: 'DELETE' });
    await dialog(page).getByRole('button', { name: 'Delete the version' }).click();
    await page.waitForURL(`${engineUrl}/pipelines`);
    await context.close();
  });
});

describe('zh-TW', () => {
  test('the admin pages speak Traditional Chinese', async () => {
    const context = await newContext(browser, 'zh-TW', ['zh-TW']);
    const { page } = await signedIn(context, root, '/triggers');
    await page.getByRole('heading', { name: /觸發器/ }).waitFor();
    await shot(page, 'admin-triggers-zh');
    await page.goto(`${engineUrl}/allowlist`);
    await page.getByRole('heading', { name: /白名單/ }).waitFor();
    await page.getByRole('button', { name: '新增條目' }).click();
    await dialog(page).getByText('信任').first().waitFor();
    await shot(page, 'admin-allowlist-dialog-zh');
    await page.goto(`${engineUrl}/resources`);
    await page.getByRole('heading', { name: /共享資源/ }).waitFor();
    await shot(page, 'admin-resources-zh');
    await context.close();
  });
});

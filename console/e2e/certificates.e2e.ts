// The certificates of WI-52 in a real browser (Chrome), against a real packaged Engine with its
// PostgreSQL and a real PKCS12 keystore made by keytool, and an `openai-compatible` service behind
// real TLS that requires a client certificate: the Fake of the Engine's tests run as a process of
// its own (`./gradlew :accessors:fakeOpenAiServer` with FAKE_OPENAI_TLS_*). The keystore's
// certificates are shown with what may be shown of them; a resource is defined with its form,
// trusting the keystore's authority and presenting its client certificate, and its check passes by
// a real handshake; one without the client certificate fails, said in words on its card and the
// same as the API's last check. Nothing of a private key, and not the keystore's password, is in any
// answer the browser received, in the DOM, the storage or the cookies.
//
// Needs, besides E2E_ENGINE_URL and E2E_TOKENS (an admin in it):
// - the keystore holds `internal-ca` (the authority that issued the service's certificate and the
//   client certificate), `app-client` (the client certificate's private key entry) and
//   `soon-client` (a private key entry whose certificate ends within the warning threshold);
// - E2E_TLS_OPENAI_URL: the base address the Fake printed (https://localhost:<port>/v1);
// - E2E_PRIVATE_KEY_MARKERS: the base64 of the private keys of `app-client` and `soon-client`
//   (PKCS#8), separated by commas, and E2E_KEYSTORE_PASSWORD: what must be in no answer.

import type { Browser, BrowserContext, Page } from 'playwright-core';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseCallers } from '../contract/system-contract';
import { launchChrome, newContext, settled, signInWith, watch } from './browser';

const need = (name: string, why: string) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set: ${why}`);
  return value;
};
const engineUrl = need('E2E_ENGINE_URL', 'the tests need a running Engine');
const callers = parseCallers(need('E2E_TOKENS', 'name:role:token of an admin at least'));
const root = callers.find((c) => c.role === 'admin')!;
const serviceUrl = need('E2E_TLS_OPENAI_URL', 'the https base address of the Fake that requires a client certificate');
const keyMarkers = need('E2E_PRIVATE_KEY_MARKERS', 'the base64 of the private keys in the keystore').split(',');
const keystorePassword = need('E2E_KEYSTORE_PASSWORD', 'the password of the keystore');
const screenshots = process.env.E2E_SCREENSHOTS;

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
const dialog = (page: Page) => page.getByRole('dialog');
const cardOf = (page: Page, name: string) =>
  page.locator('.resource', { has: page.locator('h2.name', { hasText: new RegExp(`^${name}$`) }) });
const api = (path: string, init: RequestInit = {}) =>
  fetch(`${engineUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${root.token}`, 'Content-Type': 'application/json', ...init.headers },
  });
const resource = async (name: string) => (await (await api(`/api/v1/resources/${name}`)).json()) as any;
const remove = (name: string) => api(`/api/v1/resources/${name}`, { method: 'DELETE' });

/** Every answer the pages received, to look for what must never be in one. */
class Bodies {
  readonly all: string[] = [];
  watch(page: Page) {
    page.on('response', async (response) => {
      try {
        this.all.push(`${response.url()}\n${JSON.stringify(response.headers())}\n${await response.text()}`);
      } catch {
        // An answer without a body to read.
      }
    });
  }
}

async function signedIn(context: BrowserContext, bodies: Bodies, path: string) {
  const page = await context.newPage();
  bodies.watch(page);
  const problems = await watch(page, engineUrl);
  await page.goto(`${engineUrl}${path}`);
  if ((await settled(page)) === 'sign-in') await signInWith(page, root.token);
  await page.locator('aside nav').waitFor();
  return { page, problems };
}

async function defineService(page: Page, name: string, client: string | null) {
  await page.getByRole('button', { name: 'Define a resource' }).first().click();
  await dialog(page).locator('#resource-type').selectOption({ label: 'OpenAI-compatible service' });
  await dialog(page).locator('#resource-name').fill(name);
  await dialog(page).locator('#openai-base-url').fill(serviceUrl);
  const trust = dialog(page).locator('#resource-certificates input[type="checkbox"][value="internal-ca"]');
  await trust.waitFor();
  await trust.check();
  if (client !== null) await dialog(page).locator('#resource-client-cert').selectOption(client);
  await dialog(page).getByRole('button', { name: 'Define' }).click();
  await dialog(page).waitFor({ state: 'detached' });
  await cardOf(page, name).waitFor();
}

async function check(page: Page, name: string, outcome: 'passed' | 'failed') {
  await cardOf(page, name).getByRole('button', { name: 'Check' }).click();
  await cardOf(page, name).locator(`.last-check .outcome.${outcome}`).waitFor({ timeout: 30_000 });
  return (await resource(name)).lastCheck;
}

describe('the certificates of resources in a real browser (WI-52)', () => {
  test('the keystore shows each certificate, a service is checked over mTLS through the form, and no key is anywhere', async () => {
    const context = await newContext(browser, 'en-US');
    const bodies = new Bodies();
    const withClient = `tls-llm-${stamp()}`;
    const withoutClient = `tls-llm-bare-${stamp()}`;
    try {
      const { page, problems } = await signedIn(context, bodies, '/resources');

      // The keystore: each certificate with its subject, end, days left and fingerprint.
      const list = page.locator('[data-certificates-of="app-client"] li');
      await list.first().waitFor();
      expect(await list.count()).toBe(2);
      expect(await list.first().locator('.fingerprint').innerText()).toMatch(/SHA-256 ([0-9A-F]{2}:){31}[0-9A-F]{2}/);
      expect(await page.locator('[data-certificates-of="soon-client"] li.expiring .warning').count()).toBe(1);
      const listed = (await (await api('/api/v1/secrets')).json()).secrets as any[];
      expect(await list.first().locator('.subject').innerText()).toBe(
        listed.find((s) => s.alias === 'app-client').certificates[0].subject,
      );
      await shot(page, 'wi52-keystore-certificates-en');

      // A service that requires a client certificate: with the keystore's, the check passes.
      await defineService(page, withClient, 'app-client');
      expect((await resource(withClient)).settings).toMatchObject({ trustAliases: ['internal-ca'], clientCertAlias: 'app-client' });
      expect(await check(page, withClient, 'passed')).toMatchObject({ ok: true, failure: null });

      // Without it, the service refuses, and the card says so in words.
      await defineService(page, withoutClient, null);
      const failed = await check(page, withoutClient, 'failed');
      expect(failed).toMatchObject({ ok: false, failure: 'client_cert_rejected' });
      expect(await cardOf(page, withoutClient).locator('.last-check').innerText()).toContain('asked for a client certificate');
      await shot(page, 'wi52-checks-en');

      // Changing it: the client certificate is chosen, and the check passes.
      await cardOf(page, withoutClient).getByRole('button', { name: 'Change' }).click();
      await dialog(page).locator('#resource-client-cert option[value="app-client"]').waitFor({ state: 'attached' });
      await dialog(page).locator('#resource-client-cert').selectOption('app-client');
      await dialog(page).getByRole('button', { name: 'Save' }).click();
      await dialog(page).waitFor({ state: 'detached' });
      expect(await check(page, withoutClient, 'passed')).toMatchObject({ ok: true });

      // zh-TW speaks of certificates too.
      const zh = await newContext(browser, 'zh-TW', ['zh-TW']);
      const { page: zhPage } = await signedIn(zh, bodies, '/resources');
      await zhPage.locator('[data-certificates-of="app-client"]').waitFor();
      expect(await zhPage.locator('section.secrets').innerText()).toContain('憑證');
      await shot(zhPage, 'wi52-keystore-certificates-zh-TW');
      await zh.close();

      expect(problems.csp).toEqual([]);
      expect(problems.foreign).toEqual([]);
      const held = await page.evaluate(() => ({
        dom: document.documentElement.outerHTML,
        local: JSON.stringify({ ...localStorage }),
        session: JSON.stringify({ ...sessionStorage }),
      }));
      for (const secret of [...keyMarkers.map((m) => m.slice(0, 40)), keystorePassword]) {
        expect(bodies.all.filter((body) => body.includes(secret))).toEqual([]);
        expect(held.dom).not.toContain(secret);
        expect(held.local + held.session).not.toContain(secret);
        expect(JSON.stringify(await context.cookies())).not.toContain(secret);
      }
    } finally {
      await remove(withClient);
      await remove(withoutClient);
      await context.close();
    }
  });
});

import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import WebhookUsage from './WebhookUsage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const show = async (props: { webhookPath: string; secret?: string }, languages = ['en']) => {
  app = await createTestApp({ languages });
  return app.mount(WebhookUsage, props);
};

describe('how to call a webhook', () => {
  test('gives the address on the Engine, which is where the Console is, with the method', async () => {
    const view = await show({ webhookPath: '/api/v1/webhooks/hook.one' });
    expect(view.querySelector('.url')!.textContent).toBe(`${location.origin}/api/v1/webhooks/hook.one`);
    expect(view.textContent).toContain('POST');
  });

  test('names the two headers that go with it, and says what each is', async () => {
    const view = await show({ webhookPath: '/api/v1/webhooks/x' });
    const names = [...view.querySelectorAll('dt')].map((dt) => dt.textContent);
    expect(names).toEqual(['X-Runline-Webhook-Secret', 'X-Runline-Delivery-Id']);
    expect(view.textContent).toContain('delivery');
  });

  test('has an example that a person can run, with a place for the secret when it is not known', async () => {
    const view = await show({ webhookPath: '/api/v1/webhooks/x' });
    const example = view.querySelector('pre')!.textContent!;
    expect(example).toContain(`curl -X POST '${location.origin}/api/v1/webhooks/x'`);
    expect(example).toContain("X-Runline-Webhook-Secret: <secret>");
    expect(example).toContain('X-Runline-Delivery-Id');
  });

  test('has the secret in the example when it has just been given', async () => {
    const view = await show({ webhookPath: '/api/v1/webhooks/x', secret: 'S3cr3t-value' });
    expect(view.querySelector('pre')!.textContent).toContain('X-Runline-Webhook-Secret: S3cr3t-value');
  });

  test('speaks the language of the screen', async () => {
    const view = await show({ webhookPath: '/api/v1/webhooks/x' }, ['zh-TW']);
    expect(view.textContent).toContain('範例');
  });
});

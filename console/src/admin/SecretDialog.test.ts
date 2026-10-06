import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import SecretDialog from './SecretDialog.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const SECRET = 'q9X-the-secret_VALUE-0123456789abcdefghij';
const show = async (props: { rotated?: boolean } = {}, languages = ['en']) => {
  const onclose = vi.fn();
  app = await createTestApp({ languages });
  const view = app.mount(SecretDialog, {
    name: 'hook.one',
    webhookPath: '/api/v1/webhooks/hook.one',
    secret: SECRET,
    onclose,
    ...props,
  });
  return { view, onclose };
};
const done = (view: HTMLElement) =>
  [...view.querySelectorAll<HTMLButtonElement>('footer button')].find((b) => b.textContent!.trim() === 'Done')!;
const saved = (view: HTMLElement) => view.querySelector<HTMLInputElement>('input[type="checkbox"]')!;

describe('the dialog of a new webhook secret', () => {
  test('shows the secret as text, says it is shown only once, and gives the example with it', async () => {
    const { view } = await show();

    expect(view.querySelector('[role="dialog"] h2')!.textContent).toBe('Webhook secret of hook.one');
    expect(view.querySelector('.secret')!.textContent).toBe(SECRET);
    expect(view.textContent).toContain('only time');
    expect(view.querySelector('pre')!.textContent).toContain(`X-Runline-Webhook-Secret: ${SECRET}`);
    expect(view.textContent).toContain(`${location.origin}/api/v1/webhooks/hook.one`);
  });

  test('cannot be closed until the person says the secret is kept: no Escape, no close button, Done is off', async () => {
    const { view, onclose } = await show();

    view.querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(view.querySelector('button[aria-label="Close"]')).toBeNull();
    expect(done(view).disabled).toBe(true);
    done(view).click();
    expect(onclose).not.toHaveBeenCalled();

    saved(view).click();
    await tick();
    expect(done(view).disabled).toBe(false);
    done(view).click();
    expect(onclose).toHaveBeenCalledOnce();
  });

  test('says that the old secret no longer works when it is a rotation', async () => {
    const { view } = await show({ rotated: true });
    expect(view.querySelector('h2')!.textContent).toBe('New webhook secret of hook.one');
    expect(view.textContent).toContain('old secret no longer works');
  });

  test('does not keep the secret anywhere: not in the storage of the tab or of the browser, not in the address, and not on the page once it is gone', async () => {
    const { view } = await show();
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(SECRET);
    expect(JSON.stringify({ ...localStorage })).not.toContain(SECRET);
    expect(location.href).not.toContain(SECRET);
    expect(document.title).not.toContain(SECRET);

    await app.dispose();
    expect(document.body.innerHTML).not.toContain(SECRET);
    void view;
    app = await createTestApp();
  });

  test('speaks the language of the screen', async () => {
    const { view } = await show({}, ['zh-TW']);
    expect(view.querySelector('h2')!.textContent).toContain('hook.one');
    expect(view.textContent).toContain('只會顯示這一次');
  });

  test('reads the secret as text, whatever it holds', async () => {
    const { view } = await show();
    expect(view.querySelector('.secret')!.querySelector('*:not(span)')).toBeNull();
  });
});

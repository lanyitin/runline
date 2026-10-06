import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import DialogHarness from '../../test-support/DialogHarness.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const open = async (props: { dismissible?: boolean; onclose?: () => void } = {}, languages = ['en']) => {
  app = await createTestApp({ languages });
  const view = app.mount(DialogHarness, props);
  const opener = view.querySelector<HTMLButtonElement>('#opener')!;
  opener.focus();
  opener.click();
  await tick();
  return { view, opener, dialog: () => view.querySelector<HTMLElement>('[role="dialog"]') };
};
const press = (target: Element, key: string, shiftKey = false) =>
  target.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }));

describe('a dialog', () => {
  test('is labelled by its title and is modal', async () => {
    const { dialog } = await open();

    expect(dialog()!.getAttribute('aria-modal')).toBe('true');
    const title = document.getElementById(dialog()!.getAttribute('aria-labelledby')!)!;
    expect(title.textContent).toBe('Delete it');
    expect(dialog()!.textContent).toContain('The text of the dialog.');
  });

  test('takes the keyboard when it opens: the first thing in it that can be used', async () => {
    const { dialog } = await open();
    expect(dialog()!.contains(document.activeElement)).toBe(true);
    expect(document.activeElement!.getAttribute('aria-label')).toBe('Close');
  });

  test('keeps the keyboard inside: Tab from the last thing goes to the first, Shift+Tab from the first to the last', async () => {
    const { dialog } = await open();
    const first = document.activeElement as HTMLElement;
    const last = dialog()!.querySelector<HTMLElement>('#last')!;

    last.focus();
    press(last, 'Tab');
    expect(document.activeElement).toBe(first);

    press(first, 'Tab', true);
    expect(document.activeElement).toBe(last);
  });

  test('is closed by Escape, and the keyboard goes back to where it was', async () => {
    const onclose = vi.fn();
    const { dialog, opener } = await open({ onclose });

    press(dialog()!, 'Escape');
    await tick();

    expect(onclose).toHaveBeenCalledOnce();
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  test('is closed by its close button', async () => {
    const onclose = vi.fn();
    const { dialog } = await open({ onclose });
    dialog()!.querySelector<HTMLButtonElement>('button[aria-label="Close"]')!.click();
    expect(onclose).toHaveBeenCalledOnce();
  });

  test('that must be answered has no way out but its own buttons: no Escape, no close button', async () => {
    const onclose = vi.fn();
    const { dialog } = await open({ dismissible: false, onclose });

    press(dialog()!, 'Escape');

    expect(onclose).not.toHaveBeenCalled();
    expect(dialog()!.querySelector('button[aria-label="Close"]')).toBeNull();
    expect(dialog()!.contains(document.activeElement)).toBe(true);
  });

  test('stops the page behind it from scrolling for as long as it is open', async () => {
    const { dialog } = await open();
    expect(document.body.classList.contains('rl-modal-open')).toBe(true);
    press(dialog()!, 'Escape');
    await tick();
    expect(document.body.classList.contains('rl-modal-open')).toBe(false);
  });

  test('speaks the language of the screen', async () => {
    const { dialog } = await open({}, ['zh-TW']);
    expect(dialog()!.querySelector('button[aria-label="關閉"]')).not.toBeNull();
  });
});

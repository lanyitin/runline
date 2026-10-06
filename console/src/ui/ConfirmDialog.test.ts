import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import ConfirmHarness from '../../test-support/ConfirmHarness.svelte';
import { ApiFailure } from '../api/failure';

let app: TestApp;
afterEach(() => app.dispose());

const show = async (props: { danger?: boolean; busy?: boolean; failure?: ApiFailure | null } = {}) => {
  const onconfirm = vi.fn();
  const oncancel = vi.fn();
  app = await createTestApp({ languages: ['en'] });
  const view = app.mount(ConfirmHarness, { ...props, onconfirm, oncancel });
  const button = (label: string) =>
    [...view.querySelectorAll<HTMLButtonElement>('footer button')].find((b) => b.textContent!.trim() === label)!;
  return { view, onconfirm, oncancel, button };
};

describe('a confirmation', () => {
  test('says what is asked, and does it only when it is confirmed', async () => {
    const { view, button, onconfirm, oncancel } = await show();

    expect(view.querySelector('[role="dialog"] h2')!.textContent).toBe('Delete the version?');
    expect(view.textContent).toContain('It cannot be undone.');
    expect(onconfirm).not.toHaveBeenCalled();

    button('Delete it').click();
    expect(onconfirm).toHaveBeenCalledOnce();
    expect(oncancel).not.toHaveBeenCalled();
  });

  test('can be refused: Cancel, the close button and Escape', async () => {
    const { view, button, oncancel } = await show();
    button('Cancel').click();
    view.querySelector<HTMLButtonElement>('button[aria-label="Close"]')!.click();
    view.querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(oncancel).toHaveBeenCalledTimes(3);
  });

  test('starts with the keyboard on Cancel, so that Enter does not do what cannot be undone', async () => {
    const { button } = await show({ danger: true });
    expect(document.activeElement).toBe(button('Cancel'));
  });

  test('a dangerous one has the button of a dangerous act', async () => {
    const { button } = await show({ danger: true });
    expect(button('Delete it').classList.contains('danger')).toBe(true);
    expect(button('Delete it').classList.contains('solid')).toBe(true);
  });

  test('while it is busy it cannot be answered again or left', async () => {
    const { view, button, onconfirm, oncancel } = await show({ busy: true });

    expect(button('Delete it').disabled).toBe(true);
    expect(button('Cancel').disabled).toBe(true);
    expect(view.querySelector('button[aria-label="Close"]')).toBeNull();
    view.querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(onconfirm).not.toHaveBeenCalled();
    expect(oncancel).not.toHaveBeenCalled();
  });

  test('says what the Engine refused, in words, and stays open', async () => {
    const { view } = await show({ failure: new ApiFailure(409, { error: 'in_use', message: 'x' }) });

    expect(view.querySelector('[role="alert"]')!.textContent).toContain('in use');
    expect(view.querySelector('[role="dialog"]')).not.toBeNull();
  });
});

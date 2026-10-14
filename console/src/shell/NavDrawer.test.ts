import { tick } from 'svelte';
import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import App from '../App.svelte';

// The navigation on a narrow screen (WI-61): the sidebar is a drawer that the menu button of the top
// bar opens. Which of the two layouts is in force is the business of the style sheet (the one
// breakpoint, styles/responsive.css); these tests are about what the drawer does whichever it is:
// its names and states, where the keyboard is, and what closes it.

let app: TestApp;
afterEach(() => app.dispose());

const developer = { name: 'Ada', role: 'developer' as const };

const open = async (languages = ['en'], path = '/') => {
  app = await createTestApp({ identity: developer, path, languages });
  const view = app.mount(App);
  await tick();
  const menu = view.querySelector<HTMLButtonElement>('header button.menu')!;
  return { view, menu, drawer: () => view.querySelector<HTMLElement>('aside')! };
};
const press = (target: Element, key: string, shiftKey = false) =>
  target.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }));

describe('the menu button of the top bar', () => {
  test('has a name and says that the drawer is closed; it controls the sidebar', async () => {
    const { menu, drawer } = await open();

    expect(menu.getAttribute('aria-label')).toBe('Menu');
    expect(menu.getAttribute('aria-expanded')).toBe('false');
    expect(menu.getAttribute('aria-controls')).toBe(drawer().id);
    expect(drawer().id).not.toBe('');
  });

  test('has its name in Chinese too', async () => {
    const { menu } = await open(['zh-TW']);
    expect(menu.getAttribute('aria-label')).toBe('選單');
  });

  test('opens the drawer: it says so, and the keyboard goes into the navigation', async () => {
    const { menu, drawer } = await open();

    menu.click();
    await tick();

    expect(menu.getAttribute('aria-expanded')).toBe('true');
    expect(drawer().dataset.open).toBe('true');
    expect(drawer().getAttribute('aria-label')).toBe('Navigation menu');
    expect(drawer().contains(document.activeElement)).toBe(true);
  });

  test('closes the drawer again when pressed a second time', async () => {
    const { menu, drawer } = await open();
    menu.click();
    await tick();

    menu.click();
    await tick();

    expect(menu.getAttribute('aria-expanded')).toBe('false');
    expect(drawer().dataset.open).toBeUndefined();
  });
});

describe('the drawer', () => {
  test('is closed by Escape, and the keyboard goes back to the menu button', async () => {
    const { menu, drawer } = await open();
    menu.click();
    await tick();

    press(drawer(), 'Escape');
    await tick();

    expect(menu.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(menu);
  });

  test('is closed by a click on the scrim, and the keyboard goes back to the menu button', async () => {
    const { view, menu } = await open();
    menu.click();
    await tick();

    view.querySelector<HTMLElement>('.rl-nav-scrim')!.click();
    await tick();

    expect(menu.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(menu);
  });

  test('is closed by the choice of a page, and the page is the one chosen', async () => {
    const { view, menu } = await open();
    menu.click();
    await tick();

    view.querySelector<HTMLAnchorElement>('aside a[href="/runs"]')!.click();
    await tick();

    expect(app.context.router.path).toBe('/runs');
    expect(menu.getAttribute('aria-expanded')).toBe('false');
  });

  test('keeps the keyboard inside while it is open: Tab goes round, and so does Shift+Tab', async () => {
    const { menu, drawer } = await open();
    menu.click();
    await tick();
    const items = [...drawer().querySelectorAll<HTMLElement>('a[href], button')];
    const first = items[0];
    const last = items[items.length - 1];

    last.focus();
    press(last, 'Tab');
    expect(document.activeElement).toBe(first);

    first.focus();
    press(first, 'Tab', true);
    expect(document.activeElement).toBe(last);
  });

  test('is not a dialog while it is closed: the wide screen keeps its plain sidebar', async () => {
    const { drawer } = await open();
    expect(drawer().getAttribute('role')).toBeNull();
    expect(drawer().getAttribute('aria-modal')).toBeNull();
  });

  test('is a modal dialog while it is open', async () => {
    const { menu, drawer } = await open();
    menu.click();
    await tick();
    expect(drawer().getAttribute('role')).toBe('dialog');
    expect(drawer().getAttribute('aria-modal')).toBe('true');
  });
});

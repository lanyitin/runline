// What the real-browser tests of the narrow layout share beyond browser.ts (WI-63): the check of what
// is touched on a page, and of a dialog on a phone. They report what is wrong as a list of words, so
// that a failing test says which control and how.

import type { Page } from 'playwright-core';
import { expect } from 'vitest';
import { horizontalOverflow } from './browser';

export type Viewport = { width: number; height: number };

/**
 * What is wrong with what is touched in [scope]: a button, field or link that is not in the window
 * (outside a container that scrolls on its own), a button or field lower than 44px, and a link that
 * cannot be hit within 44px (20px above or below its middle). Empty when all is well.
 */
export const trouble = (page: Page, scope: string) =>
  page.evaluate((root) => {
    const scopeEl = document.querySelector<HTMLElement>(root)!;
    const width = document.documentElement.clientWidth;
    const found: string[] = [];
    const visible = (el: Element) => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
    };
    const scrolls = (el: Element) => {
      for (let node = el.parentElement; node && node !== scopeEl.parentElement; node = node.parentElement) {
        const x = getComputedStyle(node).overflowX;
        if ((x === 'auto' || x === 'scroll') && node !== document.body && node !== document.documentElement) return true;
      }
      return false;
    };
    const name = (el: Element) => `${el.tagName.toLowerCase()}${el.className ? '.' + String(el.className).split(' ').join('.') : ''} "${(el.textContent ?? '').trim().slice(0, 24)}"`;
    const pressed = scopeEl.querySelectorAll<HTMLElement>(
      'button, select, textarea, input:not([type=checkbox]):not([type=radio]):not([type=file]):not([type=hidden]), .rl-btn',
    );
    for (const el of pressed) {
      if (!visible(el)) continue;
      const r = el.getBoundingClientRect();
      if (r.height < 43.5) found.push(`${name(el)} is ${Math.round(r.height)}px high`);
      if (!scrolls(el) && (r.left < -0.5 || r.right > width + 0.5)) found.push(`${name(el)} is outside the window`);
    }
    for (const el of scopeEl.querySelectorAll<HTMLInputElement>('input[type=checkbox], input[type=radio]')) {
      if (!visible(el)) continue;
      const box = (el.labels?.[0] ?? el).getBoundingClientRect();
      if (box.height < 43.5) found.push(`${name(el)} (${el.type}) has ${Math.round(box.height)}px to be hit in`);
      if (!scrolls(el) && el.getBoundingClientRect().right > width + 0.5) found.push(`${name(el)} is outside the window`);
    }
    for (const el of scopeEl.querySelectorAll<HTMLAnchorElement>('a[href]')) {
      // The link to skip to the content is there for the keyboard, and out of sight until it has it.
      if (!visible(el) || el.matches('.rl-btn, .skip') || el.closest('.sr-only')) continue;
      el.scrollIntoView({ block: 'center' });
      const rects = [...el.getClientRects()];
      if (rects.length === 0) continue;
      const r = rects[0];
      const x = r.x + r.width / 2;
      const y = r.y + r.height / 2;
      const owns = (py: number) => document.elementsFromPoint(x, py).some((hit) => hit === el || el.contains(hit));
      if (r.height < 43.5 && !(owns(y - 20) && owns(y + 20))) found.push(`${name(el)} cannot be hit within 44px`);
    }
    return found;
  }, scope);

/** The window of a dialog: where it is, and what it holds. */
export async function dialogIn(page: Page, viewport: Viewport) {
  const dialog = page.getByRole('dialog');
  await dialog.waitFor();
  const rect = (await dialog.boundingBox())!;
  expect(rect.x).toBeGreaterThanOrEqual(0);
  expect(rect.y).toBeGreaterThanOrEqual(0);
  expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width + 0.5);
  expect(rect.y + rect.height).toBeLessThanOrEqual(viewport.height + 0.5);
  expect(await horizontalOverflow(page)).toBe(0);
  expect(await trouble(page, '[role=dialog]')).toEqual([]);
  // The buttons of the foot are in the window, whatever the body holds.
  for (const button of await dialog.locator('footer button:visible').all()) {
    const r = (await button.boundingBox())!;
    expect(r.x + r.width).toBeLessThanOrEqual(viewport.width + 0.5);
    expect(r.y + r.height).toBeLessThanOrEqual(viewport.height + 0.5);
  }
  return dialog;
}


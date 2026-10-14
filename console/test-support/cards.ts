import { expect } from 'vitest';

/**
 * What a list that is a table on a wide screen and a column of cards on a narrow one (ADR-048,
 * WI-62) promises in its markup: it is one table, marked as such, and every cell carries the name
 * of its column, which the card shows; the way into the row is one link that covers the card.
 * The look itself (cards below 768px, a table above) is checked in a real browser, e2e/responsive-information.e2e.ts.
 */
export function expectCards(table: Element | null, headers: string[]) {
  expect(table).not.toBeNull();
  expect(table!.classList.contains('rl-cards')).toBe(true);
  expect([...table!.querySelectorAll('thead th')].map((th) => th.textContent!.trim())).toEqual(headers);
  const rows = [...table!.querySelectorAll('tbody tr')];
  expect(rows.length).toBeGreaterThan(0);
  for (const row of rows) {
    expect([...row.querySelectorAll(':scope > td')].map((td) => td.getAttribute('data-label'))).toEqual(headers);
    expect(row.querySelectorAll('a.rl-card-link')).toHaveLength(1);
  }
}

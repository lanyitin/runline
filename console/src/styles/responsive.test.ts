import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';

// The narrow layout begins below 768px, and the width is written down once: in the style sheet of
// the design system (src/styles/responsive.css). Nothing else in the Console names it (ADR-048).

const files = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? files(join(dir, entry.name)) : [join(dir, entry.name)],
  );
const styled = files('src').filter((f) => /\.(css|svelte)$/.test(f));
const BREAKPOINT = /@media[^{]*767\.98px/;

describe('the breakpoint of the narrow layout', () => {
  test('is a width media query of the design system, below 768px', () => {
    const css = readFileSync('src/styles/responsive.css', 'utf8');
    expect(css.match(/@media[^{]*max-width:\s*767\.98px/g)).toHaveLength(1);
  });

  test('is written down nowhere else', () => {
    const elsewhere = styled
      .filter((f) => f !== join('src', 'styles', 'responsive.css'))
      .filter((f) => BREAKPOINT.test(readFileSync(f, 'utf8')) || /768px/.test(readFileSync(f, 'utf8')));
    expect(elsewhere).toEqual([]);
  });

  test('is in force in the Console: the style sheet is loaded after the others', () => {
    const boot = readFileSync('src/bootstrap.ts', 'utf8');
    expect(boot.indexOf("import './styles/responsive.css'")).toBeGreaterThan(
      boot.indexOf("import './styles/base.css'"),
    );
  });
});

// A table is wider than a phone more often than not. Every table of the Console is either cards
// (the lists: rl-cards) or keeps to a container of its own that scrolls (rl-table-wrap, rl-scroll-x)
// with its first column held (rl-sticky-first), so that no page scrolls sideways because of one.
describe('the tables of the Console', () => {
  const sources = styled.filter((f) => f.endsWith('.svelte'));
  const tables = sources.flatMap((file) => {
    const lines = readFileSync(file, 'utf8').split('\n');
    return lines.flatMap((line, at) =>
      line.includes('<table')
        ? [{ file, tag: line.trim(), before: lines.slice(Math.max(0, at - 2), at).join('\n') }]
        : [],
    );
  });

  test('are found in the pages', () => {
    expect(tables.length).toBeGreaterThan(10);
  });

  test('are cards or hold their first column', () => {
    const loose = tables.filter(({ tag }) => !/rl-cards|rl-sticky-first/.test(tag));
    expect(loose.map(({ file }) => file)).toEqual([]);
  });

  test('are in a container of their own that scrolls', () => {
    const loose = tables.filter(({ before }) => !/rl-table-wrap|rl-scroll-x/.test(before));
    expect(loose.map(({ file }) => file)).toEqual([]);
  });
});

import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';

// The favicon is one SVG file (public/favicon.svg) that cannot refer to the design tokens, so its two
// pairs of colours are checked against the tokens (src/styles/tokens.css) here (WI-81): the tile is
// --accent-solid and the prompt symbol --on-accent, in the light theme and in the dark one. When a
// token changes, this fails until the icon is changed with it.

const DARK_QUERY = '@media (prefers-color-scheme: dark)';
const svg = readFileSync('public/favicon.svg', 'utf8');
const css = readFileSync('src/styles/tokens.css', 'utf8');

/** The part of a piece of CSS inside the braces that follow `marker`. */
function blockAfter(source: string, marker: string): string {
  const start = source.indexOf(marker);
  if (start < 0) return '';
  const open = source.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(open + 1, i);
  }
  throw new Error(`the block after ${marker} is not closed`);
}

/** The `--name: #rrggbb` declarations of tokens.css, lower-cased. */
function tokens(source: string): Record<string, string> {
  const found: Record<string, string> = {};
  for (const m of source.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\b/g)) found[m[1]] = m[2].toLowerCase();
  return found;
}

/** The colour that `selector { property: #rrggbb }` gives in a piece of CSS. */
function colourOf(source: string, selector: string, property: 'fill' | 'stroke'): string | undefined {
  const rule = new RegExp(`\\${selector}\\s*\\{[^}]*?${property}:\\s*(#[0-9a-fA-F]{6})\\b`).exec(source);
  return rule?.[1].toLowerCase();
}

const style = /<style[^>]*>([\s\S]*?)<\/style>/.exec(svg)?.[1] ?? '';
const darkStyle = blockAfter(style, DARK_QUERY);
const lightStyle = style.replace(darkStyle, '');

const darkBlockOfTokens = blockAfter(css, DARK_QUERY);
const lightTokens = tokens(css.replace(darkBlockOfTokens, ''));
const darkTokens = { ...lightTokens, ...tokens(darkBlockOfTokens) };

function luminance(hex: string): number {
  const channel = (offset: number) => {
    const value = parseInt(hex.slice(1 + offset, 3 + offset), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
}

function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

describe('the favicon', () => {
  test('is one self-contained SVG that loads nothing and runs nothing', () => {
    expect(svg).not.toMatch(/<script|href=|@import|url\(|<image|<link/i);
  });

  test.each([
    ['light', lightStyle, lightTokens],
    ['dark', darkStyle, darkTokens],
  ] as const)('has the tile and symbol colours of the %s tokens', (_theme, source, theme) => {
    expect(colourOf(source, '.tile', 'fill')).toBe(theme['accent-solid']);
    expect(colourOf(source, '.mark', 'stroke')).toBe(theme['on-accent']);
  });

  test.each([
    ['light', lightStyle],
    ['dark', darkStyle],
  ] as const)('keeps the symbol readable on the tile in the %s theme (3:1)', (theme, source) => {
    const tile = colourOf(source, '.tile', 'fill');
    const mark = colourOf(source, '.mark', 'stroke');
    expect(tile, `the ${theme} tile colour`).toBeDefined();
    expect(mark, `the ${theme} symbol colour`).toBeDefined();
    expect(contrast(tile!, mark!)).toBeGreaterThanOrEqual(3);
  });
});

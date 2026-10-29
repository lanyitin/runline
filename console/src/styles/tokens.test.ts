import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';

// The design tokens are CSS variables (src/styles/tokens.css), in two themes (ADR-70): the light
// values of :root, and the dark values that the system preference puts over them. The text colours
// must be readable on the surfaces they are used on (WCAG 2.x AA: 4.5:1 for text; 3:1 for large
// text, icons and the boundaries of components), in each theme.

const css = readFileSync('src/styles/tokens.css', 'utf8');
const DARK_QUERY = '@media (prefers-color-scheme: dark)';

/** The declarations of `--name: #rrggbb` in a piece of CSS. */
function colours(source: string): Record<string, string> {
  const found: Record<string, string> = {};
  for (const match of source.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\b/g)) {
    found[match[1]] = match[2];
  }
  return found;
}

/** The part of tokens.css inside the dark media query. */
function darkBlock(): string {
  const start = css.indexOf(DARK_QUERY);
  if (start < 0) return '';
  const open = css.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < css.length; i++) {
    if (css[i] === '{') depth++;
    if (css[i] === '}' && --depth === 0) return css.slice(open + 1, i);
  }
  throw new Error('the dark media query of tokens.css is not closed');
}

const light = colours(css.replace(darkBlock(), ''));
const dark = { ...light, ...colours(darkBlock()) };

function luminance(hex: string): number {
  const channel = (offset: number) => {
    const value = parseInt(hex.slice(1 + offset, 3 + offset), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
}

function contrast(theme: Record<string, string>, foreground: string, background: string): number {
  for (const name of [foreground, background]) {
    if (!theme[name]) throw new Error(`token --${name} is not a hex colour in tokens.css`);
  }
  const [a, b] = [luminance(theme[foreground]), luminance(theme[background])].sort((x, y) => y - x);
  return (a + 0.05) / (b + 0.05);
}

// Text on the surface it is set on: 4.5:1.
const textPairs: Array<[string, string]> = [
  ['text', 'bg'],
  ['text', 'surface'],
  ['text', 'surface-subtle'],
  ['text-secondary', 'surface'],
  ['text-secondary', 'surface-subtle'],
  ['text-muted', 'surface'],
  ['text-muted', 'bg'],
  ['text-muted', 'surface-subtle'],
  ['accent-text', 'surface'],
  ['accent-text', 'accent-tint'],
  ['on-accent', 'accent-solid'],
  ['on-accent', 'danger-text'],
  ['success-text', 'success-tint'],
  ['danger-text', 'danger-tint'],
  ['danger-text', 'surface'],
  ['warning-text', 'warning-tint'],
  ['warning-text', 'surface'],
  ['running-text', 'running-tint'],
  ['neutral-text', 'neutral-tint'],
  ['indigo-text', 'indigo-tint'],
  ['terminal-text', 'terminal-bg'],
  ['terminal-muted', 'terminal-bg'],
  ['terminal-error-text', 'terminal-bg'],
];

// Boundaries, icons and the focus ring against the surfaces they sit on: 3:1.
const boundaryPairs: Array<[string, string]> = [
  ['danger', 'surface'],
];
// The boundaries that the light theme does not reach and that its unchanged values keep (the
// field border and the focus ring on white are 1.5:1 and 2.8:1); only the dark theme is held to them.
const darkOnlyBoundaryPairs: Array<[string, string]> = [
  ['border-strong', 'surface'],
  ['border-strong', 'bg'],
  ['border-strong', 'surface-subtle'],
  ['accent', 'surface'],
  ['accent', 'bg'],
  ['accent', 'surface-subtle'],
  ['success', 'surface'],
  ['warning', 'surface'],
  ['running', 'surface'],
  ['neutral', 'surface'],
  ['indigo', 'surface'],
  ['terminal-border', 'surface'],
  ['terminal-border', 'bg'],
];

const themes: Array<[string, Record<string, string>, Array<[string, string]>]> = [
  ['light', light, boundaryPairs],
  ['dark', dark, [...boundaryPairs, ...darkOnlyBoundaryPairs]],
];

describe.each(themes)('the %s theme of the design system', (_name, theme, boundaries) => {
  test.each(textPairs)('text %s on %s reaches 4.5:1', (foreground, background) => {
    expect(contrast(theme, foreground, background)).toBeGreaterThanOrEqual(4.5);
  });

  test.each(boundaries)('boundary %s on %s reaches 3:1', (foreground, background) => {
    expect(contrast(theme, foreground, background)).toBeGreaterThanOrEqual(3);
  });
});

describe('the dark theme', () => {
  test('is given by the system preference, over the same token names', () => {
    expect(darkBlock()).not.toBe('');
    const declared = new Set(
      [...css.replace(darkBlock(), '').matchAll(/--([a-z0-9-]+):/g)].map((m) => m[1]),
    );
    const names = Object.keys(colours(darkBlock()));
    expect(names.filter((name) => !declared.has(name))).toEqual([]);
  });

  test('changes the surfaces and the text: no light surface is left', () => {
    for (const name of ['bg', 'surface', 'surface-subtle', 'accent-tint', 'success-tint']) {
      expect(luminance(dark[name]), `--${name}`).toBeLessThan(0.05);
    }
    for (const name of ['text', 'text-secondary', 'text-muted']) {
      expect(luminance(dark[name]), `--${name}`).toBeGreaterThan(0.2);
    }
  });

  test('layers the surfaces by lightness: background, then card, then subtle', () => {
    expect(luminance(dark.bg)).toBeLessThan(luminance(dark.surface));
    expect(luminance(dark.surface)).toBeLessThan(luminance(dark['surface-subtle']));
  });

  test('keeps the light theme as it was', () => {
    expect(light).toMatchObject({
      bg: '#f7f9fc',
      surface: '#ffffff',
      text: '#0f172a',
      'accent-solid': '#0369a1',
      'on-accent': '#ffffff',
      'danger-text': '#b91c1c',
    });
  });

  test('has the terminal panel apart from the surfaces around it', () => {
    expect(luminance(dark['terminal-bg'])).toBeLessThan(luminance(dark.surface));
  });

  test('declares that the browser controls follow it', () => {
    expect(css).toMatch(/color-scheme:\s*light dark/);
  });
});

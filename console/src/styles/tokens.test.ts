import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';

// The design tokens are CSS variables (src/styles/tokens.css). The text colours of the design
// system must be readable on the surfaces they are used on (WCAG 2.x AA: 4.5:1 for text).

const css = readFileSync('src/styles/tokens.css', 'utf8');

function token(name: string): string {
  const match = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})\\b`).exec(css);
  if (!match) throw new Error(`token --${name} is not a hex colour in tokens.css`);
  return match[1];
}

function luminance(hex: string): number {
  const channel = (offset: number) => {
    const value = parseInt(hex.slice(1 + offset, 3 + offset), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
}

function contrast(foreground: string, background: string): number {
  const [a, b] = [luminance(token(foreground)), luminance(token(background))].sort((x, y) => y - x);
  return (a + 0.05) / (b + 0.05);
}

const pairs: Array<[string, string]> = [
  ['text', 'bg'],
  ['text', 'surface'],
  ['text-secondary', 'surface'],
  ['text-secondary', 'surface-subtle'],
  ['text-muted', 'surface'],
  ['text-muted', 'bg'],
  ['text-muted', 'surface-subtle'],
  ['accent-text', 'surface'],
  ['accent-text', 'accent-tint'],
  ['on-accent', 'accent-solid'],
  ['success-text', 'success-tint'],
  ['danger-text', 'danger-tint'],
  ['warning-text', 'warning-tint'],
  ['running-text', 'running-tint'],
  ['neutral-text', 'neutral-tint'],
  ['indigo-text', 'indigo-tint'],
];

describe('the text colours of the design system', () => {
  test.each(pairs)('%s on %s reaches 4.5:1', (foreground, background) => {
    expect(contrast(foreground, background)).toBeGreaterThanOrEqual(4.5);
  });
});

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';

// A component does not name a colour of its own: it refers to a token (ADR-70). A colour written in
// a component stays the same in the dark theme and breaks it. The values live in tokens.css.

const files = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? files(join(dir, entry.name)) : [join(dir, entry.name)],
  );
const styled = files('src').filter(
  (f) => /\.(css|svelte)$/.test(f) && f !== join('src', 'styles', 'tokens.css'),
);

const LITERAL =
  /#[0-9a-fA-F]{3,8}\b|\b(?:rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color)\(|:\s*(?:white|black|red|green|blue|gray|grey|silver|orange|yellow)\b/g;

describe('the colours of the components', () => {
  test('are tokens: no component or style sheet but tokens.css names a colour', () => {
    const literals = styled.flatMap((file) =>
      [...readFileSync(file, 'utf8').matchAll(LITERAL)].map((m) => `${file}: ${m[0]}`),
    );
    expect(literals).toEqual([]);
  });
});

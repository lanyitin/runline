// The static rule behind the plain text rendering of ADR-017: what the pipelines send (names,
// parameters, log lines, failure messages and traces, strings in a jar) is untrusted, and no source
// of the Console may turn a string into markup, script or style. There is no way around the rule:
// the Console renders with text nodes (`{value}` in a template, `textContent`), which cannot be
// read as markup. A match fails the build.

export interface MarkupInsertion {
  file: string;
  line: number;
  rule: string;
}

const RULES: ReadonlyArray<{ rule: string; pattern: RegExp }> = [
  { rule: 'the {@html} tag of Svelte', pattern: /\{@html\b/ },
  { rule: 'innerHTML', pattern: /\binnerHTML\b/ },
  { rule: 'outerHTML', pattern: /\bouterHTML\b/ },
  { rule: 'insertAdjacentHTML', pattern: /\binsertAdjacentHTML\b/ },
  { rule: 'document.write', pattern: /\bdocument\s*\.\s*write(?:ln)?\s*\(/ },
  { rule: 'createContextualFragment', pattern: /\bcreateContextualFragment\b/ },
  { rule: 'DOMParser', pattern: /\bDOMParser\b/ },
  { rule: 'srcdoc', pattern: /\bsrcdoc\b/ },
  { rule: 'eval', pattern: /(?<![.\w])eval\s*\(/ },
  { rule: 'new Function', pattern: /\bnew\s+Function\s*\(/ },
];

/** Every place in [source] that turns a string into markup or code. */
export function findMarkupInsertion(source: string, file: string): MarkupInsertion[] {
  const found: MarkupInsertion[] = [];
  source.split('\n').forEach((text, index) => {
    for (const { rule, pattern } of RULES) {
      if (pattern.test(text)) found.push({ file, line: index + 1, rule });
    }
  });
  return found;
}

const isOwnSource = (id: string) =>
  /\/src\/.*\.(svelte|ts|js)$/.test(id) && !id.includes('/node_modules/') && !/\.test\.ts$/.test(id);

/** A Vite plugin: the build fails on a source of the Console that inserts a string as markup. */
export function forbidMarkupInsertion() {
  return {
    name: 'runline-forbid-markup-insertion',
    enforce: 'pre' as const,
    transform(code: string, id: string): null {
      const file = id.split('?')[0];
      if (!isOwnSource(file)) return null;
      const [first] = findMarkupInsertion(code, file);
      if (first) {
        throw new Error(
          `${first.file}:${first.line}: ${first.rule} would insert a string as markup. Content ` +
            'from pipelines is untrusted and is shown as plain text only (ADR-017).',
        );
      }
      return null;
    },
  };
}

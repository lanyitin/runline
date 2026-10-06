import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';
import config from '../vite.config';
import { findMarkupInsertion, forbidMarkupInsertion } from './untrusted-content';

describe('findMarkupInsertion', () => {
  test.each([
    ['{@html value}', 'a.svelte'],
    ['<div>{@html  log.line}</div>', 'a.svelte'],
    ['el.innerHTML = line;', 'a.ts'],
    ['el.outerHTML = line;', 'a.ts'],
    ['el.insertAdjacentHTML("beforeend", line);', 'a.ts'],
    ['document.write(line);', 'a.ts'],
    ['range.createContextualFragment(line);', 'a.ts'],
    ['new DOMParser().parseFromString(line, "text/html");', 'a.ts'],
    ['iframe.srcdoc = line;', 'a.ts'],
    ['eval(line);', 'a.ts'],
    ['new Function(line);', 'a.ts'],
    ['<div bind:innerHTML={text}></div>', 'a.svelte'],
    ['<p>{@html "<b>x</b>"}</p>\n<script>el.innerHTML = 1</script>', 'a.svelte'],
  ])('finds %s', (source, file) => {
    expect(findMarkupInsertion(source, file).length).toBeGreaterThan(0);
  });

  test('says which file and line', () => {
    const [found] = findMarkupInsertion('ok\nok\nel.innerHTML = x;', 'src/Log.ts');
    expect(found).toMatchObject({ file: 'src/Log.ts', line: 3 });
    expect(found.rule).toContain('innerHTML');
  });

  test.each([
    ['<p>{line}</p>', 'a.svelte'],
    ['el.textContent = line;', 'a.ts'],
    ['const html = "innerHTMLish";', 'a.ts'],
    ['evaluate(x); retrieval(y);', 'a.ts'],
  ])('lets %s through', (source, file) => {
    expect(findMarkupInsertion(source, file)).toEqual([]);
  });
});

describe('forbidMarkupInsertion (the plugin of the build)', () => {
  const plugin = forbidMarkupInsertion();
  const transform = (code: string, id: string) =>
    (plugin.transform as (code: string, id: string) => unknown).call({}, code, id);

  test('fails the build on a source of the Console that inserts markup', () => {
    expect(() => transform('{@html x}', '/p/console/src/Log.svelte')).toThrow(/Log\.svelte:1.*@html/s);
  });

  test('lets a clean source be', () => {
    expect(transform('<p>{x}</p>', '/p/console/src/Log.svelte')).toBeNull();
  });

  test('does not look at tests or at what is not the Console\'s own source', () => {
    expect(transform('el.innerHTML = x', '/p/console/src/a.test.ts')).toBeNull();
    expect(transform('el.innerHTML = x', '/p/console/node_modules/lib/index.js')).toBeNull();
  });
});

describe('the sources of the Console', () => {
  const walk = (dir: string): string[] =>
    readdirSync(dir).flatMap((name) => {
      const path = join(dir, name);
      return statSync(path).isDirectory() ? walk(path) : [path];
    });

  test('insert no string as markup', () => {
    const own = walk('src').filter((f) => /\.(svelte|ts)$/.test(f) && !/\.test\.ts$/.test(f));
    expect(own.length).toBeGreaterThan(0);
    const found = own.flatMap((file) => findMarkupInsertion(readFileSync(file, 'utf8'), file));
    expect(found).toEqual([]);
  });
});

describe('the rule in the build (WI-37)', () => {
  const plugin = forbidMarkupInsertion();
  const transform = (code: string, id: string) =>
    (plugin.transform as (code: string, id: string) => unknown).call({}, code, id);

  test('is a plugin of the build that runs before the others, with no switch to turn it off', () => {
    const plugins = (config.plugins ?? []).flat() as { name?: string; enforce?: string }[];
    const rule = plugins.find((p) => p?.name === 'runline-forbid-markup-insertion');
    expect(rule, 'the build has the rule').toBeDefined();
    expect(rule!.enforce).toBe('pre');
    const source = readFileSync('tools/untrusted-content.ts', 'utf8');
    expect(source, 'a setting that turns the rule off').not.toMatch(/process\.env|import\.meta\.env/);
  });

  test.each(['/p/console/src/a.ts', '/p/console/src/deep/er/a.js', '/p/console/src/a.svelte'])(
    'has no list of exceptions: every kind of source of the Console fails the build (%s)',
    (id) => {
      expect(() => transform('el.innerHTML = x', id)).toThrow(/innerHTML/);
    },
  );
});

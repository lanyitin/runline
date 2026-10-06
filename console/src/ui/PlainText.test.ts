import { mount, unmount } from 'svelte';
import { afterEach, expect, test } from 'vitest';
import PlainText from './PlainText.svelte';

let component: ReturnType<typeof mount> | undefined;
afterEach(() => {
  if (component) unmount(component);
  component = undefined;
  document.body.innerHTML = '';
});

const hostile = [
  '<img src=x onerror="document.title=\'pwned\'">',
  '<script>document.title = "pwned"</script>',
  '"><svg onload=alert(1)>',
  '&lt;b&gt;already escaped&lt;/b&gt;',
];

test.each(hostile)('shows %s as the text it is, creating no element', (value) => {
  component = mount(PlainText, { target: document.body, props: { value } });

  const shown = document.body.firstElementChild!;
  expect(shown.textContent).toBe(value);
  expect(shown.children).toHaveLength(0);
  expect(document.body.querySelector('img, script, svg')).toBeNull();
  expect(document.title).not.toBe('pwned');
});

test('keeps the line breaks and the spaces of a log line when it is multiline', () => {
  component = mount(PlainText, {
    target: document.body,
    props: { value: 'a\n  b', multiline: true },
  });
  expect(document.body.firstElementChild!.classList.contains('multiline')).toBe(true);
  expect(document.body.firstElementChild!.textContent).toBe('a\n  b');
});

test('is monospace for identifiers when asked', () => {
  component = mount(PlainText, { target: document.body, props: { value: 'abc', mono: true } });
  expect(document.body.firstElementChild!.classList.contains('mono')).toBe(true);
});

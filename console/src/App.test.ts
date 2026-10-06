import { mount, unmount } from 'svelte';
import { afterEach, expect, test } from 'vitest';
import App from './App.svelte';

let app: ReturnType<typeof mount> | undefined;

afterEach(() => {
  if (app) unmount(app);
  app = undefined;
  document.body.innerHTML = '';
});

test('the skeleton page names the Console', () => {
  app = mount(App, { target: document.body });

  expect(document.body.textContent).toContain('Runline Console');
});

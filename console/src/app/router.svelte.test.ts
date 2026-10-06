import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { createRouter } from './router.svelte';

let router: ReturnType<typeof createRouter>;

beforeEach(() => history.replaceState(null, '', '/runs'));
afterEach(() => router.dispose());

describe('the history router', () => {
  test('starts at the path of the page', () => {
    router = createRouter(window);
    expect(router.path).toBe('/runs');
  });

  test('navigate changes the address without loading a page, and the path with it', () => {
    router = createRouter(window);
    const before = history.length;

    router.navigate('/upload');

    expect(router.path).toBe('/upload');
    expect(location.pathname).toBe('/upload');
    expect(history.length).toBe(before + 1);
  });

  test('navigating to where it already is adds no entry of history', () => {
    router = createRouter(window);
    const before = history.length;
    router.navigate('/runs');
    expect(history.length).toBe(before);
  });

  test('back and forward of the browser change the path', () => {
    router = createRouter(window);
    router.navigate('/upload');

    history.replaceState(null, '', '/pipelines');
    window.dispatchEvent(new PopStateEvent('popstate'));

    expect(router.path).toBe('/pipelines');
  });

  test('keeps the query and the fragment out of the path, and does not drop them from the address', () => {
    router = createRouter(window);
    router.navigate('/runs?state=FAILED#top');
    expect(router.path).toBe('/runs');
    expect(location.search + location.hash).toBe('?state=FAILED#top');
  });

  test('after dispose the browser buttons no longer move it', () => {
    router = createRouter(window);
    router.dispose();
    history.replaceState(null, '', '/pipelines');
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(router.path).toBe('/runs');
  });
});

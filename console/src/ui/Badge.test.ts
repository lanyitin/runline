import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import Badge from './Badge.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const badge = async (props: { kind: 'runState' | 'verdict'; value: string }) => {
  app = await createTestApp();
  return app.mount(Badge, props).querySelector('.badge')!;
};

describe('Badge', () => {
  test.each([
    ['runState', 'SUCCEEDED', 'success'],
    ['runState', 'FAILED', 'danger'],
    ['runState', 'RUNNING', 'running'],
    ['runState', 'QUEUED', 'neutral'],
    ['runState', 'WAITING_FOR_RESOURCES', 'warning'],
    ['runState', 'TIMED_OUT', 'danger'],
    ['runState', 'CANCELLED', 'neutral'],
    ['verdict', 'SAFE', 'success'],
    ['verdict', 'UNSAFE', 'danger'],
  ] as const)('%s %s is %s', async (kind, value, tone) => {
    const element = await badge({ kind, value });
    expect(element.classList.contains(tone)).toBe(true);
  });

  test('says the state in words, so that colour is not the only sign', async () => {
    const element = await badge({ kind: 'runState', value: 'FAILED' });
    expect(element.textContent?.trim()).toBe('FAILED');
  });

  test('a value the Console does not know is shown as it came, in the neutral tone', async () => {
    const element = await badge({ kind: 'runState', value: 'SOMETHING_NEW' });
    expect(element.textContent?.trim()).toBe('SOMETHING_NEW');
    expect(element.classList.contains('neutral')).toBe(true);
  });

  test('has a dot that screen readers skip', async () => {
    const element = await badge({ kind: 'verdict', value: 'SAFE' });
    expect(element.querySelector('.dot')?.getAttribute('aria-hidden')).toBe('true');
  });
});

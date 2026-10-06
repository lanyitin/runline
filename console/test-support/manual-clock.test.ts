import { expect, test } from 'vitest';
import { ManualClock, ManualVisibility } from './manual-clock';

test('a timer runs when its time has come, not before, and only once', () => {
  const clock = new ManualClock();
  const ran: string[] = [];
  clock.after(1000, () => ran.push('a'));

  clock.advance(999);
  expect(ran).toEqual([]);
  clock.advance(1);
  expect(ran).toEqual(['a']);
  clock.advance(10_000);
  expect(ran).toEqual(['a']);
});

test('timers run in the order of their time, also when one sets another that falls due', () => {
  const clock = new ManualClock();
  const ran: string[] = [];
  clock.after(300, () => ran.push('c'));
  clock.after(100, () => {
    ran.push('a');
    clock.after(100, () => ran.push('b'));
  });

  clock.advance(1000);

  expect(ran).toEqual(['a', 'b', 'c']);
});

test('a timer that was cancelled does not run; the time to the next one and how many wait are told', () => {
  const clock = new ManualClock();
  const ran: string[] = [];
  const cancel = clock.after(500, () => ran.push('x'));
  clock.after(800, () => ran.push('y'));
  expect(clock.waiting).toBe(2);
  expect(clock.untilNext).toBe(500);

  cancel();
  expect(clock.waiting).toBe(1);
  expect(clock.untilNext).toBe(800);
  clock.advance(1000);
  expect(ran).toEqual(['y']);
  expect(clock.untilNext).toBeNull();
});

test('the visibility tells whoever listens, until they stop', () => {
  const visibility = new ManualVisibility();
  let told = 0;
  const stop = visibility.subscribe(() => (told += 1));
  expect(visibility.visible).toBe(true);

  visibility.set(false);
  expect(visibility.visible).toBe(false);
  stop();
  visibility.set(true);

  expect(told).toBe(1);
});

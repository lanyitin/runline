import { expect, test } from 'vitest';
import { createConnectionMonitor } from './connection.svelte';

test('knows nothing until the first call has gone', () => {
  expect(createConnectionMonitor().state).toBe('unknown');
});

test('is up when the last call got an answer, down when it did not, and follows the last one only', () => {
  const connection = createConnectionMonitor();
  connection.record(true);
  expect(connection.state).toBe('up');
  connection.record(false);
  expect(connection.state).toBe('down');
  connection.record(true);
  expect(connection.state).toBe('up');
});

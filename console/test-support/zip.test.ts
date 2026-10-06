import { expect, test } from 'vitest';
import { readZip, storedZip, uniqueJar } from './zip';

const text = (bytes: Uint8Array | undefined) => new TextDecoder().decode(bytes);

test('what is put in a zip is read out of it, by name, byte for byte', () => {
  const zip = storedZip({
    'a.txt': 'alpha',
    'dir/b.bin': new Uint8Array([0, 1, 2, 255]),
    'ü.txt': 'ü',
  });
  const entries = readZip(zip)!;
  expect([...entries.keys()]).toEqual(['a.txt', 'dir/b.bin', 'ü.txt']);
  expect(text(entries.get('a.txt'))).toBe('alpha');
  expect([...entries.get('dir/b.bin')!]).toEqual([0, 1, 2, 255]);
  expect(text(entries.get('ü.txt'))).toBe('ü');
});

test('a zip of nothing is a zip with no entries', () => {
  expect(readZip(storedZip({}))!.size).toBe(0);
});

test('what is not a zip is not read as one', () => {
  expect(readZip(new TextEncoder().encode('this is not a jar'))).toBeNull();
  expect(readZip(new Uint8Array(0))).toBeNull();
});

test('a zip that is cut short is not read as one', () => {
  const zip = storedZip({ 'a.txt': 'alpha' });
  expect(readZip(zip.slice(0, zip.length - 10))).toBeNull();
});

test('a jar with a comment is another file with the same entries: a new version of the same pipelines', () => {
  const zip = storedZip({ 'a.txt': 'alpha', 'b.txt': 'beta' });
  const first = uniqueJar(zip, 'one');
  const second = uniqueJar(zip, 'two');

  expect(first).not.toEqual(zip);
  expect(first).not.toEqual(second);
  expect([...readZip(first)!.keys()]).toEqual(['a.txt', 'b.txt']);
  expect(text(readZip(second)!.get('b.txt'))).toBe('beta');
});

test('a comment is added to a jar that has one already, and the entries are still read', () => {
  const once = uniqueJar(storedZip({ 'a.txt': 'alpha' }), 'one');
  const twice = uniqueJar(once, 'two');
  expect(twice).not.toEqual(once);
  expect(text(readZip(twice)!.get('a.txt'))).toBe('alpha');
});

import { describe, expect, test } from 'vitest';
import {
  formatBytes,
  formatDateTime,
  formatDuration,
  formatNumber,
  formatRelative,
  utcOriginal,
} from './format';

describe('formatDateTime', () => {
  const iso = '2026-10-05T14:30:00Z';

  test('shows the instant in the time zone of the browser, in the format of the language', () => {
    expect(formatDateTime(iso, 'en', 'Asia/Taipei')).toBe('Oct 5, 2026, 10:30:00 PM');
    expect(formatDateTime(iso, 'zh-TW', 'Asia/Taipei')).toBe('2026年10月5日 晚上10:30:00');
  });

  test('another time zone gives another wall clock for the same instant', () => {
    expect(formatDateTime(iso, 'en', 'UTC')).toBe('Oct 5, 2026, 2:30:00 PM');
  });

  test('a value that is not a time is shown as it came', () => {
    expect(formatDateTime('yesterday-ish', 'en', 'UTC')).toBe('yesterday-ish');
  });
});

describe('utcOriginal', () => {
  test('is the UTC value of the API, for the tooltip', () => {
    expect(utcOriginal('2026-10-05T14:30:00Z')).toBe('2026-10-05T14:30:00Z');
    expect(utcOriginal('2026-10-05T14:30:00.250Z')).toBe('2026-10-05T14:30:00.250Z');
  });
});

describe('formatNumber', () => {
  test('separates thousands the way of the language', () => {
    expect(formatNumber(1234567.5, 'en')).toBe('1,234,567.5');
    expect(formatNumber(1234567.5, 'zh-TW')).toBe('1,234,567.5');
  });
});

describe('formatBytes', () => {
  test.each([
    [0, '0 B'],
    [1023, '1,023 B'],
    [1024, '1 KiB'],
    [1536, '1.5 KiB'],
    [50 * 1024 * 1024, '50 MiB'],
    [3 * 1024 ** 3, '3 GiB'],
  ])('%d bytes', (bytes, text) => expect(formatBytes(bytes, 'en')).toBe(text));

  test('rounds to one decimal', () => {
    expect(formatBytes(1024 * 1024 * 1.2345, 'en')).toBe('1.2 MiB');
  });
});

describe('formatDuration', () => {
  test.each([
    [0, '0s'],
    [250, '250ms'],
    [84_000, '1m 24s'],
    [3_600_000, '1h'],
    [90_061_000, '1d 1h'],
  ])('%d ms in English', (ms, text) => expect(formatDuration(ms, 'en')).toBe(text));

  test('in zh-TW the units are the words of that language', () => {
    expect(formatDuration(84_000, 'zh-TW')).toMatch(/1.*分.*24.*秒/);
  });
});

describe('formatRelative', () => {
  const now = new Date('2026-10-05T12:00:00Z');

  test('in the past and the future, with the unit that fits', () => {
    expect(formatRelative('2026-10-05T11:57:00Z', now, 'en')).toBe('3 minutes ago');
    expect(formatRelative('2026-10-05T12:00:30Z', now, 'en')).toBe('in 30 seconds');
    expect(formatRelative('2026-10-05T09:00:00Z', now, 'en')).toBe('3 hours ago');
    expect(formatRelative('2026-10-03T12:00:00Z', now, 'en')).toBe('2 days ago');
  });

  test('in zh-TW', () => {
    expect(formatRelative('2026-10-05T11:57:00Z', now, 'zh-TW')).toBe('3 分鐘前');
  });

  test('the moment itself is "now"', () => {
    expect(formatRelative('2026-10-05T12:00:00Z', now, 'en')).toBe('now');
  });
});

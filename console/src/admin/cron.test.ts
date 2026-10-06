import { describe, expect, test } from 'vitest';
import { catalogs } from '../i18n/catalogs';
import { createTranslator } from '../i18n/translator';
import { describeCron, parseCron } from './cron';

const en = (expression: string) => describeCron(expression, createTranslator(catalogs, 'en'), 'en');
const zh = (expression: string) =>
  describeCron(expression, createTranslator(catalogs, 'zh-TW'), 'zh-TW');

describe('reading a cron expression', () => {
  test('is five fields: minute, hour, day of month, month, day of week', () => {
    expect(parseCron('*/15 9-17 * 1,6 1-5')).toEqual({
      minute: { kind: 'step', every: 15 },
      hour: { kind: 'values', values: [9, 10, 11, 12, 13, 14, 15, 16, 17] },
      dayOfMonth: { kind: 'any' },
      month: { kind: 'values', values: [1, 6] },
      dayOfWeek: { kind: 'values', values: [1, 2, 3, 4, 5] },
    });
  });

  test('knows the names of months and days, in any case, and 7 is Sunday', () => {
    expect(parseCron('0 0 * jan-mar mon,SUN')).toMatchObject({
      month: { kind: 'values', values: [1, 2, 3] },
      dayOfWeek: { kind: 'values', values: [0, 1] },
    });
    expect(parseCron('0 0 * * 7')).toMatchObject({ dayOfWeek: { kind: 'values', values: [0] } });
  });

  test('has steps over a range and from a start', () => {
    expect(parseCron('0-30/10 * * * *')).toMatchObject({ minute: { kind: 'values', values: [0, 10, 20, 30] } });
    expect(parseCron('5/20 * * * *')).toMatchObject({ minute: { kind: 'values', values: [5, 25, 45] } });
  });

  test('is nothing when it is not five fields, has a value out of range or a form it does not know', () => {
    for (const bad of ['', '* * * *', '* * * * * *', '60 * * * *', '* 24 * * *', '* * 0 * *', '* * * 13 *', '* * * * 8', 'a b c d e', '*/0 * * * *', '5-1 * * * *', '@daily', '0 0 L * *', '0 0 ? * *']) {
      expect(parseCron(bad), bad).toBeNull();
    }
  });
});

describe('saying a cron expression in words', () => {
  test.each([
    ['* * * * *', 'Every minute'],
    ['*/5 * * * *', 'Every 5 minutes'],
    ['0 * * * *', 'Every hour, at minute 0'],
    ['15,45 * * * *', 'Every hour, at minutes 15 and 45'],
    ['30 9 * * *', 'At 09:30 every day'],
    ['0 9,18 * * *', 'At 09:00 and 18:00 every day'],
    ['30 9 * * 1-5', 'At 09:30, Monday to Friday'],
    ['0 0 1 * *', 'At 00:00, on day 1 of the month'],
    ['0 0 1,15 * *', 'At 00:00, on days 1 and 15 of the month'],
    ['0 8 * 1,6 *', 'At 08:00 every day, in January and June'],
    ['0 8 * * 0', 'At 08:00, on Sunday'],
    ['0 8 1 * 1', 'At 08:00, on day 1 of the month or on Monday'],
    ['*/10 * * * 1,3,5', 'Every 10 minutes, on Monday, Wednesday and Friday'],
    ['0 */2 * * *', 'At minute 0 of every 2 hours'],
    ['*/20 9-17 * * *', 'Every 20 minutes, during hours 9 to 17'],
    ['* 3 * * *', 'Every minute, during hour 3'],
    ['0 9-17 * * *', 'At minute 0 of hours 9 to 17'],
  ])('%s is: %s', (expression, words) => {
    expect(en(expression)).toBe(words);
  });

  test('in zh-TW as well', () => {
    expect(zh('* * * * *')).toBe('每分鐘');
    expect(zh('*/5 * * * *')).toBe('每 5 分鐘');
    expect(zh('30 9 * * *')).toBe('每天 09:30');
    expect(zh('30 9 * * 1-5')).toBe('09:30，星期一至星期五');
    expect(zh('0 0 1 * *')).toBe('00:00，每月 1 日');
  });

  test('is nothing for what is not read: the Engine decides what it accepts', () => {
    expect(en('not a cron')).toBeNull();
  });
});

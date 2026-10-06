// A cron expression read, and said in words, so that the person who writes one sees what it means
// before the Engine takes it (WI-36). Only the standard five fields are read here: the minute, the
// hour, the day of the month, the month and the day of the week, with lists, ranges, steps and the
// names of months and days. What this does not read (`@daily`, `L`, `?`, ...) is not said to be
// wrong: the Engine decides what it accepts, and the page says there is no preview.

import type { Locale } from '../i18n/locale';
import type { Translate } from '../i18n/translator';

export type CronField =
  | { kind: 'any' }
  /** The whole field is a step over everything, "every n-th value" (`*` and a slash and n). */
  | { kind: 'step'; every: number }
  | { kind: 'values'; values: number[] };

export interface CronFields {
  minute: CronField;
  hour: CronField;
  dayOfMonth: CronField;
  month: CronField;
  dayOfWeek: CronField;
}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
const DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];

interface Spec {
  min: number;
  max: number;
  /** The names of the values, from `min` on. */
  names?: string[];
}

const SPECS: Record<keyof CronFields, Spec> = {
  minute: { min: 0, max: 59 },
  hour: { min: 0, max: 23 },
  dayOfMonth: { min: 1, max: 31 },
  month: { min: 1, max: 12, names: MONTHS },
  dayOfWeek: { min: 0, max: 7, names: DAYS },
};

function number(text: string, spec: Spec): number | null {
  const named = spec.names?.indexOf(text.toLowerCase()) ?? -1;
  const value = named >= 0 ? named + spec.min : /^\d+$/.test(text) ? Number(text) : NaN;
  return Number.isInteger(value) && value >= spec.min && value <= spec.max ? value : null;
}

function parseField(text: string, spec: Spec): CronField | null {
  if (text === '*') return { kind: 'any' };
  const whole = /^\*\/(\d+)$/.exec(text);
  if (whole) {
    const every = Number(whole[1]);
    if (every < 1) return null;
    return every === 1 ? { kind: 'any' } : { kind: 'step', every };
  }
  const values = new Set<number>();
  for (const item of text.split(',')) {
    const [range, stepText, extra] = item.split('/');
    if (extra !== undefined || range === '' || range === undefined) return null;
    let step = 1;
    if (stepText !== undefined) {
      if (!/^\d+$/.test(stepText) || Number(stepText) < 1) return null;
      step = Number(stepText);
    }
    let from: number | null;
    let to: number | null;
    if (range === '*') {
      from = spec.min;
      to = spec.max;
    } else if (range.includes('-')) {
      const [a, b, more] = range.split('-');
      if (more !== undefined) return null;
      from = number(a, spec);
      to = number(b, spec);
    } else {
      from = number(range, spec);
      to = stepText === undefined ? from : spec.max;
    }
    if (from === null || to === null || from > to) return null;
    for (let value = from; value <= to; value += step) values.add(value);
  }
  return { kind: 'values', values: [...values].sort((a, b) => a - b) };
}

/** The five fields of [expression], or null when it is not a standard five-field expression. */
export function parseCron(expression: string): CronFields | null {
  const parts = expression.trim().split(/\s+/);
  if (parts.length !== 5) return null;
  const names = Object.keys(SPECS) as Array<keyof CronFields>;
  const fields: Partial<CronFields> = {};
  for (const [index, name] of names.entries()) {
    const field = parseField(parts[index], SPECS[name]);
    if (field === null) return null;
    fields[name] =
      name === 'dayOfWeek' && field.kind === 'values'
        ? { kind: 'values', values: [...new Set(field.values.map((v) => v % 7))].sort((a, b) => a - b) }
        : field;
  }
  return fields as CronFields;
}

// ---- in words ------------------------------------------------------------------------------------

const pad = (value: number) => String(value).padStart(2, '0');
const MAX_TIMES = 6;

/** The values as the pieces of a list: a run of three or more is one piece, "9 to 17". */
function pieces(
  values: number[],
  t: Translate,
  show: (value: number) => string,
): Array<{ text: string; range: boolean }> {
  const result: Array<{ text: string; range: boolean }> = [];
  for (let i = 0; i < values.length; ) {
    let j = i;
    while (j + 1 < values.length && values[j + 1] === values[j] + 1) j += 1;
    if (j - i >= 2) {
      result.push({ text: t('cron.range', { from: show(values[i]), to: show(values[j]) }), range: true });
      i = j + 1;
    } else {
      result.push({ text: show(values[i]), range: false });
      i += 1;
    }
  }
  return result;
}

export function describeCron(expression: string, t: Translate, locale: Locale): string | null {
  const fields = parseCron(expression);
  if (fields === null) return null;
  const listFormat = new Intl.ListFormat(locale === 'en' ? 'en-GB' : locale, {
    type: 'conjunction',
    style: 'long',
  });
  const list = (items: string[]) => listFormat.format(items);
  const plain = (value: number) => String(value);
  const numbers = (values: number[]) => list(pieces(values, t, plain).map((p) => p.text));

  const { minute, hour, dayOfMonth, month } = fields;
  // A weekday field that holds every day is no restriction; a step over the week is its values.
  let dayOfWeek = fields.dayOfWeek;
  if (dayOfWeek.kind === 'step') {
    const values: number[] = [];
    for (let v = 0; v <= 6; v += dayOfWeek.every) values.push(v);
    dayOfWeek = { kind: 'values', values };
  }
  if (dayOfWeek.kind === 'values' && dayOfWeek.values.length === 7) dayOfWeek = { kind: 'any' };

  // ---- the days ------------------------------------------------------------------------------
  const weekdayName = (day: number) =>
    new Intl.DateTimeFormat(locale, { weekday: 'long', timeZone: 'UTC' }).format(
      new Date(Date.UTC(2023, 0, 1 + day)),
    );
  const monthName = (value: number) =>
    new Intl.DateTimeFormat(locale, { month: 'long', timeZone: 'UTC' }).format(
      new Date(Date.UTC(2023, value - 1, 1)),
    );

  let weekdays: string | null = null;
  if (dayOfWeek.kind === 'values') {
    const parts = pieces(dayOfWeek.values, t, weekdayName);
    weekdays =
      parts.length === 1 && parts[0].range
        ? parts[0].text
        : t('cron.days.weekList', { list: list(parts.map((p) => p.text)) });
  }
  let monthDays: string | null = null;
  if (dayOfMonth.kind === 'step') {
    monthDays = t('cron.days.everyDays', { n: dayOfMonth.every });
  } else if (dayOfMonth.kind === 'values') {
    monthDays = t('cron.days.monthDays', {
      count: dayOfMonth.values.length,
      list: numbers(dayOfMonth.values),
    });
  }
  const dayClause =
    weekdays !== null && monthDays !== null
      ? t('cron.days.or', { a: monthDays, b: weekdays })
      : (weekdays ?? monthDays);

  let monthClause: string | null = null;
  if (month.kind === 'step') {
    monthClause = t('cron.month.every', { n: month.every });
  } else if (month.kind === 'values') {
    monthClause = t('cron.month.in', { list: list(pieces(month.values, t, monthName).map((p) => p.text)) });
  }

  // ---- the time --------------------------------------------------------------------------------
  const unit = (count: number) => ({ count });
  let time: string;
  let timeOfDay = false;
  if (minute.kind === 'values' && hour.kind === 'values' && minute.values.length * hour.values.length <= MAX_TIMES) {
    const times = hour.values.flatMap((h) => minute.values.map((m) => `${pad(h)}:${pad(m)}`));
    time = t('cron.time.at', { times: list(times) });
    timeOfDay = true;
  } else if (minute.kind === 'any' && hour.kind === 'any') {
    time = t('cron.time.everyMinute');
  } else if (minute.kind === 'step' && hour.kind === 'any') {
    time = t('cron.time.everyMinutes', { n: minute.every });
  } else if (minute.kind === 'values' && hour.kind === 'any') {
    time = t('cron.time.hourlyAt', { ...unit(minute.values.length), list: numbers(minute.values) });
  } else if (minute.kind === 'any' && hour.kind === 'values') {
    time = t('cron.time.everyMinuteDuring', { ...unit(hour.values.length), list: numbers(hour.values) });
  } else if (minute.kind === 'step' && hour.kind === 'values') {
    time = t('cron.time.everyMinutesDuring', {
      n: minute.every,
      ...unit(hour.values.length),
      list: numbers(hour.values),
    });
  } else if (minute.kind === 'values' && hour.kind === 'values') {
    time = t('cron.time.minuteOfHours', {
      count: minute.values.length,
      minutes: numbers(minute.values),
      hourCount: hour.values.length,
      hours: numbers(hour.values),
    });
  } else if (minute.kind === 'values' && hour.kind === 'step') {
    time = t('cron.time.minuteOfEveryHours', {
      count: minute.values.length,
      minutes: numbers(minute.values),
      n: hour.every,
    });
  } else if (minute.kind === 'any' && hour.kind === 'step') {
    time = t('cron.time.everyMinuteOfEveryHours', { n: hour.every });
  } else {
    // Every n minutes of every m hours: said as two parts.
    const m = minute.kind === 'step' ? t('cron.time.everyMinutes', { n: minute.every }) : '';
    time = t('cron.time.everyMinutesOfEveryHours', { minutes: m, n: hour.kind === 'step' ? hour.every : 1 });
  }

  const clauses: string[] = [];
  if (timeOfDay && dayClause === null) {
    clauses.push(t('cron.time.everyDay', { time }));
  } else {
    clauses.push(time);
    if (dayClause !== null) clauses.push(dayClause);
  }
  if (monthClause !== null) clauses.push(monthClause);
  return clauses.reduce((all, next) => t('cron.join', { first: all, second: next }));
}

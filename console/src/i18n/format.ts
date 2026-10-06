// Dates, numbers, sizes and durations in the language of the screen (ADR-015), with the browser's
// own Intl. The API sends ISO-8601 UTC; identifiers (hash, runId, cron, class names) are never
// passed through here.

import type { Locale } from './locale';

const isTime = (iso: string) => !Number.isNaN(Date.parse(iso));

/** The instant as local time (the browser's time zone unless [timeZone] says one), in the language. */
export function formatDateTime(iso: string, locale: Locale, timeZone?: string): string {
  if (!isTime(iso)) return iso;
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'medium', timeZone })
    .format(new Date(iso))
    .replace(/\u202f/g, ' '); // ICU puts a narrow no-break space before AM/PM
}

/** What the tooltip of a local time shows: the UTC value as the API sent it. */
export const utcOriginal = (iso: string): string => iso;

export const formatNumber = (value: number, locale: Locale): string =>
  new Intl.NumberFormat(locale).format(value);

const BYTE_UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];

export function formatBytes(bytes: number, locale: Locale): string {
  let value = bytes;
  let unit = 0;
  while (Math.abs(value) >= 1024 && unit < BYTE_UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const digits = unit === 0 ? 0 : 1;
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: digits }).format(value);
  return `${number} ${BYTE_UNITS[unit]}`;
}

const DURATION_UNITS = [
  ['day', 86_400_000],
  ['hour', 3_600_000],
  ['minute', 60_000],
  ['second', 1_000],
] as const;

/** The two largest units that are not zero ("1m 24s"); under a second, milliseconds. */
export function formatDuration(milliseconds: number, locale: Locale): string {
  const unitFormat = (unit: string) =>
    new Intl.NumberFormat(locale, { style: 'unit', unit, unitDisplay: 'narrow' });
  if (milliseconds === 0) return unitFormat('second').format(0);
  if (milliseconds < 1_000) return unitFormat('millisecond').format(Math.round(milliseconds));

  const parts: string[] = [];
  let rest = Math.floor(milliseconds);
  for (const [unit, size] of DURATION_UNITS) {
    const count = Math.floor(rest / size);
    rest -= count * size;
    if (count > 0 && parts.length < 2) parts.push(unitFormat(unit).format(count));
  }
  return parts.join(' ');
}

const RELATIVE_UNITS = [
  ['year', 31_536_000],
  ['month', 2_592_000],
  ['day', 86_400],
  ['hour', 3_600],
  ['minute', 60],
  ['second', 1],
] as const;

/** "3 minutes ago", "in 30 seconds": the unit that fits the distance, in the language. */
export function formatRelative(iso: string, now: Date, locale: Locale): string {
  if (!isTime(iso)) return iso;
  const seconds = (Date.parse(iso) - now.getTime()) / 1000;
  const format = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  for (const [unit, size] of RELATIVE_UNITS) {
    if (Math.abs(seconds) >= size || unit === 'second') {
      return format.format(Math.trunc(seconds / size), unit);
    }
  }
  return iso;
}

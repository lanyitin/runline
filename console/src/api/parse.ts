// The small readers that the models of the Engine's answers are made of: each says what it wanted
// when the answer is not what 08-api.md says, as a failure (`ApiFailure` with status 0) and not as
// a screen that breaks half way.

import { ApiFailure } from './failure.ts';

export const bad = (what: string) =>
  new ApiFailure(0, null, `an answer of the Engine is not as 08-api.md says: ${what}`);

export type Obj = Record<string, unknown>;
export const obj = (value: unknown, what: string): Obj => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw bad(what);
  return value as Obj;
};
export const str = (value: unknown, what: string): string => {
  if (typeof value !== 'string') throw bad(what);
  return value;
};
export const strOrNull = (value: unknown, what: string): string | null => {
  if (value === null || value === undefined) return null;
  return str(value, what);
};
export const bool = (value: unknown, what: string): boolean => {
  if (typeof value !== 'boolean') throw bad(what);
  return value;
};
export const num = (value: unknown, what: string): number => {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw bad(what);
  return value;
};
export const list = <T>(value: unknown, what: string, item: (v: unknown, i: number) => T): T[] => {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw bad(what);
  return value.map(item);
};
export const strings = (value: unknown, what: string): string[] => list(value, what, (v) => str(v, what));

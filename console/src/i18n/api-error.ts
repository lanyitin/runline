import type { Translate } from './translator';

/** An API error in the language of the screen (ADR-015), ready to show. */
export interface DescribedApiError {
  /** The text for the person: from the translation of the code, never from the server's message. */
  message: string;
  /** The stable `error` code of the answer, if the body had one. */
  code: string | null;
  /** False when the Console has no translation for the code: `message` is then the generic text. */
  known: boolean;
  status: number;
  /** Of an internal error (500): what to give when reporting it. */
  errorId: string | null;
  /** One line per `problems[]` item, or for the single `problem` of the answer. */
  problems: string[];
  /** The server's `message`, untranslated: only for the "details", labelled as the server's text. */
  serverMessage: string | null;
}

const text = (value: unknown): string | null => (typeof value === 'string' ? value : null);

function problemLines(t: Translate, body: Record<string, unknown>): string[] {
  const lines: string[] = [];
  const line = (subject: string | null, problem: string) => {
    const key = `error.problem.${problem}`;
    if (t.has(key)) return t(key, subject === null ? undefined : { subject });
    return subject !== null ? `${subject}: ${problem}` : problem;
  };

  const single = text(body.problem);
  if (single) lines.push(line(null, single));
  if (Array.isArray(body.problems)) {
    for (const item of body.problems) {
      if (typeof item !== 'object' || item === null) continue;
      const entry = item as Record<string, unknown>;
      const problem = text(entry.problem);
      if (problem) lines.push(line(text(entry.name) ?? text(entry.resource) ?? '', problem));
    }
  }
  return lines;
}

/**
 * Maps an error answer of the API to what the Console shows. The `error` code (with the HTTP status
 * and the structured `problem`/`problems`) selects the translation; `message` is the server's
 * wording, which may change, so it is only handed on apart, for the details.
 */
export function describeApiError(t: Translate, status: number, body: unknown): DescribedApiError {
  const record =
    typeof body === 'object' && body !== null && !Array.isArray(body)
      ? (body as Record<string, unknown>)
      : {};
  const code = text(record.error);

  const base = {
    code,
    status,
    errorId: text(record.errorId),
    problems: problemLines(t, record),
    serverMessage: text(record.message),
  };

  if (code && t.has(`error.${code}`)) {
    return { ...base, message: t(`error.${code}`), known: true };
  }
  if (code) {
    return { ...base, message: t('error.unknown', { code }), known: false };
  }
  if (t.has(`error.http.${status}`)) {
    return { ...base, message: t(`error.http.${status}`), known: true };
  }
  return { ...base, message: t('error.unknown.noCode', { status }), known: false };
}

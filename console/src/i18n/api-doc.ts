// What 08-api.md says, read for the checks of the translations (ADR-015: the document is the single
// source of truth for the error codes and the enumerations the Console must translate).

const STATUS_AND_CODE = /\b[45]\d\d `([a-z][a-z0-9]*(?:_[a-z0-9]+)*)`/g;
const CODE_TABLE_HEADING = /的 `error`：/;
const FIRST_CELL_CODE = /^\| `([a-z][a-z0-9]*(?:_[a-z0-9]+)*)` \|/;

/**
 * The error codes of the API: a code that follows an HTTP status of 4xx or 5xx ("409 `in_use`"),
 * and the codes in the first column of the table that follows a line "... 的 `error`：" (the 422
 * codes of an upload).
 */
export function documentedErrorCodes(markdown: string): string[] {
  const codes = new Set<string>();
  for (const match of markdown.matchAll(STATUS_AND_CODE)) codes.add(match[1]);

  const lines = markdown.split('\n');
  const heading = lines.findIndex((line) => CODE_TABLE_HEADING.test(line));
  if (heading >= 0) {
    for (const line of lines.slice(heading + 1).filter((l) => l.trim() !== '')) {
      if (!line.startsWith('|')) break;
      const code = FIRST_CELL_CODE.exec(line)?.[1];
      if (code) codes.add(code);
    }
  }
  return [...codes].sort();
}

/** The `UPPER_CASE` words in backticks of the first sentence after `label`. */
function listedAfter(markdown: string, label: string): string[] {
  const start = markdown.indexOf(label);
  if (start < 0) return [];
  const sentence = markdown.slice(start + label.length).split('。')[0];
  return [...sentence.matchAll(/`([A-Z][A-Z_]+)`/g)].map((m) => m[1]);
}

export const documentedReasonKinds = (markdown: string): string[] =>
  listedAfter(markdown, '`reasons[].kind` 的值：');

export const documentedRunStates = (markdown: string): string[] =>
  listedAfter(markdown, '`state` 的值：');

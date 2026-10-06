import { describe, expect, test } from 'vitest';
import { documentedErrorCodes, documentedReasonKinds, documentedRunStates } from './api-doc';

const sample = `
| 状態 | 意義 |
|---|---|
| 413 \`too_large\` | big |
| 409 \`already_finished\` | done |
| 401 \`unauthorized\` | no |
| 200 | fine |

422 的 \`error\`：

| 代碼 | 意義 |
|---|---|
| \`not_a_jar\` | bad |
| \`jar_too_many_entries\` | many |

Text: 404 \`run_not_found\`；500 \`internal_error\`；\`problem\` 為 \`unknown\` 或 \`disabled\`，欄位 \`class_name\`。
400 \`bad_request\`

\`reasons[].kind\` 的值：\`UNRESTRICTED_ACCESS\`（網路，\`category\`）、\`JVM_EXIT\`（參照，\`member\`）、\`IO_SENSITIVE_MEMBER\`（IO，[ADR-013](x)）、\`LIMIT_EXCEEDED\`（超出，\`detail\`）。新增 \`NOT_A_KIND\` 之前。

Run: \`state\` 的值：\`QUEUED\`、\`RUNNING\`，終止狀態 \`SUCCEEDED\`、\`TIMED_OUT\`。
`;

describe('documentedErrorCodes', () => {
  test('finds the codes after a status, and the codes in the first column of a code table', () => {
    expect(documentedErrorCodes(sample)).toEqual(
      [
        'already_finished',
        'bad_request',
        'internal_error',
        'jar_too_many_entries',
        'not_a_jar',
        'run_not_found',
        'too_large',
        'unauthorized',
      ].sort(),
    );
  });

  test('knows nothing of lower case words that are not error codes', () => {
    const codes = documentedErrorCodes(sample);
    for (const word of ['problem', 'unknown', 'disabled', 'class_name']) {
      expect(codes).not.toContain(word);
    }
  });
});

describe('the enumerations the document lists', () => {
  test('reasons[].kind: the upper case words of that sentence up to its end', () => {
    expect(documentedReasonKinds(sample)).toEqual([
      'UNRESTRICTED_ACCESS',
      'JVM_EXIT',
      'IO_SENSITIVE_MEMBER',
      'LIMIT_EXCEEDED',
    ]);
  });

  test('run states', () => {
    expect(documentedRunStates(sample)).toEqual(['QUEUED', 'RUNNING', 'SUCCEEDED', 'TIMED_OUT']);
  });

  test('a document that lists none yields none, so that the check can say so', () => {
    expect(documentedRunStates('nothing')).toEqual([]);
  });
});

import { describe, expect, test } from 'vitest';
import { describeApiError } from './api-error';
import { createTranslator } from './translator';

const catalogs = {
  en: {
    'error.run_not_found': 'The run does not exist or is not yours.',
    'error.invalid_parameters': 'The parameters do not fit the pipeline.',
    'error.problem.missing': 'Parameter {subject} is required.',
    'error.problem.undeclared': 'Parameter {subject} is not declared.',
    'error.problem.cron_required': 'A cron trigger needs a cron expression.',
    'error.http.401': 'You are not signed in.',
    'error.unknown': 'Something went wrong (code {code}).',
    'error.unknown.noCode': 'Something went wrong (HTTP {status}).',
  },
  'zh-TW': {
    'error.run_not_found': '找不到這個 run，或它不是你的。',
    'error.invalid_parameters': '參數與 pipeline 的宣告不符。',
    'error.problem.missing': '缺少必填參數 {subject}。',
    'error.problem.undeclared': '參數 {subject} 未宣告。',
    'error.problem.cron_required': 'cron trigger 需要 cron 表達式。',
    'error.http.401': '尚未登入。',
    'error.unknown': '發生錯誤（代碼 {code}）。',
    'error.unknown.noCode': '發生錯誤（HTTP {status}）。',
  },
};
const en = createTranslator(catalogs, 'en');
const zh = createTranslator(catalogs, 'zh-TW');

describe('describeApiError', () => {
  test('a known code is shown by the text of the language, not by the server message', () => {
    const body = { error: 'run_not_found', message: 'Run 123 was not found' };
    expect(describeApiError(zh, 404, body).message).toBe('找不到這個 run，或它不是你的。');
    expect(describeApiError(en, 404, body).message).toBe('The run does not exist or is not yours.');
  });

  test('the server message is kept apart, to be shown as the server original', () => {
    const described = describeApiError(en, 404, { error: 'run_not_found', message: 'Run 7 gone' });
    expect(described.serverMessage).toBe('Run 7 gone');
    expect(described.message).not.toContain('Run 7 gone');
    expect(described.code).toBe('run_not_found');
  });

  test('the problems of a code are listed, each with its subject', () => {
    const described = describeApiError(zh, 422, {
      error: 'invalid_parameters',
      message: 'x',
      problems: [
        { name: 'limit', problem: 'missing' },
        { name: 'extra', problem: 'undeclared' },
      ],
    });
    expect(described.problems).toEqual(['缺少必填參數 limit。', '參數 extra 未宣告。']);
  });

  test('the subject of a problem can be a resource', () => {
    const described = describeApiError(en, 409, {
      error: 'resources_unavailable',
      problems: [{ resource: 'gpu', problem: 'missing' }],
    });
    expect(described.problems).toEqual(['Parameter gpu is required.']);
  });

  test('the single problem of an answer has a text of its own, without a subject', () => {
    const described = describeApiError(en, 422, {
      error: 'invalid_trigger',
      problem: 'cron_required',
    });
    expect(described.problems).toEqual(['A cron trigger needs a cron expression.']);
  });

  test('a code with no translation gets the generic text with the code, and the server message apart', () => {
    const described = describeApiError(en, 418, { error: 'teapot_overflow', message: 'brew' });
    expect(described.message).toBe('Something went wrong (code teapot_overflow).');
    expect(described.known).toBe(false);
    expect(described.serverMessage).toBe('brew');
  });

  test('an internal error carries its errorId', () => {
    const described = describeApiError(en, 500, {
      error: 'internal_error',
      message: 'boom',
      errorId: 'e-42',
    });
    expect(described.errorId).toBe('e-42');
    expect(described.message).toContain('internal_error');
  });

  test('a problem with no translation is shown as its code', () => {
    const described = describeApiError(en, 422, {
      error: 'invalid_parameters',
      problems: [{ name: 'p', problem: 'brand_new' }],
    });
    expect(described.problems).toEqual(['p: brand_new']);
  });

  test('an answer without a body is understood by its status', () => {
    expect(describeApiError(en, 401, undefined).message).toBe('You are not signed in.');
    expect(describeApiError(en, 502, null).message).toBe('Something went wrong (HTTP 502).');
  });

  test('a body that is not what the API sends is treated as no body', () => {
    expect(describeApiError(en, 401, 'text').message).toBe('You are not signed in.');
    expect(describeApiError(en, 401, { error: 5 }).message).toBe('You are not signed in.');
  });
});

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';
import { FakeEngine } from '../test-support/fake-engine';
import { storedZip } from '../test-support/zip';
import type { DemoJars } from '../test-support/fake-jars';
import { describeAdminContract } from './admin-contract';
import { describeInfoContract } from './info-contract';
import { describeResourceTypesContract } from './resource-types-contract';
import { describePipelinesContract } from './pipelines-contract';
import { describeSystemContract, parseCallers } from './system-contract';

// Run by `npm run test:contract` against a running, packaged Engine. It is no part of `npm test`:
// it needs an Engine and its PostgreSQL. With no RUNLINE_ENGINE_URL, or no RUNLINE_CONTRACT_TOKENS
// (the Engine's API_TOKENS: `name:role:token,...`, with two developers and an admin in it), or no
// RUNLINE_CONTRACT_JARS (the directory with the sample pipelines' jars, `demo-slow.jar`, ...), it
// fails; it is never skipped.
const url = process.env.RUNLINE_ENGINE_URL;
if (!url) {
  throw new Error('RUNLINE_ENGINE_URL is not set: the contract test needs a running Engine');
}
const tokens = process.env.RUNLINE_CONTRACT_TOKENS;
if (!tokens) {
  throw new Error(
    'RUNLINE_CONTRACT_TOKENS is not set: name:role:token of two developers and an admin',
  );
}
const callers = parseCallers(tokens);
if (callers.filter((c) => c.role === 'developer').length < 2) {
  throw new Error('RUNLINE_CONTRACT_TOKENS must have two developers: visibility is tested');
}
const jarDirectory = process.env.RUNLINE_CONTRACT_JARS;
if (!jarDirectory) {
  throw new Error(
    'RUNLINE_CONTRACT_JARS is not set: the directory of the sample jars (dev/sample-pipelines/build/pipelines)',
  );
}
// The Engine's UPLOAD_MAX_BYTES, if it was set; its default is 50 MiB.
const uploadLimitBytes = Number(process.env.RUNLINE_CONTRACT_UPLOAD_LIMIT_BYTES ?? 50 * 1024 * 1024);

const jar = (name: string): Uint8Array => readFileSync(join(jarDirectory, `${name}.jar`));
const jars = (): DemoJars => ({
  slow: jar('demo-slow'),
  failing: jar('demo-failing'),
  unsafe: jar('demo-unsafe'),
  resource: jar('demo-resource'),
  typed: jar('demo-typed'),
  noPipeline: storedZip({ 'hello.txt': 'there is no pipeline here' }),
  junk: new TextEncoder().encode('this is not a jar'),
});

describeInfoContract('the real Engine', () => url);
describeSystemContract('the real Engine', () => url, () => callers);
describePipelinesContract('the real Engine', {
  baseUrl: () => url,
  callers: () => callers,
  jars,
  uploadLimitBytes,
});
describeAdminContract('the real Engine', {
  baseUrl: () => url,
  callers: () => callers,
  jars: jars,
  uploadLimitBytes,
});
describeResourceTypesContract('the real Engine', {
  baseUrl: () => url,
  callers: () => callers,
  jars,
  uploadLimitBytes,
});

// The Fake Engine's catalog is a copy of the Engine's (test-support/fake-resource-types.ts): the
// two answers must be the same, entry for entry, so that what the Console is tested with is what
// the Engine tells.
describe('the resource types of the real Engine and of the Fake Engine', () => {
  test('are the same answer', async () => {
    const admin = callers.find((c) => c.role === 'admin')!;
    const fake = await FakeEngine.start(
      { version: '0.0.0', commitHash: '0'.repeat(40), dirty: false },
      { callers: [admin] },
    );
    try {
      const read = async (base: string) => {
        const response = await fetch(`${base}/api/v1/resource-types`, {
          headers: { Authorization: `Bearer ${admin.token}` },
        });
        expect(response.status).toBe(200);
        return response.json();
      };
      expect(await read(url)).toEqual(await read(fake.url));
    } finally {
      await fake.stop();
    }
  });
});

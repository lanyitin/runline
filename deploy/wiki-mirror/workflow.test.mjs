// The shape of .forgejo/workflows/sync-wiki.yaml that the acceptance conditions of work item #2 rest on.
// Read as text: the workflow has no YAML parser to lean on, and each check is a line the file must hold.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const workflow = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), "../../.forgejo/workflows/sync-wiki.yaml"),
  "utf8",
);
const code = workflow
  .split("\n")
  .filter((line) => !line.trim().startsWith("#"))
  .join("\n");

test("it runs only on pushes to main that touch docs/stable", () => {
  assert.match(code, /on:\n {2}push:\n {4}branches:\n {6}- main\n {4}paths:\n {6}- "docs\/stable\/\*\*"\n(?!\s+-)/);
  assert.doesNotMatch(code, /^ {2}(pull_request|schedule|workflow_dispatch|release)/m);
  assert.doesNotMatch(code, /tags:/);
});

test("it asks for explicit permissions and not the wide ones", () => {
  assert.match(code, /permissions:\n {6}contents: write\n/);
  assert.doesNotMatch(code, /write-all|read-all/);
  assert.equal((code.match(/permissions:/g) ?? []).length, 1);
});

test("the credential is the automatic token and no secret", () => {
  assert.match(code, /WIKI_TOKEN: \$\{\{ github\.token \}\}/);
  assert.doesNotMatch(code, /secrets\./);
});

test("two syncs do not run at once and a waiting one is not cancelled", () => {
  assert.match(code, /concurrency:\n {2}group: sync-wiki\n {2}cancel-in-progress: false/);
});

test("it does not turn certificate verification off", () => {
  assert.doesNotMatch(code, /sslVerify|NODE_TLS_REJECT_UNAUTHORIZED|--insecure|-k /i);
});

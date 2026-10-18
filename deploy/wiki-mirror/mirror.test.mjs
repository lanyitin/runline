// What the mirror of docs/stable holds: pages, rewritten links, the Home page and the sidebar.
// The fixtures are real directory trees; the last tests run on the repository's own docs/stable.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildMirror, verifyMirror } from "./mirror.mjs";
import { pageNameOfWikiFile, wikiFileNameOf } from "./pages.mjs";

const repoUrl = "https://forge.example/acme/proj";
const wiki = `${repoUrl}/wiki`;

const repoWith = (files) => {
  const root = mkdtempSync(join(tmpdir(), "mirror-test-"));
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), content);
  }
  return root;
};
const mirrorOf = (files) => {
  const root = repoWith(files);
  try {
    return buildMirror({ repoRoot: root, repoUrl, ref: "main" });
  } finally {
    rmSync(root, { recursive: true });
  }
};
const pagesOf = (mirror) => [...mirror.files.keys()].map(pageNameOfWikiFile).sort();
const pageText = (mirror, page) => mirror.files.get(wikiFileNameOf(page));

const sample = {
  "docs/stable/dev.md": "# Dev guide\n",
  "docs/stable/alpha/README.md": "# Alpha topic\nsee [req](01-requirements.md) and [adr](adr/)\n",
  "docs/stable/alpha/01-requirements.md": "# Req\n[up](README.md#top)\n",
  "docs/stable/alpha/adr/README.md": "# Alpha decisions\n",
  "docs/stable/alpha/adr/1-x.md": "# X\n",
  "docs/stable/beta/README.md": "# Beta topic\n",
  "docs/stable/beta/01-requirements.md": "# Beta req\n",
  "docs/draft/alpha/idea.md": "# draft\n",
  "src/Main.kt": "fun main() {}\n",
};

test("the pages are the Markdown files of stable, one to one, plus Home and the sidebar", () => {
  const m = mirrorOf(sample);
  assert.deepEqual(pagesOf(m), [
    "Home",
    "_Sidebar",
    "alpha/01-requirements",
    "alpha/README",
    "alpha/adr/1-x",
    "alpha/adr/README",
    "beta/01-requirements",
    "beta/README",
    "dev",
  ]);
});

test("the same file name under two topics gives two pages", () => {
  const m = mirrorOf(sample);
  assert.ok(pageText(m, "alpha/01-requirements").includes("# Req"));
  assert.ok(pageText(m, "beta/01-requirements").includes("# Beta req"));
});

test("draft documents and files outside stable are not in the mirror", () => {
  const text = [...mirrorOf(sample).files.values()].join("\n");
  assert.ok(!text.includes("idea"));
  assert.ok(!pagesOf(mirrorOf(sample)).some((p) => p.includes("draft")));
  assert.ok(!text.includes("fun main"));
});

test("a non-Markdown file of stable is not a page", () => {
  const m = mirrorOf({ ...sample, "docs/stable/alpha/data.json": "{}" });
  assert.ok(!pagesOf(m).some((p) => p.includes("data")));
});

test("links in the pages point to Wiki pages", () => {
  const m = mirrorOf(sample);
  assert.ok(pageText(m, "alpha/README").includes(`[req](${wiki}/alpha/01-requirements)`));
  assert.ok(pageText(m, "alpha/README").includes(`[adr](${wiki}/alpha/adr/README)`));
  assert.ok(pageText(m, "alpha/01-requirements").includes(`[up](${wiki}/alpha/README#top)`));
});

test("dropped links are reported with the document they are in", () => {
  const m = mirrorOf({ ...sample, "docs/stable/beta/README.md": "# B\n[x](../../../other-repo/a.md)\n" });
  assert.deepEqual(m.dropped, [{ source: "docs/stable/beta/README.md", dest: "../../../other-repo/a.md" }]);
});

test("Home and the sidebar list the entry documents by topic, in a stable order", () => {
  const m = mirrorOf(sample);
  const entries = [
    `- [Alpha topic](${wiki}/alpha/README)`,
    `  - [Alpha decisions](${wiki}/alpha/adr/README)`,
    `- [Beta topic](${wiki}/beta/README)`,
    `- [Dev guide](${wiki}/dev)`,
  ].join("\n");
  assert.ok(pageText(m, "Home").includes(entries), pageText(m, "Home"));
  assert.ok(pageText(m, "_Sidebar").includes(entries), pageText(m, "_Sidebar"));
  assert.ok(pageText(m, "_Sidebar").includes(`[首頁](${wiki}/Home)`));
});

test("building twice from the same stable gives the same files", () => {
  assert.deepEqual([...mirrorOf(sample).files], [...mirrorOf(sample).files]);
});

test("a deleted document is gone from the next mirror", () => {
  const { "docs/stable/beta/01-requirements.md": _gone, ...without } = sample;
  assert.ok(!pagesOf(mirrorOf(without)).includes("beta/01-requirements"));
});

test("a stable document may not take the name of a page the Wiki reserves", () => {
  for (const name of ["Home", "_Sidebar", "_Footer"]) {
    assert.throws(() => mirrorOf({ ...sample, [`docs/stable/${name}.md`]: "# x\n" }), /reserved/);
  }
});

test("a repository without docs/stable is an error, not an empty Wiki", () => {
  assert.throws(() => mirrorOf({ "README.md": "# x\n" }), /docs\/stable/);
});

test("the verification fails on a link to a page that is not in the mirror", () => {
  const m = mirrorOf(sample);
  m.files.set(wikiFileNameOf("alpha/README"), `[no](${wiki}/alpha/missing)`);
  assert.throws(() => verifyMirror(m, repoUrl), /alpha\/missing/);
});

test("the verification passes on a mirror built from stable", () => {
  verifyMirror(mirrorOf(sample), repoUrl);
});

// The repository's own stable, as a sample.
const here = dirname(fileURLToPath(import.meta.url));
const realRoot = resolve(here, "../..");
const markdownFilesUnder = (dir) =>
  readdirSync(dir, { withFileTypes: true, recursive: true })
    .filter((e) => e.isFile() && e.name.endsWith(".md"))
    .map((e) => join(e.parentPath, e.name));

test("on the repository's stable: one page per document and no link to a missing page", () => {
  const m = buildMirror({ repoRoot: realRoot, repoUrl, ref: "main" });
  verifyMirror(m, repoUrl);
  const documents = markdownFilesUnder(join(realRoot, "docs/stable")).length;
  assert.equal(m.files.size, documents + 2);
  assert.ok(pagesOf(m).includes("wiki-mirror/README"));
});

test("on the repository's stable: building does not change stable", () => {
  const snapshot = () =>
    markdownFilesUnder(join(realRoot, "docs/stable")).map((f) => readFileSync(f, "utf8"));
  const before = snapshot();
  buildMirror({ repoRoot: realRoot, repoUrl, ref: "main" });
  assert.deepEqual(snapshot(), before);
});

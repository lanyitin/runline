// The link table of ADR 6 (docs/stable/wiki-mirror/adr/6-mirror-mapping-links-deletion-credential.md).
import { test } from "node:test";
import assert from "node:assert/strict";
import { rewriteLinks, wikiLinks } from "./links.mjs";

const repoUrl = "https://forge.example/acme/proj";
const wiki = `${repoUrl}/wiki`;
const main = `${repoUrl}/src/branch/main`;

// The repository the links are resolved against: path -> "file" | "dir".
const tree = {
  "docs/stable/t/a.md": "file",
  "docs/stable/t/b.md": "file",
  "docs/stable/t/README.md": "file",
  "docs/stable/t/adr": "dir",
  "docs/stable/t/adr/README.md": "file",
  "docs/stable/t/adr/x-1.md": "file",
  "docs/stable/t/loose": "dir",
  "docs/stable/t/loose/y.md": "file",
  "docs/stable/t/data.json": "file",
  "docs/stable": "dir",
  "docs/stable/t": "dir",
  "core/src/Main.kt": "file",
  "core": "dir",
};
const ctx = (source = "docs/stable/t/a.md") => ({
  source,
  repoUrl,
  ref: "main",
  kindOf: (path) => tree[path] ?? null,
});
const rewrite = (md, source) => rewriteLinks(md, ctx(source));

test("a link to a stable Markdown file becomes a link to its Wiki page", () => {
  assert.equal(rewrite("see [b](b.md)").content, `see [b](${wiki}/t/b)`);
});

test("the anchor of a link to a stable file is kept", () => {
  assert.equal(rewrite("[b](b.md#sec-1)").content, `[b](${wiki}/t/b#sec-1)`);
});

test("a relative path across directories resolves from the source file", () => {
  assert.equal(rewrite("[a](../a.md)", "docs/stable/t/adr/x-1.md").content, `[a](${wiki}/t/a)`);
});

test("a link to a directory with a README goes to the README page", () => {
  assert.equal(rewrite("[adr](adr/)").content, `[adr](${wiki}/t/adr/README)`);
  assert.equal(rewrite("[adr](adr)").content, `[adr](${wiki}/t/adr/README)`);
});

test("a link to a directory without a README goes to the Home page", () => {
  assert.equal(rewrite("[l](loose/)").content, `[l](${wiki}/Home)`);
});

test("an anchor inside the page is unchanged", () => {
  assert.equal(rewrite("[here](#sec)").content, "[here](#sec)");
});

test("an external URL is unchanged", () => {
  const md = "[x](https://example.org/a.md#b) and [m](mailto:a@b.c)";
  assert.equal(rewrite(md).content, md);
});

test("a link to a file of the repository outside stable goes to that file on main", () => {
  assert.equal(
    rewrite("[m](../../../core/src/Main.kt#L3)").content,
    `[m](${main}/core/src/Main.kt#L3)`,
  );
});

test("a link to a directory of the repository outside stable goes to that directory on main", () => {
  assert.equal(rewrite("[c](../../../core/)").content, `[c](${main}/core)`);
});

test("a stable file that is not Markdown goes to that file on main", () => {
  assert.equal(rewrite("[d](data.json)").content, `[d](${main}/docs/stable/t/data.json)`);
});

test("a link that leaves the repository keeps its text and is reported", () => {
  const r = rewrite("see [feed](../../../../runline-digest-rss/README.md) now");
  assert.equal(r.content, "see feed now");
  assert.deepEqual(r.dropped, ["../../../../runline-digest-rss/README.md"]);
});

test("a link to a file that does not exist keeps its text and is reported", () => {
  const r = rewrite("[gone](gone.md#x)");
  assert.equal(r.content, "gone");
  assert.deepEqual(r.dropped, ["gone.md#x"]);
});

test("a link with a title keeps the title", () => {
  assert.equal(rewrite('[b](b.md "the B")').content, `[b](${wiki}/t/b "the B")`);
});

test("a destination in angle brackets and with percent escapes resolves", () => {
  assert.equal(rewrite("[b](<b.md#s>)").content, `[b](${wiki}/t/b#s)`);
  assert.equal(rewrite("[b](%62.md)").content, `[b](${wiki}/t/b)`);
});

test("link text with code and brackets is kept whole", () => {
  assert.equal(rewrite("[`f[0]` x](b.md)").content, `[\`f[0]\` x](${wiki}/t/b)`);
});

test("several links on one line are all rewritten", () => {
  assert.equal(rewrite("[a](a.md), [b](b.md)").content, `[a](${wiki}/t/a), [b](${wiki}/t/b)`);
});

test("links inside inline code are not touched", () => {
  const md = "write `[b](b.md)` like this";
  assert.equal(rewrite(md).content, md);
});

test("links inside a fenced code block are not touched", () => {
  const md = "```md\n[b](b.md)\n```\n[b](b.md)";
  assert.equal(rewrite(md).content, `\`\`\`md\n[b](b.md)\n\`\`\`\n[b](${wiki}/t/b)`);
});

test("a tilde fence closes only on a tilde fence", () => {
  const md = "~~~\n```\n[b](b.md)\n~~~\n[b](b.md)";
  assert.equal(rewrite(md).content, `~~~\n\`\`\`\n[b](b.md)\n~~~\n[b](${wiki}/t/b)`);
});

test("wikiLinks lists the page and anchor of every link into the Wiki", () => {
  const md = `[a](${wiki}/t/a#x) [e](https://example.org) [m](${main}/core)`;
  assert.deepEqual(wikiLinks(md, repoUrl), [{ page: "t/a", anchor: "x" }]);
});

// Page naming of the Wiki mirror (docs/stable/wiki-mirror/adr/6-mirror-mapping-links-deletion-credential.md):
// the path is the page name, and the file name in the Wiki repository is what Forgejo itself writes for
// that page. The shapes below are those observed in a Forgejo 16 Wiki: a slash is %2F; a name with a dash
// is stored as <name>.-.md, one without as <name>.md.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pageNameOf, wikiFileNameOf, pageNameOfWikiFile } from "./pages.mjs";

test("the page name is the path inside stable without the extension", () => {
  assert.equal(pageNameOf("pipeline-engine/adr/ADR-019-typed.md"), "pipeline-engine/adr/ADR-019-typed");
  assert.equal(pageNameOf("devkit-guide.md"), "devkit-guide");
});

test("the same file name under two topics gives two page names", () => {
  assert.notEqual(pageNameOf("a/01-requirements.md"), pageNameOf("b/01-requirements.md"));
});

test("a slash in the page name is %2F in the file name", () => {
  assert.equal(wikiFileNameOf("a/b/README"), "a%2Fb%2FREADME.md");
});

test("a page name with a dash is stored with the .-.md suffix", () => {
  assert.equal(wikiFileNameOf("pipeline-engine/01-requirements"), "pipeline-engine%2F01-requirements.-.md");
});

test("a literal percent sign is escaped so the file name decodes back", () => {
  assert.equal(wikiFileNameOf("a/100%.x"), "a%2F100%25.x.md");
});

test("the page name is recovered from the file name", () => {
  for (const page of ["Home", "a/b/README", "pipeline-engine/01-requirements", "a/100%.x"]) {
    assert.equal(pageNameOfWikiFile(wikiFileNameOf(page)), page);
  }
});

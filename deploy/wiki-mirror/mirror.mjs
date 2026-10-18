// Builds the Wiki mirror of docs/stable (ADR 1 and 6 of docs/stable/wiki-mirror): every Markdown document
// of stable becomes one page whose name is its path, links are rewritten for the Wiki, and a Home page and a
// sidebar list the entry documents by topic. It only reads the checkout; it writes nothing.
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { rewriteLinks, wikiLinks } from "./links.mjs";
import { RESERVED_PAGES, pageNameOf, pageNameOfWikiFile, wikiFileNameOf } from "./pages.mjs";

const STABLE = "docs/stable";

const encodePath = (page) => page.split("/").map(encodeURIComponent).join("/");
const compareSegments = (a, b) => {
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1;
  }
  return a.length - b.length;
};

const title = (markdown, fallback) =>
  /^# +(.+?)\s*#*\s*$/m.exec(markdown)?.[1].replace(/[[\]]/g, "") ?? fallback;

// The entry documents are the README of every directory and the documents directly under stable;
// they are listed as a tree by directory depth, siblings in path order.
const entryList = (documents, wikiUrl) =>
  documents
    .map(({ page, content }) => {
      const segments = page.split("/");
      const isReadme = segments.at(-1) === "README";
      return { page, content, key: isReadme ? segments.slice(0, -1) : segments, isReadme };
    })
    .filter(({ isReadme, key }) => isReadme || key.length === 1)
    .sort((a, b) => compareSegments(a.key, b.key))
    .map(
      ({ page, content, key }) =>
        `${"  ".repeat(Math.max(key.length - 1, 0))}- [${title(content, page)}](${wikiUrl}/${encodePath(page)})`,
    )
    .join("\n");

const listMarkdown = (root, directory = "") =>
  readdirSync(join(root, directory), { withFileTypes: true })
    .sort((a, b) => (a.name < b.name ? -1 : 1))
    .flatMap((entry) => {
      const path = directory ? `${directory}/${entry.name}` : entry.name;
      if (entry.isDirectory()) return listMarkdown(root, path);
      return entry.isFile() && entry.name.endsWith(".md") ? [path] : [];
    });

/**
 * The mirror of the repository at `repoRoot`.
 * Returns {files, dropped}: `files` maps the file name in the Wiki repository to its content; `dropped`
 * lists {source, dest} of the links that led nowhere and were replaced by their text.
 */
export const buildMirror = ({ repoRoot, repoUrl, ref }) => {
  const stableRoot = join(repoRoot, STABLE);
  let relativePaths;
  try {
    relativePaths = listMarkdown(stableRoot);
  } catch {
    throw new Error(`${STABLE} cannot be read in ${repoRoot}`);
  }
  const kindOf = (path) => {
    try {
      const stat = statSync(join(repoRoot, path));
      return stat.isDirectory() ? "dir" : "file";
    } catch {
      return null;
    }
  };

  const files = new Map();
  const dropped = [];
  const documents = [];
  for (const relative of relativePaths) {
    const page = pageNameOf(relative);
    if (RESERVED_PAGES.includes(page)) {
      throw new Error(`${STABLE}/${relative} takes the name of the reserved page ${page}`);
    }
    const source = `${STABLE}/${relative}`;
    const original = readFileSync(join(repoRoot, source), "utf8");
    const rewritten = rewriteLinks(original, { source, repoUrl, ref, kindOf });
    dropped.push(...rewritten.dropped.map((dest) => ({ source, dest })));
    files.set(wikiFileNameOf(page), rewritten.content);
    documents.push({ page, content: original });
  }

  const wikiUrl = `${repoUrl}/wiki`;
  const entries = entryList(documents, wikiUrl);
  files.set(
    wikiFileNameOf("Home"),
    [
      "# runline 文件（stable）",
      "",
      "本 Wiki 是儲存庫 `docs/stable/` 的唯讀映像，`main` 上 `docs/stable/` 有變動時由 CI 自動同步。請勿在此編輯：修改會在下次同步被覆蓋，內容的修訂走 `docs/draft/` 的 PR 流程。",
      "",
      "## 目錄",
      "",
      entries,
      "",
    ].join("\n"),
  );
  files.set(wikiFileNameOf("_Sidebar"), [`**[首頁](${wikiUrl}/Home)**`, "", entries, ""].join("\n"));
  return { files, dropped };
};

/** Throws when a page of the mirror links to a Wiki page that the mirror does not hold. */
export const verifyMirror = ({ files }, repoUrl) => {
  const pages = new Set([...files.keys()].map(pageNameOfWikiFile));
  const dangling = [];
  for (const [fileName, content] of files) {
    for (const { page } of wikiLinks(content, repoUrl)) {
      if (!pages.has(page)) dangling.push(`${pageNameOfWikiFile(fileName)} -> ${page}`);
    }
  }
  if (dangling.length > 0) {
    throw new Error(`links to pages that do not exist:\n  ${dangling.join("\n  ")}`);
  }
};

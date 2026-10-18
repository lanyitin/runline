// Link rewriting of the Wiki mirror (the table in ADR 6 of docs/stable/wiki-mirror). Only inline links
// `[text](destination "title")` are handled, outside fenced code blocks and inline code; stable has no
// other form, and a form this does not read is left as it is.
import { posix } from "node:path";
import { pageNameOf } from "./pages.mjs";

const STABLE = "docs/stable";
const LINK =
  /(!?)\[((?:[^\[\]]|\[[^\[\]]*\])*)\]\(\s*(<[^<>]*>|(?:[^\s()]|\([^\s()]*\))*)((?:\s+"[^"]*")?)\s*\)/dg;
const CODE_SPAN = /(`+)(?:(?!\1)[^])+?\1(?!`)/g;
const FENCE = /^ {0,3}(`{3,}|~{3,})/;

// Calls `visit(match)` for every inline link outside code; `match` is {bang, text, dest, title} (dest
// without angle brackets). `visit` returns the replacement text, or undefined to keep the link as written.
const mapLinks = (markdown, visit) => {
  let fence = null; // {char, length} while inside a fenced block
  return markdown
    .split("\n")
    .map((line) => {
      const opening = FENCE.exec(line);
      if (fence) {
        const closing = new RegExp(`^ {0,3}${fence.char}{${fence.length},}\\s*$`);
        if (closing.test(line)) fence = null;
        return line;
      }
      if (opening) {
        fence = { char: opening[1][0] === "`" ? "`" : "~", length: opening[1].length };
        return line;
      }
      // Code spans are blanked out so the pattern cannot see links inside them, while the groups
      // are read back from the original line (a link text may hold code).
      const masked = line.replace(CODE_SPAN, (span) => "".repeat(span.length));
      let out = "";
      let at = 0;
      for (const m of masked.matchAll(LINK)) {
        const [start, end] = m.indices[0];
        const slice = (i) => line.slice(m.indices[i][0], m.indices[i][1]);
        const rawDest = slice(3);
        const replacement = visit({
          bang: slice(1),
          text: slice(2),
          dest: rawDest.startsWith("<") ? rawDest.slice(1, -1) : rawDest,
          title: slice(4),
        });
        if (replacement === undefined) continue;
        out += line.slice(at, start) + replacement;
        at = end;
      }
      return out + line.slice(at);
    })
    .join("\n");
};

const encodePath = (page) => page.split("/").map(encodeURIComponent).join("/");

// Where a destination written in `source` leads, or null when it leads nowhere that exists.
const target = (dest, { source, repoUrl, ref, kindOf }) => {
  const hash = dest.indexOf("#");
  const anchor = hash < 0 ? "" : dest.slice(hash);
  const path = (hash < 0 ? dest : dest.slice(0, hash)).replace(/\?.*$/, "");
  let resolved;
  try {
    const decoded = decodeURIComponent(path);
    resolved = decoded.startsWith("/")
      ? posix.normalize(decoded.slice(1))
      : posix.join(posix.dirname(source), decoded);
  } catch {
    return null;
  }
  resolved = posix.normalize(resolved).replace(/\/$/, "");
  if (resolved === ".." || resolved.startsWith("../")) return null;
  const kind = kindOf(resolved);
  if (kind === null) return null;
  const inStable = resolved === STABLE || resolved.startsWith(`${STABLE}/`);
  if (inStable && kind === "file" && resolved.endsWith(".md")) {
    return `${repoUrl}/wiki/${encodePath(pageNameOf(resolved.slice(STABLE.length + 1)))}${anchor}`;
  }
  if (inStable && kind === "dir") {
    const readme = `${resolved}/README.md`;
    if (kindOf(readme) === "file") {
      return `${repoUrl}/wiki/${encodePath(pageNameOf(readme.slice(STABLE.length + 1)))}${anchor}`;
    }
    return `${repoUrl}/wiki/Home`;
  }
  return `${repoUrl}/src/branch/${ref}/${resolved.split("/").map(encodeURIComponent).join("/")}${anchor}`;
};

/**
 * Rewrites the links of one stable document for the Wiki.
 *   source   the document's path in the repository (docs/stable/...)
 *   repoUrl  the repository's URL, e.g. https://forge.example/owner/repo
 *   ref      the branch that links to files outside the Wiki point to
 *   kindOf   path in the repository -> "file" | "dir" | null
 * Returns {content, dropped}: `dropped` lists the destinations that led nowhere; their links were
 * replaced by the link text.
 */
export const rewriteLinks = (markdown, context) => {
  const dropped = [];
  const content = mapLinks(markdown, ({ bang, text, dest, title }) => {
    if (dest === "" || dest.startsWith("#") || /^([a-z][a-z0-9+.-]*:|\/\/)/i.test(dest)) return undefined;
    const href = target(dest, context);
    if (href === null) {
      dropped.push(dest);
      return text;
    }
    return `${bang}[${text}](${href}${title})`;
  });
  return { content, dropped };
};

/** The pages (and anchors) a document links to inside the Wiki of `repoUrl`. */
export const wikiLinks = (markdown, repoUrl) => {
  const prefix = `${repoUrl}/wiki/`;
  const found = [];
  mapLinks(markdown, ({ dest }) => {
    if (dest.startsWith(prefix)) {
      const [path, anchor] = dest.slice(prefix.length).split("#");
      found.push({ page: path.split("/").map(decodeURIComponent).join("/"), anchor: anchor ?? "" });
    }
    return undefined;
  });
  return found;
};

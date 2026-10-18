// Page naming of the Wiki mirror (ADR 6 of docs/stable/wiki-mirror): the path inside docs/stable, without
// the extension, is the page name, so equal file names under different topics do not collide and the path
// can be recovered from the name.

// Pages the Wiki gives a meaning of its own; a stable document may not take their names.
export const RESERVED_PAGES = ["Home", "_Sidebar", "_Footer"];

export const pageNameOf = (pathInStable) => pathInStable.replace(/\.md$/, "");

// The file name Forgejo writes in the Wiki repository for a page: a slash is %2F (and a percent sign %25,
// so the name decodes back); a name with a dash carries the .-.md suffix, one without .md.
export const wikiFileNameOf = (page) => {
  const escaped = page.replaceAll("%", "%25").replaceAll("/", "%2F");
  return page.includes("-") ? `${escaped}.-.md` : `${escaped}.md`;
};

export const pageNameOfWikiFile = (fileName) =>
  decodeURIComponent(fileName.replace(/\.-\.md$|\.md$/, ""));

// History routing (ADR-015): clean paths, which the Engine answers with the entry page. The router
// knows paths only; which page a path is, is routes.ts. Nothing here puts anything but the path the
// caller gives into the address: a token must never be in a URL (ADR-017).

export function createRouter(win: Window) {
  let path = $state(win.location.pathname);
  let search = $state(win.location.search);

  const follow = () => {
    path = win.location.pathname;
    search = win.location.search;
  };
  win.addEventListener('popstate', follow);

  return {
    /** The path of the page, without query and fragment. Reactive. */
    get path() {
      return path;
    },
    /** The query of the address (`?a=b`, or empty). Reactive. */
    get search() {
      return search;
    },
    /** Goes to `to` (a path, with query and fragment if it has any) without loading the page. */
    navigate(to: string) {
      const target = new URL(to, win.location.href);
      const next = target.pathname + target.search + target.hash;
      const current = win.location.pathname + win.location.search + win.location.hash;
      if (next !== current) win.history.pushState(null, '', next);
      path = target.pathname;
      search = target.search;
    },
    /** Changes the address to `to` without adding an entry to the history (a filter being typed). */
    replace(to: string) {
      const target = new URL(to, win.location.href);
      win.history.replaceState(null, '', target.pathname + target.search + target.hash);
      path = target.pathname;
      search = target.search;
    },
    dispose() {
      win.removeEventListener('popstate', follow);
    },
  };
}

export type Router = ReturnType<typeof createRouter>;

// History routing (ADR-015): clean paths, which the Engine answers with the entry page. The router
// knows paths only; which page a path is, is routes.ts. Nothing here puts anything but the path the
// caller gives into the address: a token must never be in a URL (ADR-017).

export function createRouter(win: Window) {
  let path = $state(win.location.pathname);

  const follow = () => {
    path = win.location.pathname;
  };
  win.addEventListener('popstate', follow);

  return {
    /** The path of the page, without query and fragment. Reactive. */
    get path() {
      return path;
    },
    /** Goes to `to` (a path, with query and fragment if it has any) without loading the page. */
    navigate(to: string) {
      const target = new URL(to, win.location.href);
      const next = target.pathname + target.search + target.hash;
      const current = win.location.pathname + win.location.search + win.location.hash;
      if (next !== current) win.history.pushState(null, '', next);
      path = target.pathname;
    },
    dispose() {
      win.removeEventListener('popstate', follow);
    },
  };
}

export type Router = ReturnType<typeof createRouter>;

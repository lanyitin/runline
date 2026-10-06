<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { HTMLAnchorAttributes } from 'svelte/elements';
  import { useApp } from '../app/context';

  // A link inside the Console. It is a real anchor (new tab, copy address, screen readers), and a
  // plain click is taken by the router instead of loading the page again.
  interface Props extends Omit<HTMLAnchorAttributes, 'href' | 'children'> {
    href: string;
    /** The link is a section of the Console (the navigation): it is the place you are in below it too. */
    section?: boolean;
    children: Snippet;
  }
  let { href, section = false, children, ...rest }: Props = $props();

  const { router } = useApp();
  const base = $derived(href.split(/[?#]/)[0]);
  const current = $derived(
    router.path === base
      ? 'page'
      : section && base !== '/' && router.path.startsWith(`${base}/`)
        ? 'true'
        : undefined,
  );

  function follow(event: MouseEvent) {
    const modified = event.ctrlKey || event.metaKey || event.shiftKey || event.altKey;
    const plain = event.button === 0 && !modified;
    if (!plain || event.defaultPrevented) return;
    event.preventDefault();
    router.navigate(href);
  }
</script>

<a
  {href}
  aria-current={current}
  onclick={follow}
  {...rest}
>
  {@render children()}
</a>

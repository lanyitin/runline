<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { HTMLAnchorAttributes } from 'svelte/elements';
  import { useApp } from '../app/context';

  // A link inside the Console. It is a real anchor (new tab, copy address, screen readers), and a
  // plain click is taken by the router instead of loading the page again.
  interface Props extends Omit<HTMLAnchorAttributes, 'href' | 'children'> {
    href: string;
    children: Snippet;
  }
  let { href, children, ...rest }: Props = $props();

  const { router } = useApp();

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
  aria-current={router.path === href.split(/[?#]/)[0] ? 'page' : undefined}
  onclick={follow}
  {...rest}
>
  {@render children()}
</a>

<script lang="ts">
  import { useApp } from '../app/context';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';

  const { i18n, router } = useApp();
</script>

<h1>{i18n.t('notFound.title')}</h1>
<p>
  {i18n.t('notFound.body', { path: router.path })}
</p>
<p><Link href="/">{i18n.t('notFound.home')}</Link></p>

<style>
  h1 {
    font-size: var(--text-xl);
    font-weight: 600;
  }
</style>

<script lang="ts">
  import { useApp } from '../app/context';
  import type { Role } from '../app/identity.svelte';
  import Link from '../ui/Link.svelte';

  interface Props {
    role: Role;
  }
  let { role }: Props = $props();

  const { i18n } = useApp();
</script>

<h1>{i18n.t('forbidden.title')}</h1>
<p>{i18n.t('forbidden.body', { role: i18n.t(`role.${role}`) })}</p>
<p><Link href="/">{i18n.t('notFound.home')}</Link></p>

<style>
  h1 {
    font-size: var(--text-xl);
    font-weight: 600;
  }
</style>

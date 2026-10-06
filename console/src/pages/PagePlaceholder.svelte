<script lang="ts">
  import { useApp } from '../app/context';
  import type { RouteId } from '../app/routes';

  // What stands for a page until its work item builds it (WI-34 on): the title, in the heading.
  interface Props {
    id: RouteId;
  }
  let { id }: Props = $props();

  const { i18n } = useApp();
</script>

<h1>{i18n.t(`page.${id}.title`)}</h1>
<p class="pending">{i18n.t('page.pending')}</p>

<style>
  h1 {
    font-size: var(--text-xl);
    font-weight: 600;
  }
  .pending {
    color: var(--text-secondary);
  }
</style>

<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import { useApp } from '../app/context';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Link from '../ui/Link.svelte';

  // What went wrong with reading a view, in words and with what to do about it (WI-1302): the
  // headline and the detail come from the code of the error and the problem (`describeApiError`),
  // never from the Engine's message and never as JSON; below them what to try, and a way to try
  // again. A role that cannot be used leads to where it is settled. The Engine never says the value
  // it refused, only the parameter and why, and nothing here repeats a value.
  interface Props {
    failure: ApiFailure;
    /** Reads again. Left out when trying again would change nothing. */
    onretry?: () => void;
    /** Where the shared resource of a role is chosen. */
    roleHref: (role: string) => string;
  }
  let { failure, onretry, roleHref }: Props = $props();

  const { i18n } = useApp();
  const body = $derived(
    typeof failure.body === 'object' && failure.body !== null ? (failure.body as Record<string, unknown>) : {},
  );
  const code = $derived(typeof body.error === 'string' ? body.error : null);
  const hint = $derived(
    code !== null && i18n.translate.has(`views.hint.${code}`) ? `views.hint.${code}` : 'views.hint.other',
  );
  // The parameter the Engine refused and why, in the words of the field.
  const refused = $derived.by(() => {
    if (code !== 'invalid_view_parameter') return null;
    const reason = typeof body.reason === 'string' ? body.reason : '';
    const parameter = typeof body.parameter === 'string' ? body.parameter : '';
    return i18n.translate.has(`views.invalid.${reason}`)
      ? i18n.translate(`views.invalid.${reason}`, { parameter })
      : null;
  });
  const role = $derived(code === 'view_resource_unavailable' && typeof body.role === 'string' ? body.role : null);
</script>

<div class="view-error rl-stack">
  <ApiErrorNotice {failure} />
  {#if refused}<p class="refused">{refused}</p>{/if}
  <p class="view-hint">{i18n.t(hint as 'views.hint.other')}</p>
  <div class="actions">
    {#if role !== null}
      <Link href={roleHref(role)} class="rl-btn small fix">{i18n.t('views.problem.fix', { role })}</Link>
    {/if}
    {#if onretry}
      <button class="rl-btn small retry" type="button" onclick={onretry}>{i18n.t('common.retry')}</button>
    {/if}
  </div>
</div>

<style>
  p {
    margin: 0;
  }
  .refused {
    font-weight: 600;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
</style>

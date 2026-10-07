<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Secret } from '../api/admin-model';
  import { useApp } from '../app/context';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';

  // The alias of the secret of a resource (a database password, an API key), chosen among the
  // secrets of the keystore (`GET /api/v1/secrets`, ADR-019 decision 6). Never a secret value: there
  // is no field to type one, and the keystore is the operators' (keytool). Certificates and private
  // keys are not offered: a resource's secret is a secret. A new resource may have none; an existing
  // one keeps one once it has it (08-api: no change clears an alias), so "none" is offered only when
  // it has none. Its alias is offered even when the keystore no longer has it. When there is no
  // keystore, or no secret in it, it says why there is nothing to choose.
  interface Props {
    /** The alias chosen; '' for none. */
    value: string;
    /** The alias the resource has now; null for a new one, or one without. */
    current: string | null;
    /** `jdbc-pool` or `openai-compatible`: what the secret is for. */
    type: string;
    error?: string;
    onchange?: () => void;
  }
  let { value = $bindable(), current, type, error, onchange }: Props = $props();

  const { i18n, api } = useApp();
  let secrets = $state<Secret[] | null>(null);
  let notConfigured = $state(false);
  let failure = $state.raw<ApiFailure | null>(null);

  $effect(() => {
    let live = true;
    api.secrets().then(
      (found) => {
        if (live) secrets = found.filter((secret) => secret.type === 'secret');
      },
      (refused: unknown) => {
        if (!live) return;
        const answer = refused instanceof ApiFailure ? refused : new ApiFailure(0, null, String(refused));
        if ((answer.body as { error?: string } | null)?.error === 'secret_store_not_configured') notConfigured = true;
        else failure = answer;
      },
    );
    return () => {
      live = false;
    };
  });

  const label = (secret: Secret) =>
    secret.status === 'found'
      ? secret.alias
      : i18n.t('resources.form.secret.unusable', { alias: secret.alias });
  const missingCurrent = $derived(
    current !== null && secrets !== null && !secrets.some((secret) => secret.alias === current),
  );
</script>

<div class="rl-field">
  <label for="resource-secret">{i18n.translate(`resources.form.secret.${type}`)}</label>
  <select
    id="resource-secret"
    class="rl-input mono"
    aria-invalid={error ? 'true' : undefined}
    bind:value
    onchange={() => onchange?.()}
  >
    {#if current === null}<option value="">{i18n.t('resources.form.secret.none')}</option>{/if}
    {#if current !== null && (secrets === null || missingCurrent)}
      <option value={current}>{i18n.t('resources.form.secret.missing', { alias: current })}</option>
    {/if}
    {#each secrets ?? [] as secret (secret.alias)}
      <option value={secret.alias}>{label(secret)}</option>
    {/each}
  </select>
  <span class="rl-help secret-note">
    {#if notConfigured}
      {i18n.t('resources.form.secret.notConfigured')}
    {:else if secrets !== null && secrets.length === 0}
      {i18n.t('resources.form.secret.empty')}
    {/if}
    {i18n.t('resources.form.secret.help')}
  </span>
  {#if failure}<ApiErrorNotice {failure} />{/if}
  {#if error}<span class="rl-field-error">{error}</span>{/if}
</div>

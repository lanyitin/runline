<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Secret } from '../api/admin-model';
  import { useApp } from '../app/context';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import PlainText from '../ui/PlainText.svelte';

  // The certificates of a resource that connects over TLS (WI-52; 08-api.md: `trustAliases` and
  // `clientCertAlias`), chosen among the keystore's entries by kind: the trusted certificates are
  // ticked (several; with any, only they are trusted), the client certificate is a private key
  // entry chosen in a list (one, or none). A secret is never offered here, and nothing of a key is
  // shown: an alias, the subject, the end of validity and how many days are left. Aliases the
  // resource has and the keystore no longer has stay listed, said so, until they are taken away.
  interface Props {
    trustAliases: string[];
    clientCertAlias: string;
    /** `jdbc-pool` or `openai-compatible`: what the certificates are for. */
    type: string;
    error?: string;
    onchange?: () => void;
  }
  let { trustAliases = $bindable(), clientCertAlias = $bindable(), type, error, onchange }: Props = $props();

  const { i18n, api } = useApp();
  let entries = $state<Secret[] | null>(null);
  let notConfigured = $state(false);
  let failure = $state.raw<ApiFailure | null>(null);
  // The aliases the resource had when the form opened: kept in the lists even when not in the keystore.
  // svelte-ignore state_referenced_locally
  const startTrust = [...trustAliases];
  // svelte-ignore state_referenced_locally
  const startClient = clientCertAlias;

  $effect(() => {
    let live = true;
    api.secrets().then(
      (found) => {
        if (live) entries = found;
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

  const ofKind = (kind: string) => (entries ?? []).filter((entry) => entry.type === kind);
  const trusted = $derived(ofKind('trusted_certificate'));
  const keys = $derived(ofKind('private_key'));
  const lostTrust = $derived(startTrust.filter((alias) => entries !== null && !trusted.some((e) => e.alias === alias)));
  const lostClient = $derived(
    startClient !== '' && entries !== null && !keys.some((e) => e.alias === startClient) ? startClient : null,
  );

  /** What may be said of an entry's certificate: subject, and days left (or that it expired). */
  const about = (entry: Secret) => {
    const own = entry.certificates[0];
    if (own === undefined) return '';
    const left =
      own.expiry === 'expired'
        ? i18n.t('resources.form.certificates.expired')
        : i18n.t('resources.form.certificates.daysLeft', { days: own.daysLeft });
    return `${own.subject} — ${left}`;
  };

  function toggle(alias: string, on: boolean) {
    trustAliases = on ? [...trustAliases.filter((a) => a !== alias), alias] : trustAliases.filter((a) => a !== alias);
    onchange?.();
  }
</script>

<fieldset id="resource-certificates" class="rl-field certificates">
  <legend>{i18n.t('resources.form.certificates.title')}</legend>
  <span class="rl-help">{i18n.translate(`resources.form.certificates.help.${type}`)}</span>

  <div class="trust">
    <span class="label">{i18n.t('resources.form.certificates.trust')}</span>
    {#each trusted as entry (entry.alias)}
      <label class="choice {entry.certificates[0]?.expiry ?? ''}">
        <input
          type="checkbox"
          value={entry.alias}
          checked={trustAliases.includes(entry.alias)}
          onchange={(event) => toggle(entry.alias, event.currentTarget.checked)}
        />
        <PlainText value={entry.alias} mono />
        <span class="about">{about(entry)}</span>
      </label>
    {/each}
    {#each lostTrust as alias (alias)}
      <label class="choice missing">
        <input
          type="checkbox"
          value={alias}
          checked={trustAliases.includes(alias)}
          onchange={(event) => toggle(alias, event.currentTarget.checked)}
        />
        <span>{i18n.t('resources.form.secret.missing', { alias })}</span>
      </label>
    {/each}
    {#if entries !== null && trusted.length === 0 && lostTrust.length === 0}
      <span class="rl-help">{i18n.t('resources.form.certificates.noTrusted')}</span>
    {/if}
  </div>

  <div class="client">
    <label for="resource-client-cert">{i18n.t('resources.form.certificates.client')}</label>
    <select
      id="resource-client-cert"
      class="rl-input mono"
      bind:value={clientCertAlias}
      onchange={() => onchange?.()}
    >
      <option value="">{i18n.t('resources.form.certificates.noClient')}</option>
      {#if lostClient !== null}
        <option value={lostClient}>{i18n.t('resources.form.secret.missing', { alias: lostClient })}</option>
      {/if}
      {#each keys as entry (entry.alias)}
        <option value={entry.alias}>{entry.status === 'found' ? `${entry.alias} — ${about(entry)}` : i18n.t('resources.form.certificates.unusableKey', { alias: entry.alias })}</option>
      {/each}
    </select>
  </div>

  {#if notConfigured}
    <span class="rl-help">{i18n.t('resources.form.certificates.notConfigured')}</span>
  {/if}
  <span class="rl-help">{i18n.t('resources.form.certificates.keytool')}</span>
  {#if failure}<ApiErrorNotice {failure} />{/if}
  {#if error}<span class="rl-field-error">{error}</span>{/if}
</fieldset>

<style>
  .certificates {
    display: grid;
    gap: var(--space-2);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    padding: var(--space-3);
    margin: 0;
  }
  legend {
    font-weight: 600;
    font-size: var(--text-sm);
  }
  .trust,
  .client {
    display: grid;
    gap: var(--space-1);
  }
  .label {
    color: var(--text);
    font-size: var(--text-sm);
  }
  .choice {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-sm);
  }
  .about {
    color: var(--text-muted);
  }
  .choice.expiring .about {
    color: var(--warning-text);
  }
  .choice.expired .about,
  .choice.missing {
    color: var(--danger-text);
  }
</style>

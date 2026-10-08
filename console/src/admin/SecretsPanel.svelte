<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Secret, SecretReload } from '../api/admin-model';
  import { createPolled } from '../api/polled.svelte';
  import { useApp } from '../app/context';
  import { enumLabel } from '../i18n/enums';
  import type { Clock, Visibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import PlainText from '../ui/PlainText.svelte';

  // The keystore as the Engine has read it (`GET /api/v1/secrets`, ADR-019): each alias with the
  // kind of entry, whether it can be used and the resources that refer to it, and a reload of the
  // whole file (`POST /api/v1/secrets/reload`) with what it found. No value of a secret is ever in an
  // answer, and the Console takes none: operators add and change secrets with keytool. These are
  // not the secrets of webhooks, which the Console shows once when a trigger is made or rotated.
  // The list is read again every 3 s while the tab is shown, as the resources are.
  interface Props {
    clock: Clock;
    visibility: Visibility;
  }
  let { clock, visibility }: Props = $props();

  const { i18n, api } = useApp();

  /** What the Engine has: its aliases, or no keystore at all (409, which is a state, not a failure). */
  type Keystore = { configured: true; secrets: Secret[] } | { configured: false };

  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
  const isCode = (error: ApiFailure, code: string) =>
    (error.body as { error?: string } | null)?.error === code;

  // The clock and the visibility of a page are the same for as long as it lives.
  // svelte-ignore state_referenced_locally
  const keystore = createPolled<Keystore>({
    load: async () => {
      try {
        return { configured: true, secrets: await api.secrets() };
      } catch (error) {
        const failure = asFailure(error);
        if (isCode(failure, 'secret_store_not_configured')) return { configured: false };
        throw failure;
      }
    },
    clock,
    visibility,
    intervalMs: 3000,
  });
  $effect(() => {
    void keystore.start();
    return () => keystore.dispose();
  });

  let reloading = $state(false);
  let reloaded = $state.raw<SecretReload | null>(null);
  let reloadFailure = $state.raw<ApiFailure | null>(null);

  async function reload() {
    if (reloading) return;
    reloading = true;
    reloaded = null;
    reloadFailure = null;
    try {
      reloaded = await api.reloadSecrets();
      keystore.reload();
    } catch (error) {
      reloadFailure = asFailure(error);
    } finally {
      reloading = false;
    }
  }

  const users = (names: string[]) => (names.length === 0 ? '—' : names.join(', '));
</script>

<section class="secrets rl-card" aria-labelledby="secrets-title">
  <header>
    <h2 id="secrets-title">{i18n.t('secrets.title')}</h2>
    {#if keystore.data?.configured}
      <button class="rl-btn small" type="button" disabled={reloading} onclick={() => void reload()}>
        {i18n.t('secrets.reload')}
      </button>
    {/if}
  </header>
  <p class="rl-help">{i18n.t('secrets.intro')}</p>
  <p class="rl-help">{i18n.t('secrets.notWebhook')}</p>

  {#if keystore.status === 'failed' && keystore.error}
    <ApiErrorNotice failure={keystore.error} />
  {:else if keystore.status === 'loading'}
    <p class="rl-help" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
  {:else if keystore.data && !keystore.data.configured}
    <p class="not-configured rl-notice info">{i18n.t('secrets.notConfigured')}</p>
  {:else if keystore.data?.configured}
    {#if reloadFailure}
      <ApiErrorNotice failure={reloadFailure} />
    {/if}
    {#if reloaded}
      <div class="reloaded rl-notice success" role="status">
        <p>{i18n.t('secrets.reloaded', { aliases: reloaded.aliases })}</p>
        {#if reloaded.changed.length === 0}
          <p>{i18n.t('secrets.reloaded.none')}</p>
        {:else}
          <p>{i18n.t('secrets.reloaded.changed')}</p>
          <ul>
            {#each reloaded.changed as change (change.alias)}
              <li>
                <PlainText value={change.alias} mono />
                ({change.usedBy.length === 0
                  ? i18n.t('secrets.usedByNone')
                  : i18n.t('secrets.usedBy', { names: change.usedBy.join(', ') })})
              </li>
            {/each}
          </ul>
        {/if}
      </div>
    {/if}
    {#if keystore.data.secrets.length === 0}
      <p class="rl-help">{i18n.t('secrets.empty')}</p>
    {/if}
    <div class="rl-table-wrap">
      <table class="rl-table">
        <thead>
          <tr>
            <th>{i18n.t('secrets.alias')}</th>
            <th>{i18n.t('secrets.type')}</th>
            <th>{i18n.t('secrets.status')}</th>
            <th>{i18n.t('secrets.resources')}</th>
          </tr>
        </thead>
        <tbody>
          {#each keystore.data.secrets as secret (secret.alias)}
            <tr data-alias={secret.alias}>
              <td><PlainText value={secret.alias} mono /></td>
              <td>{enumLabel(i18n.translate, 'secretType', secret.type)}</td>
              <td class="status {secret.status}">{enumLabel(i18n.translate, 'keystoreStatus', secret.status)}</td>
              <td><PlainText value={users(secret.usedBy)} mono /></td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    {#if keystore.data.secrets.some((secret) => secret.certificates.length > 0)}
      <div class="certificate-list">
        <h3>{i18n.t('secrets.certificates')}</h3>
        {#each keystore.data.secrets.filter((secret) => secret.certificates.length > 0) as secret (secret.alias)}
          <div class="entry">
            <PlainText value={secret.alias} mono />
            <ul class="certificates" data-certificates-of={secret.alias}>
              {#each secret.certificates as certificate, index (index)}
                <li class={certificate.expiry}>
                  <span class="subject"><PlainText value={certificate.subject} mono /></span>
                  <span class="expiry">
                    {i18n.t('secrets.certificate.notAfter', { date: certificate.notAfter })} ·
                    {certificate.expiry === 'expired'
                      ? i18n.t('secrets.certificate.expired')
                      : i18n.t('secrets.certificate.daysLeft', { days: certificate.daysLeft })}
                  </span>
                  {#if certificate.expiry === 'expiring'}
                    <span class="warning" role="note">{i18n.t('secrets.certificate.expiring')}</span>
                  {/if}
                  <span class="fingerprint mono">{i18n.t('secrets.certificate.fingerprint')} {certificate.fingerprint}</span>
                </li>
              {/each}
            </ul>
          </div>
        {/each}
      </div>
    {/if}
    {#if keystore.error}
      <ApiErrorNotice failure={keystore.error} />
    {/if}
  {/if}
</section>

<style>
  .secrets {
    display: grid;
    gap: var(--space-3);
    margin-top: var(--space-6);
  }
  header {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }
  h2 {
    margin: 0;
    font-size: var(--text-md);
    font-weight: 600;
  }
  p {
    margin: 0;
  }
  ul {
    margin: var(--space-1) 0 0;
    padding-left: var(--space-5);
  }
  .status.found {
    color: var(--success-text);
  }
  .status.invalid_secret,
  .status.invalid_key {
    color: var(--danger-text);
  }
  .certificate-list {
    display: grid;
    gap: var(--space-2);
  }
  h3 {
    margin: 0;
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .certificates {
    display: grid;
    gap: var(--space-1);
    margin: 0;
    padding-left: var(--space-4);
    font-size: var(--text-sm);
  }
  .certificates li {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .expiry,
  .fingerprint {
    color: var(--text-muted);
    overflow-wrap: anywhere;
  }
  .expiring .warning {
    color: var(--warning-text);
  }
  .expired .expiry {
    color: var(--danger-text);
  }
</style>

<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { User } from '../api/users-model';
  import { createPolled } from '../api/polled.svelte';
  import { useApp } from '../app/context';
  import { browserClock, pageVisibility, type Clock, type Visibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import ConfirmDialog from '../ui/ConfirmDialog.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';
  import UserSessionsDialog from '../users/UserSessionsDialog.svelte';

  // The people who signed in with the login service (`GET /api/v1/users`), for admins: who they are,
  // whether they are disabled and by whom, when they signed in last and how many sessions they have.
  // An admin disables a person (every session ends at once and the sign-in is refused: the dialog
  // says so, and says more when the person is the admin themselves) or enables them again, and looks
  // at their sessions to end one or all (UserSessionsDialog). The page is read again every few
  // seconds while the tab is shown, as the runs are. Names and display names come from the provider:
  // they are shown as plain text only. The page exists only when the Engine has login with a
  // provider (routes.ts); the Engine refuses a developer, and a call that fails says why in words.
  interface Props {
    /** Where the time comes from; the tests give their own. */
    clock?: Clock;
    visibility?: Visibility;
  }
  let { clock = browserClock, visibility = pageVisibility }: Props = $props();

  const { i18n, api, session } = useApp();
  const EVERY_MS = 5000;

  // The clock and the visibility of a page are the same for as long as it lives.
  // svelte-ignore state_referenced_locally
  const list = createPolled({ load: () => api.users(), clock, visibility, intervalMs: EVERY_MS });
  $effect(() => {
    void list.start();
    return () => list.dispose();
  });

  const users = $derived<User[]>(list.data ?? []);
  const me = $derived(session.state.status === 'authenticated' ? session.state.identity.name : null);

  let changing = $state<{ kind: 'disable' | 'enable'; user: User } | null>(null);
  let viewing = $state.raw<User | null>(null);
  let busy = $state(false);
  let failure = $state.raw<ApiFailure | null>(null);
  let result = $state<string | null>(null);

  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  async function confirm() {
    if (changing === null || busy) return;
    const { kind, user } = changing;
    busy = true;
    failure = null;
    try {
      if (kind === 'disable') {
        const done = await api.disableUser(user.name);
        result = i18n.t('users.result.disabled', { name: user.name, count: done.sessionsEnded ?? 0 });
      } else {
        await api.enableUser(user.name);
        result = i18n.t('users.result.enabled', { name: user.name });
      }
      changing = null;
      list.reload();
    } catch (error) {
      failure = asFailure(error);
    } finally {
      busy = false;
    }
  }

  function sessionsChanged({ key, count }: { key: 'users.result.sessionEnded' | 'users.result.sessionsEnded'; count: number }) {
    result = i18n.t(key, { name: viewing?.name ?? '', count });
    list.reload();
  }
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.users.title')}</h1>
    <p class="sub">{i18n.t('page.users.intro')}</p>
  </div>
  <div class="rl-actions">
    <button class="rl-btn" type="button" onclick={() => list.reload()}>{i18n.t('users.refresh')}</button>
  </div>
</div>

{#if result}
  <p class="rl-notice success" role="status" data-result>{result}</p>
{/if}

{#if list.status === 'failed' && list.error && list.data === null}
  <div class="rl-stack">
    <ApiErrorNotice failure={list.error} />
    <div><button class="rl-btn retry" type="button" onclick={() => list.reload()}>{i18n.t('common.retry')}</button></div>
  </div>
{:else if list.status === 'loading'}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else}
  {#if list.error}
    <div class="problem"><ApiErrorNotice failure={list.error} /></div>
  {/if}
  {#if users.length === 0}
    <div class="rl-empty">
      <strong>{i18n.t('users.empty.title')}</strong>
      <p>{i18n.t('users.empty.body')}</p>
    </div>
  {:else}
    <div class="rl-table-wrap">
      <table class="rl-table users">
        <thead>
          <tr>
            <th scope="col">{i18n.t('users.col.name')}</th>
            <th scope="col">{i18n.t('users.col.displayName')}</th>
            <th scope="col">{i18n.t('users.col.status')}</th>
            <th scope="col">{i18n.t('users.col.lastLogin')}</th>
            <th scope="col">{i18n.t('users.col.sessions')}</th>
            <th scope="col">{i18n.t('users.col.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {#each users as user (user.name)}
            <tr>
              <td class="name"><PlainText value={user.name} mono /></td>
              <td class="display"><PlainText value={user.displayName} /></td>
              <td class="status">
                <span class="state" class:disabled={user.disabled}>
                  {user.disabled ? i18n.t('users.status.disabled') : i18n.t('users.status.active')}
                </span>
                {#if user.disabled && user.statusChangedBy}
                  <span class="rl-help">{i18n.t('users.status.by', { name: user.statusChangedBy })}</span>
                {/if}
              </td>
              <td class="last rl-nowrap"><Timestamp iso={user.lastLoginAt} /></td>
              <td class="count rl-mono">{user.sessions}</td>
              <td>
                <div class="rl-actions">
                  <button
                    class="rl-btn small"
                    type="button"
                    onclick={() => {
                      viewing = user;
                      result = null;
                    }}
                  >
                    {i18n.t('users.sessions')}
                  </button>
                  {#if user.disabled}
                    <button
                      class="rl-btn small"
                      type="button"
                      onclick={() => {
                        changing = { kind: 'enable', user };
                        failure = null;
                      }}
                    >
                      {i18n.t('users.enable')}
                    </button>
                  {:else}
                    <button
                      class="rl-btn small danger"
                      type="button"
                      onclick={() => {
                        changing = { kind: 'disable', user };
                        failure = null;
                      }}
                    >
                      {i18n.t('users.disable')}
                    </button>
                  {/if}
                </div>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
{/if}

{#if changing}
  {@const { kind, user } = changing}
  <ConfirmDialog
    title={i18n.t(`users.${kind}.title`, { name: user.name })}
    confirmLabel={i18n.t(`users.${kind}.confirm`)}
    danger={kind === 'disable'}
    {busy}
    {failure}
    onconfirm={confirm}
    oncancel={() => (changing = null)}
  >
    <p>{i18n.t(`users.${kind}.body`, { name: user.name })}</p>
    {#if kind === 'disable' && user.name === me}
      <p class="rl-notice warning">{i18n.t('users.disable.self')}</p>
    {/if}
  </ConfirmDialog>
{/if}

{#if viewing}
  <UserSessionsDialog user={viewing} onchanged={sessionsChanged} onclose={() => (viewing = null)} />
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .problem {
    margin-bottom: var(--space-4);
  }
  p {
    margin: 0;
  }
  td.name,
  td.display {
    overflow-wrap: anywhere;
  }
  .state {
    font-weight: 600;
    color: var(--success-text);
  }
  .state.disabled {
    color: var(--danger-text);
  }
</style>

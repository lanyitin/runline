<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { User, UserSession } from '../api/users-model';
  import { useApp } from '../app/context';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import ConfirmDialog from '../ui/ConfirmDialog.svelte';
  import Dialog from '../ui/Dialog.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // The valid sessions of one person (`GET /api/v1/users/{name}/sessions`), by when each began and
  // was last used: the handle the Engine gives a session is no credential and says nothing, so it is
  // not shown. One is ended at once (it can be signed in again at once); all of them after a question
  // that says nobody is disabled by it. What the Engine refuses is said inside the dialog. The name
  // comes from the provider: it goes into the dialog as text only.
  interface Props {
    user: User;
    /** Something changed: the page reads its list again; [result] says what was done. */
    onchanged: (result: { key: 'users.result.sessionEnded' | 'users.result.sessionsEnded'; count: number }) => void;
    onclose: () => void;
  }
  let { user, onchanged, onclose }: Props = $props();

  const { i18n, api } = useApp();

  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  // undefined while the sessions are read.
  let sessions = $state.raw<UserSession[] | undefined>(undefined);
  let failure = $state.raw<ApiFailure | null>(null);
  let busy = $state(false);
  let asking = $state(false);

  async function load() {
    try {
      sessions = await api.userSessions(user.name);
    } catch (error) {
      failure = asFailure(error);
      sessions ??= [];
    }
  }
  void load();

  async function act(work: () => Promise<void>) {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      await work();
    } catch (error) {
      failure = asFailure(error);
    } finally {
      busy = false;
    }
  }

  const endOne = (session: UserSession) =>
    act(async () => {
      await api.endUserSession(user.name, session.id);
      sessions = await api.userSessions(user.name);
      onchanged({ key: 'users.result.sessionEnded', count: 1 });
    });

  const endAll = () =>
    act(async () => {
      const count = await api.endUserSessions(user.name);
      asking = false;
      sessions = await api.userSessions(user.name);
      onchanged({ key: 'users.result.sessionsEnded', count });
    });
</script>

{#if asking}
  <ConfirmDialog
    title={i18n.t('users.sessions.endAll.title', { name: user.name })}
    confirmLabel={i18n.t('users.sessions.endAll')}
    danger
    {busy}
    {failure}
    onconfirm={endAll}
    oncancel={() => {
      asking = false;
      failure = null;
    }}
  >
    <p>{i18n.t('users.sessions.endAll.body', { name: user.name })}</p>
  </ConfirmDialog>
{:else}
  <Dialog title={i18n.t('users.sessions.title', { name: user.name })} {onclose}>
    {#if sessions === undefined}
      <p role="status" aria-busy="true">{i18n.t('common.loading')}</p>
    {:else if sessions.length === 0}
      <p class="empty">{i18n.t('users.sessions.empty')}</p>
    {:else}
      <div class="rl-table-wrap">
        <table class="rl-table rl-sticky-first">
          <thead>
            <tr>
              <th scope="col">{i18n.t('users.sessions.col.started')}</th>
              <th scope="col">{i18n.t('users.sessions.col.lastUsed')}</th>
              <th scope="col">{i18n.t('users.col.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {#each sessions as session (session.id)}
              <tr>
                <td><Timestamp iso={session.createdAt} /></td>
                <td><Timestamp iso={session.lastUsedAt} /></td>
                <td>
                  <button class="rl-btn small danger" type="button" disabled={busy} onclick={() => endOne(session)}>
                    {i18n.t('users.sessions.end')}
                  </button>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
    {#if failure}
      <ApiErrorNotice {failure} />
    {/if}
    {#snippet footer()}
      <button
        class="rl-btn danger"
        type="button"
        disabled={busy || !sessions || sessions.length === 0}
        onclick={() => (asking = true)}
      >
        {i18n.t('users.sessions.endAll')}
      </button>
    {/snippet}
  </Dialog>
{/if}

<style>
  p {
    margin: 0;
  }
  .empty {
    color: var(--text-secondary);
  }
</style>

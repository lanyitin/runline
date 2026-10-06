<script lang="ts">
  import { useApp } from '../app/context';

  // The page before anyone is signed in: the frame of the sign-in, whichever method signs in. It
  // says what happened to a session that is gone, and gives the place to the screen of the method
  // (the session knows which one). The page asked for stays in the address; signing in lands on it.
  const { i18n, session } = useApp();

  const state = $derived(session.state);
  const View = $derived(session.SignIn);
  const notice = $derived(
    state.status === 'anonymous' && state.reason === 'expired'
      ? i18n.t('signIn.expired')
      : state.status === 'anonymous' && state.reason === 'unavailable'
        ? i18n.t('signIn.unavailable')
        : null,
  );
</script>

<section class="card">
  <h1>{i18n.t('signIn.title')}</h1>
  {#if state.status === 'restoring'}
    <p class="notice" role="status" aria-busy="true">{i18n.t('signIn.restoring')}</p>
  {:else}
    {#if notice}
      <p class="notice reason" role="status">{notice}</p>
    {/if}
    <View signIn={session.signIn} />
  {/if}
</section>

<style>
  .card {
    position: relative;
    width: min(24rem, 100%);
    padding: var(--space-6);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--surface);
    box-shadow: var(--shadow-card);
  }
  /* The corner tick of the design system: a mark of 4px on a key card. */
  .card::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    width: 8px;
    height: 8px;
    border-top: 1px solid var(--border-strong);
    border-left: 1px solid var(--border-strong);
  }
  h1 {
    margin-bottom: var(--space-5);
    font-size: var(--text-xl);
    font-weight: 600;
  }
  .notice {
    margin: 0 0 var(--space-4);
    color: var(--text-secondary);
  }
  .reason {
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-md);
    background: var(--warning-tint);
    color: var(--warning-text);
    font-size: var(--text-xs);
  }
</style>

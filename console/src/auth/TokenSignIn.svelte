<script lang="ts">
  import { tick } from 'svelte';
  import type { ApiFailure } from '../api/failure';
  import { useApp } from '../app/context';
  import { describeApiError } from '../i18n/api-error';
  import type { SignInProps } from './method';

  // The sign-in of the token method, and the only screen that knows a token is typed here: a field
  // that hides it (it can be shown), a button, and what went wrong in words. What the token is
  // worth, the Engine says (the session asks `GET /api/v1/system`). The frame around it (the title,
  // the notice about a session that ended, the version of the Engine) is the page's, not this one's.
  let { signIn }: SignInProps = $props();

  const { i18n } = useApp();
  const id = $props.id();

  let token = $state('');
  let shown = $state(false);
  let busy = $state(false);
  let problem = $state<ApiFailure | 'empty' | null>(null);
  let field: HTMLInputElement | undefined = $state();

  // The browser knows whether what is typed here is safe on its way to the Engine: a secure
  // context is HTTPS, or the machine itself (the local development of the Console), and anything
  // else sends the token across the network in the clear (ADR-017).
  const protectedTransport = window.isSecureContext;

  // Worked out here, not when the problem happened, so that it follows a change of language.
  const message = $derived.by(() => {
    if (problem === null) return null;
    if (problem === 'empty') return { text: i18n.t('signIn.token.empty'), errorId: null };
    if (problem.status === 401) return { text: i18n.t('signIn.token.invalid'), errorId: null };
    if (problem.status === 0) return { text: i18n.t('signIn.unreachable'), errorId: null };
    const described = describeApiError(i18n.translate, problem.status, problem.body);
    return { text: described.message, errorId: described.errorId };
  });

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (busy) return;
    if (token.trim() === '') {
      problem = 'empty';
      await tick();
      field?.focus();
      return;
    }
    busy = true;
    problem = null;
    const outcome = await signIn(token.trim());
    busy = false;
    if (outcome.ok) {
      token = '';
      return;
    }
    problem = outcome.failure;
    await tick();
    field?.focus();
  }
</script>

<form onsubmit={submit} novalidate>
  <label for="{id}-token">{i18n.t('signIn.token.label')}</label>
  <input
    id="{id}-token"
    bind:this={field}
    bind:value={token}
    type={shown ? 'text' : 'password'}
    autocomplete="off"
    autocapitalize="off"
    spellcheck="false"
    aria-invalid={problem !== null}
    aria-describedby="{id}-hint {id}-problem"
  />
  <div class="row">
    <p class="hint" id="{id}-hint">{i18n.t('signIn.token.hint')}</p>
    <button class="toggle" type="button" aria-pressed={shown} onclick={() => (shown = !shown)}>
      {shown ? i18n.t('signIn.token.hide') : i18n.t('signIn.token.show')}
    </button>
  </div>

  <div id="{id}-problem" role="alert">
    {#if message}
      <p class="problem">
        {message.text}
        {#if message.errorId}
          <span class="error-id">{i18n.t('error.errorId')}: <code>{message.errorId}</code></span>
        {/if}
      </p>
    {/if}
  </div>

  {#if !protectedTransport}
    <p class="insecure">{i18n.t('signIn.insecure')}</p>
  {/if}

  <button class="submit" type="submit" disabled={busy}>
    {busy ? i18n.t('signIn.token.busy') : i18n.t('signIn.token.submit')}
  </button>
</form>

<style>
  form {
    display: grid;
    gap: var(--space-2);
  }
  label {
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  input {
    width: 100%;
    padding: var(--space-3) var(--space-4);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
    color: var(--text);
    font-family: var(--font-mono);
    font-size: var(--text-sm);
    transition:
      border-color var(--motion),
      box-shadow var(--motion);
  }
  input:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-tint);
  }
  input[aria-invalid='true'] {
    border-color: var(--danger);
  }
  .row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--space-3);
  }
  .hint {
    margin: 0;
    color: var(--text-muted);
    font-size: var(--text-xs);
  }
  .toggle {
    flex: none;
    padding: 0;
    border: 0;
    background: none;
    color: var(--accent-text);
    font: inherit;
    font-size: var(--text-xs);
    cursor: pointer;
  }
  .toggle:hover {
    text-decoration: underline;
  }
  .problem {
    margin: 0;
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-md);
    background: var(--danger-tint);
    color: var(--danger-text);
    font-size: var(--text-xs);
  }
  .error-id {
    display: block;
    margin-top: var(--space-1);
  }
  .insecure {
    margin: 0;
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-md);
    background: var(--warning-tint);
    color: var(--warning-text);
    font-size: var(--text-xs);
  }
  .submit {
    margin-top: var(--space-3);
    padding: var(--space-3) var(--space-4);
    border: 0;
    border-radius: var(--radius-md);
    background: var(--accent-solid);
    color: var(--on-accent);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
    transition: background var(--motion);
  }
  .submit:hover:not(:disabled) {
    background: var(--accent-hover);
  }
  .submit:disabled {
    cursor: progress;
    opacity: 0.7;
  }
</style>

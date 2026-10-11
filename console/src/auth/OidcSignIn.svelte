<script lang="ts">
  import { useApp } from '../app/context';

  // "Sign in with <provider>": a link, because signing in is the browser moving as a page to the
  // Engine, which takes it to the provider and, when it is done, back to [target]. The Console sees
  // no code, no token and no credential of this login: the Engine keeps the session in a cookie
  // that scripts cannot read (ADR-029). Nothing is shown when the Engine does not have it on.
  let { target }: { target: string } = $props();

  const { i18n, session } = useApp();
  const oidc = $derived(session.oidc);
</script>

{#if oidc}
  <a class="provider" href={oidc.loginUrl(target)}>
    {i18n.t('signIn.oidc.submit', { provider: oidc.label ?? 'authentik' })}
  </a>
  <p class="or" aria-hidden="true"><span>{i18n.t('signIn.oidc.or')}</span></p>
{/if}

<style>
  .provider {
    display: block;
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    background: var(--accent-solid);
    color: var(--on-accent);
    font-weight: 600;
    text-align: center;
    text-decoration: none;
    transition: background var(--motion);
  }
  .provider:hover {
    background: var(--accent-hover);
  }
  .provider:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .or {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    margin: var(--space-4) 0;
    color: var(--text-muted);
    font-size: var(--text-xs);
  }
  .or::before,
  .or::after {
    content: '';
    flex: 1;
    border-top: 1px solid var(--border);
  }
</style>

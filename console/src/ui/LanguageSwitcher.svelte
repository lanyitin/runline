<script lang="ts">
  import { useApp } from '../app/context';
  import { LOCALES } from '../i18n/locale';

  // Available before and after signing in: it belongs to the shell, not to a screen. A language is
  // named in its own language, so that it can be found whichever one is on.
  const { i18n } = useApp();
</script>

<div class="switcher" role="group" aria-label={i18n.t('lang.label')}>
  {#each LOCALES as locale (locale)}
    <button
      type="button"
      lang={locale}
      aria-pressed={i18n.locale === locale}
      onclick={() => i18n.setLocale(locale)}
    >
      {i18n.t(`lang.${locale}`)}
    </button>
  {/each}
</div>

<style>
  .switcher {
    display: inline-flex;
    padding: 2px;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
  }
  button {
    border: 0;
    border-radius: 6px;
    padding: var(--space-1) var(--space-3);
    background: transparent;
    color: var(--text-secondary);
    font: inherit;
    font-size: var(--text-xs);
    cursor: pointer;
  }
  button[aria-pressed='true'] {
    background: var(--surface);
    color: var(--accent-text);
    font-weight: 600;
    box-shadow: var(--shadow-card);
  }
</style>

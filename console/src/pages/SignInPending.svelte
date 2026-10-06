<script lang="ts">
  import { useApp } from '../app/context';

  // Stands where the sign-in page of WI-34 will be: nobody can sign in yet.
  const { i18n } = useApp();
</script>

<section class="card">
  <h1>{i18n.t('app.name')}</h1>
  <p>{i18n.t('auth.pending')}</p>
</section>

<style>
  .card {
    max-width: 24rem;
    padding: var(--space-6);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--surface);
    box-shadow: var(--shadow-card);
  }
  h1 {
    font-size: var(--text-xl);
    font-weight: 600;
  }
</style>

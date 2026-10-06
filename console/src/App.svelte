<script lang="ts">
  import { tick } from 'svelte';
  import { useApp } from './app/context';
  import { matchRoute, type AppRoute } from './app/routes';
  import Forbidden from './pages/Forbidden.svelte';
  import EnginePage from './pages/EnginePage.svelte';
  import NotFound from './pages/NotFound.svelte';
  import PagePlaceholder from './pages/PagePlaceholder.svelte';
  import SignIn from './pages/SignIn.svelte';
  import AppShell from './shell/AppShell.svelte';
  import type { Crumb } from './shell/Breadcrumb.svelte';
  import PublicLayout from './shell/PublicLayout.svelte';

  const { i18n, session, router } = useApp();

  const route = $derived<AppRoute | null>(matchRoute(router.path));
  const signedIn = $derived(session.state.status === 'authenticated' ? session.state.identity : null);
  const allowed = $derived(route !== null && (!route.adminOnly || signedIn?.role === 'admin'));

  const title = $derived.by(() => {
    if (route === null) return i18n.t('notFound.title');
    if (!allowed) return i18n.t('forbidden.title');
    return i18n.t(`page.${route.id}.title`);
  });

  const crumbs = $derived<Crumb[]>(
    route !== null && allowed
      ? [{ label: i18n.t(`nav.group.${route.group}`) }, { label: title }]
      : [{ label: i18n.t('topbar.home'), href: '/' }, { label: title }],
  );

  // After a move to another page the keyboard starts at its content, not at the old link.
  let first = true;
  $effect(() => {
    void router.path;
    if (first) {
      first = false;
      return;
    }
    tick().then(() => document.getElementById('main')?.focus());
  });
</script>

<svelte:head>
  <title>{signedIn ? `${title} · ${i18n.t('app.name')}` : i18n.t('app.name')}</title>
</svelte:head>

{#if signedIn}
  <AppShell identity={signedIn} {crumbs}>
    {#if route === null}
      <NotFound />
    {:else if !allowed}
      <Forbidden role={signedIn.role} />
    {:else if route.id === 'engine'}
      <EnginePage />
    {:else}
      <PagePlaceholder id={route.id} />
    {/if}
  </AppShell>
{:else}
  <PublicLayout>
    <SignIn />
  </PublicLayout>
{/if}

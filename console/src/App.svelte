<script lang="ts">
  import { tick } from 'svelte';
  import { useApp } from './app/context';
  import { isNavRoute, matchRoute, ROUTES, type MatchedRoute } from './app/routes';
  import AllowListPage from './pages/AllowListPage.svelte';
  import CreateRunPage from './pages/CreateRunPage.svelte';
  import EnginePage from './pages/EnginePage.svelte';
  import Forbidden from './pages/Forbidden.svelte';
  import NotFound from './pages/NotFound.svelte';
  import OverviewPage from './pages/OverviewPage.svelte';
  import PipelineDetailPage from './pages/PipelineDetailPage.svelte';
  import TriggerDetailPage from './pages/TriggerDetailPage.svelte';
  import TriggerFormPage from './pages/TriggerFormPage.svelte';
  import TriggersPage from './pages/TriggersPage.svelte';
  import PipelinesPage from './pages/PipelinesPage.svelte';
  import ResourcesPage from './pages/ResourcesPage.svelte';
  import RunDetailPage from './pages/RunDetailPage.svelte';
  import RunsPage from './pages/RunsPage.svelte';
  import UploadPage from './pages/UploadPage.svelte';
  import SignIn from './pages/SignIn.svelte';
  import AppShell from './shell/AppShell.svelte';
  import type { Crumb } from './shell/Breadcrumb.svelte';
  import PublicLayout from './shell/PublicLayout.svelte';

  const { i18n, session, router } = useApp();

  const route = $derived<MatchedRoute | null>(matchRoute(router.path));
  const signedIn = $derived(session.state.status === 'authenticated' ? session.state.identity : null);
  const allowed = $derived(route !== null && (!route.adminOnly || signedIn?.role === 'admin'));

  const title = $derived.by(() => {
    if (route === null) return i18n.t('notFound.title');
    if (!allowed) return i18n.t('forbidden.title');
    return i18n.t(`page.${route.id}.title`);
  });

  const parent = $derived(
    route?.parent ? (ROUTES.filter(isNavRoute).find((r) => r.id === route.parent) ?? null) : null,
  );
  const crumbs = $derived<Crumb[]>(
    route !== null && allowed
      ? [
          { label: i18n.t(`nav.group.${route.group}`) },
          ...(parent ? [{ label: i18n.t(`nav.${parent.id}`), href: parent.path }] : []),
          { label: title },
        ]
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
    {:else}
      <!-- The page of another thing is a new page: a run is not the run before it. -->
      {#key router.path}
        {#if route.id === 'overview'}
          <OverviewPage />
        {:else if route.id === 'pipelines'}
          <PipelinesPage />
        {:else if route.id === 'pipeline'}
          <PipelineDetailPage contentHash={route.params.contentHash} />
        {:else if route.id === 'upload'}
          <UploadPage />
        {:else if route.id === 'runs'}
          <RunsPage />
        {:else if route.id === 'runNew'}
          <CreateRunPage />
        {:else if route.id === 'run'}
          <RunDetailPage runId={route.params.runId} />
        {:else if route.id === 'engine'}
          <EnginePage />
        {:else if route.id === 'allowlist'}
          <AllowListPage />
        {:else if route.id === 'resources'}
          <ResourcesPage />
        {:else if route.id === 'triggers'}
          <TriggersPage />
        {:else if route.id === 'triggerNew'}
          <TriggerFormPage mode="create" />
        {:else if route.id === 'triggerEdit'}
          <TriggerFormPage mode="edit" />
        {:else if route.id === 'trigger'}
          <TriggerDetailPage />
        {/if}
      {/key}
    {/if}
  </AppShell>
{:else}
  <PublicLayout>
    <SignIn />
  </PublicLayout>
{/if}

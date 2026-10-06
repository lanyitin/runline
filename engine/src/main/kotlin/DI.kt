package dev.lawlan.runline.engine

import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.engine.allowlist.*
import dev.lawlan.runline.engine.artifact.*
import dev.lawlan.runline.engine.auth.ConfiguredTokenAuthenticator
import dev.lawlan.runline.engine.auth.TokenAuthenticator
import dev.lawlan.runline.engine.config.EngineConfig
import dev.lawlan.runline.engine.config.RunRuntime
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.resource.*
import dev.lawlan.runline.engine.retention.*
import dev.lawlan.runline.engine.run.*
import dev.lawlan.runline.engine.trigger.*
import dev.lawlan.runline.runner.Runner
import dev.lawlan.runline.runner.Workspaces
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.opentelemetry.api.OpenTelemetry
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/**
 * Wires the Engine. Everything an implementation needs from the outside (database, tokens, allow
 * list) comes from [EngineConfig]; swapping a backing service or provider means changing only the
 * line that provides it.
 */
fun Application.configureDependencyInjection() {
  val applicationConfig = environment.config
  dependencies {
    provide<EngineConfig> { EngineConfig.from(applicationConfig) }
    provide<OpenTelemetry> {
      getOpenTelemetry(serviceName = resolve<EngineConfig>().telemetry.serviceName)
    }
    provide<TokenAuthenticator> { ConfiguredTokenAuthenticator(resolve<EngineConfig>().tokens) }
    provide<AllowListStore> { PostgresAllowListStore(resolve<DataSource>()) }
    provide<AllowListProvider> { DatabaseAllowListProvider(resolve<AllowListStore>()) }
    provide<AllowListTelemetry> { AllowListTelemetry(resolve<OpenTelemetry>()) }
    provide<AllowListAdmin> {
      AllowListAdmin(
          resolve<AllowListStore>(),
          SafetyAnalyzer(),
          Clock.systemUTC(),
          resolve<AllowListTelemetry>(),
          Path.of(System.getProperty("java.io.tmpdir")),
      )
    }
    provide<DataSource> { dataSourceOf(resolve<EngineConfig>().database) }
    provide<ArtifactStore> { PostgresArtifactStore(resolve<DataSource>()) }
    provide<UploadStaging> { UploadStaging(Path.of(System.getProperty("java.io.tmpdir"))) }
    provide<UploadTelemetry> { UploadTelemetry(resolve<OpenTelemetry>()) }
    provide<UploadService> {
      UploadService(
          JarExpansionGuard(resolve<EngineConfig>().upload.jarLimits),
          RunnerPipelineNameRule(),
          SafetyAnalyzer(),
          resolve<AllowListProvider>(),
          resolve<ArtifactStore>(),
          resolve<UploadTelemetry>(),
          Clock.systemUTC(),
      )
    }
    provide<ArtifactCatalog> { ArtifactCatalog(resolve<ArtifactStore>()) }

    // Runs (WI-08). Dependencies are closed in reverse declaration order, so the scheduler
    // (declared
    // after the Runner) stops its runs before the Runner is closed.
    provide<Clock> { Clock.systemUTC() }
    provide<DefinitionStore> { PostgresDefinitionStore(resolve<DataSource>()) }
    provide<RunStore> { PostgresRunStore(resolve<DataSource>()) }
    provide<ResourceStore> { PostgresResourceStore(resolve<DataSource>()) }
    provide<ResourceAvailability> { ResourceAvailability(resolve<ResourceStore>()) }
    provide<RunLogStore> { PostgresRunStore(resolve<DataSource>()) }
    provide<RunRuntime> { RunRuntime.fromDirectory(resolve<EngineConfig>().runs.runtimeDir) }
    provide<Workspaces> {
      Workspaces(
          resolve<EngineConfig>().workspace.config,
          resolve<Clock>(),
          WorkspaceTelemetry(resolve<OpenTelemetry>()),
      )
    }
    provide<Runner> {
      Runner(
          resolve<Workspaces>(),
          resolve<EngineConfig>().runs.maxConcurrent,
          runtimeClasspath = resolve<RunRuntime>().classpath,
      )
    }
    provide<RunTelemetry> {
      RunTelemetry(resolve<OpenTelemetry>()).also {
        it.observeLoaders(resolve<Runner>()::loaderStats)
      }
    }
    provide<RunProgress> {
      RunProgress(resolve<RunStore>(), resolve<RunTelemetry>(), resolve<Clock>())
    }
    // Shared resources (WI-09). Declared before the scheduler so that it is closed after it.
    provide<ResourceTelemetry> { ResourceTelemetry(resolve<OpenTelemetry>()) }
    provide<ResourceCoordinator> {
      ResourceCoordinator(
          resolve<ResourceAvailability>(),
          resolve<Clock>(),
          resolve<EngineConfig>().runs.resourceWaitTimeout,
          resolve<ResourceTelemetry>(),
      )
    }
    provide<ResourceGate> { resolve<ResourceCoordinator>() }
    provide<ResourceAdmin> {
      val coordinator = resolve<ResourceCoordinator>()
      ResourceAdmin(resolve<ResourceStore>(), resolve<Clock>(), coordinator::wake)
    }
    provide<ResourceCatalog> {
      ResourceCatalog(resolve<ResourceStore>(), resolve<ResourceCoordinator>())
    }
    provide<ResourceWarnings> { ResourceWarnings(resolve<ResourceAvailability>()) }
    provide<RunScheduler> {
      val runs = resolve<EngineConfig>().runs
      RunScheduler(
          resolve<Runner>(),
          resolve<DefinitionStore>(),
          resolve<RunProgress>(),
          resolve<RunLogStore>(),
          resolve<ResourceGate>(),
          resolve<RunTelemetry>(),
          resolve<Clock>(),
          SchedulerConfig(
              runs.maxConcurrent,
              runs.timeout,
              runs.shutdownGrace,
              Path.of(System.getProperty("java.io.tmpdir")),
          ),
      )
    }
    provide<RunService> {
      RunService(
          resolve<DefinitionStore>(),
          resolve<RunStore>(),
          resolve<RunScheduler>(),
          resolve<ResourceAvailability>(),
          resolve<RunTelemetry>(),
          resolve<Clock>(),
      )
    }
    provide<RunCatalog> { RunCatalog(resolve<RunStore>(), resolve<RunLogStore>()) }
    provide<RunLogFollower> { RunLogFollower(resolve<RunCatalog>(), Duration.ofMillis(200)) }
    provide<UnsafeExecutionSettings> {
      UnsafeExecutionSettings(resolve<DefinitionStore>(), resolve<Clock>())
    }
    // Triggers (WI-07). The cron scheduler is declared after everything it fires through, so it is
    // closed first and no run is created while the run machinery shuts down.
    provide<TriggerStore> { PostgresTriggerStore(resolve<DataSource>()) }
    provide<TriggerAdmin> {
      TriggerAdmin(resolve<DefinitionStore>(), resolve<TriggerStore>(), resolve<Clock>())
    }
    provide<TriggerCatalog> { TriggerCatalog(resolve<DefinitionStore>(), resolve<TriggerStore>()) }
    provide<TriggerTelemetry> { TriggerTelemetry(resolve<OpenTelemetry>()) }
    provide<TriggerFirer> {
      TriggerFirer(resolve<RunService>(), resolve<TriggerStore>(), resolve<TriggerTelemetry>())
    }
    provide<WebhookReceiver> {
      WebhookReceiver(
          resolve<TriggerStore>(),
          resolve<TriggerFirer>(),
          resolve<TriggerTelemetry>(),
          resolve<Clock>(),
      )
    }
    provide<CronScheduler> {
      CronScheduler(resolve<TriggerStore>(), resolve<TriggerFirer>(), resolve<Clock>())
    }
    provide<RunRecovery> {
      RunRecovery(resolve<RunStore>(), resolve<Workspaces>(), resolve<Clock>())
    }
    provide<WorkspaceSweeper> {
      WorkspaceSweeper(resolve<Workspaces>(), resolve<EngineConfig>().workspace.sweepInterval)
    }
    // Retention of runs, logs and trigger firings (WI-20). Separate from the retention of private
    // run directories above: its own settings and its own schedule.
    provide<RetentionStore> { PostgresRetentionStore(resolve<DataSource>()) }
    provide<RetentionTelemetry> { RetentionTelemetry(resolve<OpenTelemetry>()) }
    provide<RetentionCleaner> {
      RetentionCleaner(
          resolve<RetentionStore>(),
          resolve<EngineConfig>().retention,
          resolve<Clock>(),
          resolve<RetentionTelemetry>(),
      )
    }
    provide<RetentionSweeper> {
      val cleaner = resolve<RetentionCleaner>()
      RetentionSweeper({ cleaner.clean() }, resolve<EngineConfig>().retention.interval)
    }
  }
}

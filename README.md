# runline

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:
 * [Ktor Documentation](https://ktor.io/docs/home.html)
 * [Ktor GitHub page](https://github.com/ktorio/ktor)
 * [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).


## Features
Here's a list of features included in this project:

| Name | Description |
|------|-------------|
| [Authentication](https://start.ktor.io/p/io.ktor/server-auth) | Provides extension point for handling the Authorization header |
| [Authentication Basic](https://start.ktor.io/p/io.ktor/server-auth-basic) | Handles 'Basic' username / password authentication scheme |
| [Authentication OAuth](https://start.ktor.io/p/io.ktor/server-auth-oauth) | Handles OAuth Bearer authentication scheme |
| [Status Pages](https://start.ktor.io/p/io.ktor/server-status-pages) | Provides exception handling for routes |
| [kotlinx.serialization](https://start.ktor.io/p/io.ktor/server-kotlinx-serialization) | Handles JSON serialization using kotlinx.serialization library |
| [Content Negotiation](https://start.ktor.io/p/io.ktor/server-content-negotiation) | Provides automatic content conversion according to Content-Type and Accept headers |
| [PostgreSQL](https://start.ktor.io/p/org.jetbrains/server-postgres) | Adds Postgres database support |
| [WebSockets](https://start.ktor.io/p/io.ktor/server-websockets) | Adds WebSocket protocol support for bidirectional client connections |
| [OpenTelemetry](https://start.ktor.io/p/io.opentelemetry.instrumentation/server-open-telemetry) | Instruments applications with distributed tracing, metrics, and logging for comprehensive observability |
| [Metrics](https://start.ktor.io/p/io.ktor/server-metrics) | Adds supports for monitoring several metrics |
| [Dependency Injection](https://start.ktor.io/p/io.ktor/server-di) | Enables dependency injection for your server |
| [Shutdown URL](https://start.ktor.io/p/io.ktor/server-shutdown-url) | Enables a URL that shuts down the server when accessed |
| [Swagger](https://start.ktor.io/p/io.ktor/server-swagger) | Serves Swagger UI for your project |

## Structure
This project includes the following modules:

| Path | Description |
|------|-------------|
| core | Pipeline authoring contract (no Ktor, no OpenTelemetry helpers, no Engine) |
| runner | Pipeline Runner (depends on core; no Ktor, no Engine) |
| analyzer | Static analysis and safe/unsafe verdicts (JDK and Kotlin standard library only; no core, runner, Ktor, Engine or database) |
| devkit | Development entry point: runs and debugs a pipeline through the Runner and shows the unsafe verdict (depends on core, runner and analyzer; no Ktor, no Engine, no database). Guide: [docs/stable/devkit-guide.md](docs/stable/devkit-guide.md) |
| engine | Engine (Ktor server, OpenTelemetry helpers; depends on runner, core and analyzer) |

## Building & Running
To build or run the project, use one of the following tasks:


| Task | Description |
|------|-------------|
| `./gradlew :engine:test`    | Run the tests     |
| `./gradlew :devkit:test`    | Run the development entry point's tests |
| `./gradlew :engine:build`   | Build the project |
| `./gradlew :engine:run`     | Run the engine    |

If the engine starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## Engine runs: packaging and configuration
Every run executes in its own class loader whose parent is only the JDK, so a run sees the Runner, core and the Kotlin library it is given, plus the pipeline jar, and nothing of the Engine. For that, the Engine is deployed as two parts that must not be merged: `engine.jar` (the fat jar) and a `run-runtime/` directory holding the jars of the Runner, core and the Kotlin library. `./gradlew :engine:engineDistribution` assembles both into `engine/build/engine-dist/`. The Engine refuses to start when the run runtime directory is missing a part, or holds Engine classes.

| Setting (environment variable) | Meaning |
|--------------------------------|---------|
| `RUNLINE_RUNTIME_DIR` (required) | Directory with the run runtime jars (`run-runtime/` of the distribution) |
| `RUNLINE_MAX_CONCURRENT_RUNS` (required) | Runs executing at once; further runs queue |
| `RUNLINE_SHARED_ROOT`, `RUNLINE_RUN_ROOT` (required) | Pipeline shared directories (persistent storage) and run private directories (scratch space) |
| `RUNLINE_WORKSPACE_MAX_BYTES`, `RUNLINE_FAILED_RUN_RETENTION_SECONDS` (required) | Size limit per directory; how long a failed, cancelled or interrupted run keeps its private directory |
| `RUNLINE_RUN_TIMEOUT_SECONDS` (optional) | Cooperative limit on a pipeline body; none by default |
| `RUNLINE_SHUTDOWN_GRACE_SECONDS` (optional, 30) | The grace time of a shutdown: how long it waits for requests in flight, and again for runs it asked to stop (see "Shutdown" below) |
| `UPLOAD_MAX_BYTES` (optional, 50 MiB) | Largest jar accepted by the upload API, as sent |
| `UPLOAD_MAX_ENTRIES` (optional, 20000) | Most entries a jar may hold |
| `UPLOAD_MAX_ENTRY_BYTES` (optional, 64 MiB) | Most one entry of a jar may expand to |
| `UPLOAD_MAX_EXPANDED_BYTES` (optional, 256 MiB) | Most all entries of a jar may expand to together |
| `OTEL_SERVICE_NAME` (optional, `runline-engine`) | How the Engine names itself in logs, traces and metrics; the same name for all three |
| `RUNLINE_WORKSPACE_SWEEP_INTERVAL_SECONDS` (optional, 300) | How often expired private directories are removed |
| `RUNLINE_RESOURCE_WAIT_TIMEOUT_SECONDS` (optional, 3600) | How long a run may wait for the shared resources it declares before it fails with "等待資源逾時"; pipelines cannot change it |
| `RUNLINE_RUN_RETENTION_SECONDS` (optional, 2592000 = 30 days, at least 3600) | How long after it ended a run is kept; then it is removed with its log (see "Retention" below). Not the retention of private run directories |
| `RUNLINE_RUN_LOG_RETENTION_SECONDS` (optional, the run's, at least 3600, not above the run's) | How long after the run ended its log is kept; shorter than the run's makes the log go first |
| `RUNLINE_WEBHOOK_DEDUP_WINDOW_SECONDS` (optional, 604800 = 7 days, at least 86400) | How long a webhook firing is kept, which is how long a repeated delivery identifier is recognised |
| `RUNLINE_CRON_FIRING_RETENTION_SECONDS` (optional, 2592000, at least 3600) | How long a cron firing is kept |
| `RUNLINE_RETENTION_INTERVAL_SECONDS` (optional, 3600) | How often the clean-up runs (it also runs once at start) |
| `RUNLINE_RETENTION_BATCH_SIZE` (optional, 1000) | Rows one clean-up statement removes |
| `ALLOWLIST_PACKAGES` (optional) | Only the content the allow list gets the first time the Engine starts (see "Allow list" below); unset means the project's default list |

## Upload limits and pipeline names (WI-18)
A jar is checked before anything reads its contents: the number of entries, what each entry expands to, and what all entries expand to together must stay within the limits above, or the upload is refused (422 `jar_too_many_entries`, `jar_entry_too_large` or `jar_expanded_too_large`, naming the limit) and nothing is stored. The sizes a zip declares are not trusted: the entries are really inflated, into nothing, and counted, stopping at the first limit exceeded, so a small jar that expands to gigabytes is refused after a limit's worth of work, and the analyzer is never given a jar the check has not passed. The defaults leave room for a jar with its dependencies bundled (a few thousand entries, tens of megabytes expanded) and stop a bomb well before it can exhaust memory or disk. A pipeline's name becomes the name of its directories, so an upload is refused (422 `invalid_pipeline_name`, naming each pipeline and the allowed characters: letters, digits, `.`, `_`, `-`, and not `.` or `..`) when any pipeline in the jar has a name the Runner would not make a directory for; the rule is the Runner's own, not a copy.

## Errors and shutdown (WI-18)
An unexpected failure is answered 500 `internal_error` with a generic message and an `errorId`; the cause, stack and anything else about it are only in the log, in the line `Unexpected error <errorId> while ...`. On SIGTERM the Engine stops admitting requests (new ones get 503 `shutting_down`), waits up to `RUNLINE_SHUTDOWN_GRACE_SECONDS` for those in flight, then stops its runs (marking them interrupted, for up to the same time) and exits; open WebSockets do not delay it. The API, every endpoint with who may call it, its inputs, outputs and error codes, is in [docs/stable/pipeline-engine/08-api.md](docs/stable/pipeline-engine/08-api.md); `ApiDocumentationTest` fails when a route and that document disagree.

Database migrations are a separate one-off process (`./gradlew :engine:migrate`, or `java -cp engine.jar dev.lawlan.runline.engine.db.MigrateKt`); the Engine only checks that the schema is current. `./gradlew :engine:packagedTest` starts the packaged Engine as its own process against a real PostgreSQL (Docker needed) and checks, among other things, that a run cannot see Engine classes; it is part of `:engine:check`.

## Build info and release builds (WI-28, ADR-016)
The build writes what it knows about itself into `engine.jar` (resource `runline-build-info.properties`, made by `gradle/build-info.gradle.kts`): the project version, the full hash of `HEAD`, whether the working tree is dirty (a tracked file changed, or an untracked file that is not ignored; ignored build output does not count), and the time of the `HEAD` commit (`buildTime`, UTC; never the clock of the build). Nothing else goes in: no host, user, path or environment, so the same commit gives the same bytes. Without git information (no repository, no commit yet, git not installed) the hash is `unknown`, `dirty` is true and `buildTime` is `1970-01-01T00:00:00Z`. The task `generateBuildInfo` rewrites the file only when one of these values changes, so nothing after it is redone for the same `HEAD` and tree state.

`GET /api/v1/info` (no token: `version`, `commitHash`, `dirty`; reads only the jar's resource, so it answers when the database does not) and `GET /api/v1/system` (developer or administrator token: the same plus `buildTime`, `jdk`, `startedAt`, `uptimeSeconds`, `allowListVersion` and `caller` with the name and role of the token) are described in [docs/stable/pipeline-engine/08-api.md](docs/stable/pipeline-engine/08-api.md). Neither is cached; neither holds a token, a path, a host name or any configuration value.

**Release flag.** `-Prunline.release=true` (a Gradle property, for example `./gradlew :engine:engineDistribution -Prunline.release=true`) marks a release build: when the hash is `unknown` or the working tree is dirty, `generateBuildInfo`, and so every build that needs it, fails and says why. An ordinary build is not affected. Nothing deployed should be built without it.

## Shared resources (WI-09, ADR-007)
Administrators define named resources with a capacity (1 is mutual exclusion) through `/api/v1/resources` (administrator token only; developers get 403, even to look). A pipeline declares the names it needs in its metadata; the Engine takes all of them together, with a concurrency slot, before the pipeline body starts, and gives them back when the run ends in any way. A run that waits holds no slot and waits in first-in-first-out order. Definitions (name, capacity, enabled, who changed them) are in PostgreSQL (migration `V3__shared_resource.sql`); holders and waiters live in the Engine's memory only and are gone after a restart, when unfinished runs become interrupted anyway.

| Request | Meaning |
|---------|---------|
| `POST /api/v1/resources` `{name, capacity}` | Define a resource: 201; 409 `resource_exists`; 422 `invalid_resource` (name: 1-100 of letters, digits, `.`, `_`, `-`, starting with a letter or digit; capacity at least 1) |
| `GET /api/v1/resources`, `GET /api/v1/resources/{name}` | Definition plus holders (run, pipeline, since when) and waiters (in serving order, with waiting time); 404 `resource_not_found` |
| `PATCH /api/v1/resources/{name}` `{capacity?, enabled?}` | Change capacity or enable/disable; 422 `invalid_resource` when empty or capacity below 1. Lowering the capacity never takes a resource from a holder; disabling fails the runs that wait for it |
| `POST /api/v1/resources/{name}/holders/{runId}/release` | Force a holder to let go of that resource (logged as a warning with the administrator's name); the run itself keeps running, cancel it separately if wanted; 404 `not_a_holder` |

Creating a run whose pipeline declares a resource that is not defined or is disabled is refused with 409 `resources_unavailable` (each resource listed as `unknown` or `disabled`) and leaves no run. An upload of such a pipeline still succeeds and is judged safe or unsafe as before; each definition in the response carries `warnings` about it. A run whose wait runs out ends `FAILED` with failure type `ResourceWaitTimeout`; one whose resource became unavailable while waiting ends `FAILED` with `ResourceUnavailable`. A holder that outlived its run timeout keeps the resource until it really ends or an administrator forces it. Metrics: `runline.resources.wait.duration`, `runline.resources.hold.duration`, `runline.resources.queue.length`, `runline.resources.holders`, `runline.resources.force_released`. This is single-instance state; before running several Engine instances it must move to shared storage.

The development entry (`devkit`) has the same meaning locally: the declared resources are acquired at once and released when the run ends (printed as `[resources]` lines), with no waiting and no check against any definition.

## Allow list (WI-10, ADR-002, ADR-013, ADR-014)
The allow list that decides which pipelines are safe lives in PostgreSQL (migration `V5__allowlist.sql`) and is kept through `/api/v1/allowlist` (administrator token only; developers get 403, even to look; the changer is recorded as the name of the token, never the token). The first time the Engine starts it gets its initial content: the project's default list (`DefaultAllowList` in the analyzer module, the one place that content is defined, shared with the development entry point `devkit`) or, when `ALLOWLIST_PACKAGES` is set, that list (`package`, `package:exact` for "this package only", `class:full.Name`). After that `ALLOWLIST_PACKAGES` is not read again; a restart never overwrites the administered list, and an Engine whose database already holds a list does not get entries a newer default adds (default-2 added `class:kotlin.io.CloseableKt` and `class:java.io.Closeable`): an administrator adds them through the API. The text form is shared with the development entry point (`AllowListText` in the analyzer module). Entries are `package` or `class`, kept apart by a `kind` field.

Every change of the entries is a new version (who, when, what, and how many verdicts it flipped), and runs in one database transaction together with judging every stored definition again with the analyzer on the jar kept in the database: either the list and all verdicts change, or nothing does. Each pipeline shows the version it was judged under (`allowListVersion`). A definition that becomes unsafe through this loses its "may run unsafe" setting; other settings stay; runs in progress are not touched. Any operation takes `?preview=true` to list the definitions that would flip without changing anything. `POST /api/v1/allowlist/recheck` judges everything again with the current list and the current analysis rules (after the analyzer was updated); it makes no new version. Verdicts stored before the list was administered (version `config`) stay readable and are only changed by a change or a recheck, never by starting.

Scale: a change, a preview and a recheck read and analyse every stored jar, one at a time (memory holds one jar), so the time grows with the total size of the stored jars and the request lasts until it is done. Changes and rechecks hold the list's exclusive lock and one database transaction for that whole time: other list changes wait, and an upload's last step (storing its verdict) waits and, if the version changed meanwhile, the jar is judged again with the new one; the analysis of uploads, reads and creating runs go on. A preview takes no lock. There is no background job or progress report; if the number of stored jars makes this too slow, the next step is to judge in batches, which would give up the one-transaction guarantee.

Metrics: `runline.allowlist.operations` (by `operation` and `preview`), `runline.allowlist.verdict.changes` (by `direction`: `to_safe`, `to_unsafe`); each operation is traced as `runline.allowlist.change` and each applied change is logged.

## Triggers (WI-07, ADR-005)
Administrators bind triggers to one version of a pipeline through `/api/v1/triggers` (administrator token only; developers get 403, even to look; the changer is recorded as the name of the token). A trigger is a cron schedule or a webhook, holds the fixed parameters its runs get (checked against the version's metadata when it is created or changed, and again when the binding moves to another version; defaults of omitted optionals are applied, see `effectiveParameters`), and never follows a newer version. Every firing, cron or webhook, creates its run through `RunService.create` with the trigger as the run's source, so unsafe settings, parameter checks, shared resources and the concurrency limit apply exactly as for a manual run. When creation is refused the trigger stays enabled and the next firing tries again; the refusal is logged, counted (`runline.triggers.refused`, with trigger and reason) and kept in the firing record. The only configuration triggers have is how long their firings are kept (see "Retention"); the schema comes with migration `V4__trigger.sql`, run through the usual one-off `migrate` process.

| Request | Meaning |
|---------|---------|
| `POST /api/v1/triggers` `{name, kind: "cron"\|"webhook", contentHash, pipeline, parameters?, cron?, timeZone?, enabled?}` | Create: 201 `{trigger, secret}` (`secret` only for a webhook, shown this once); 404 `definition_not_found`; 409 `trigger_exists`; 422 `invalid_parameters` (each parameter named) or `invalid_trigger` (`problem`: `name`, `cron_required`, `cron_expression`, `time_zone`, `schedule_not_allowed`); 400 `bad_request` |
| `GET /api/v1/triggers`, `GET /api/v1/triggers/{name}` | The triggers; a webhook shows only `secretConfigured` and `secretRotatedAt`, never the secret or its hash; 404 `trigger_not_found` |
| `PATCH /api/v1/triggers/{name}` `{contentHash?, pipeline?, parameters?, enabled?, cron?, timeZone?}` | Change the binding, parameters, schedule, or enable/disable; same refusals as creation; a change that does not validate changes nothing |
| `DELETE /api/v1/triggers/{name}` | Unbind: 204; its record of firings goes with it. An artifact a trigger is bound to cannot be deleted (409 `in_use`) until then |
| `POST /api/v1/triggers/{name}/rotate-secret` | New secret, shown once; the old one is void at once; 409 `not_a_webhook` |
| `GET /api/v1/triggers/{name}/firings[?limit=]` | Recent firings, newest first: time, `scheduledFor` (cron) or `deliveryId` (webhook), `outcome` (`run_created`, `refused`, `failed`, `interrupted`, `pending`), `reason` and `detail` when refused, `runId` |

**Cron.** A standard five-field expression (`minute hour day-of-month month day-of-week`) in an IANA time zone (`timeZone`, UTC when left out), parsed and evaluated by the [cron-utils](https://github.com/jmrozanec/cron-utils) library (9.2.1, Apache-2.0; version in `gradle/libs.versions.toml`). The scheduler works from the database and the clock alone: after a restart the enabled triggers simply go on and the times missed while the Engine was down are not made up; a scheduled time fires once (it is claimed in `trigger_firing` first); a change to a trigger applies from the moment of the change; a time up to a minute late still fires, and when several are due only the latest does. Daylight saving time: a wall clock time that does not exist (clocks go forward) fires once, the same distance into the end of the gap (02:30 becomes 03:30 when 02:00 jumps to 03:00); a wall clock time that happens twice (clocks go back) fires once, the first time, so an expression that fires every hour or more often has no firing in the repeated hour.

**Webhook.** `POST /api/v1/webhooks/{name}` (no Bearer token; the call proves itself with the trigger's secret):

| Header | Meaning |
|--------|---------|
| `X-Runline-Webhook-Secret` | The secret shown when the trigger was created or its secret rotated. Only its SHA-256 is stored; compared in constant time |
| `X-Runline-Delivery-Id` | The caller's identifier of this delivery, 1 to 200 visible ASCII characters, required. Unique per trigger in the database: a delivery sent again, even at the same moment, creates no second run |

Answers: 202 `{"status":"accepted"}` once authenticated, whatever becomes of the run, and for a delivery already received (the same answer as the first time); 401 `unauthorized` with one fixed body for a missing or wrong secret, a disabled trigger, a trigger that is not a webhook and one that does not exist; 400 `invalid_delivery_id` when authenticated but the identifier is missing or malformed; 500 `internal_error` when something unexpected failed, after which the same delivery can be sent again. The request body is never read and cannot change the run's parameters. Calls that fail authentication are logged and counted (`runline.triggers.webhook.rejected`, without the name from the request) but not recorded in the firing record, so nobody without the secret can make it grow.

Metrics: `runline.triggers.firings`, `runline.triggers.refused`, `runline.triggers.failed`, `runline.triggers.webhook.duplicates`, `runline.triggers.webhook.rejected`. Secrets and API tokens appear in no log line, metric, trace or error message. The record of firings has no retention yet and grows by one row per cron occurrence and per accepted delivery (a per-minute cron adds about 525,000 rows a year). The scheduler and the delivery claim assume a single Engine instance; with several instances the claim in the database would still keep a time or delivery from firing twice, but each instance would run its own scheduler and nothing coordinates them beyond that.

## Retention of runs, logs and trigger firings (WI-20, ADR-005)
Runs, their logs and trigger firings are removed when they are old enough, so the database does not grow without end. This is separate from the retention of private run directories (`RUNLINE_FAILED_RUN_RETENTION_SECONDS`, WI-11), which has its own setting and schedule.

| What | Counted from | Default | Removed when |
|------|--------------|---------|--------------|
| Run and its whole log | when the run ended | 30 days (`RUNLINE_RUN_RETENTION_SECONDS`) | the run is in a final state (succeeded, failed, cancelled, interrupted, timed out) and older than the limit |
| Log alone | when the run ended | the run's (`RUNLINE_RUN_LOG_RETENTION_SECONDS`, never longer than the run's) | the same, the run record stays |
| Webhook firing | when it happened | 7 days (`RUNLINE_WEBHOOK_DEDUP_WINDOW_SECONDS`, at least 24 hours) | older than the window; this is also the dedup window |
| Cron firing | when it happened | 30 days (`RUNLINE_CRON_FIRING_RETENTION_SECONDS`, at least 1 hour) | older than the limit |

A run that has not ended (queued, waiting for resources, initializing, running, timed out but unfinished) is never removed, however old; neither is a firing that is still pending. Inside the dedup window a delivery identifier that is sent again creates no run, and the first one after it is a new delivery: shortening the window shortens the protection. The floors protect against settings that would defeat their purpose: a sender that was told "not accepted" may retry for as long as its own schedule lasts (commonly up to a day or more), a cron time is fired up to a minute late, and a run's own last writes and readers need time. Removing a firing leaves the run it created; removing a run keeps its firing, with no run. Pipeline definitions, artifacts and triggers are never removed, and an artifact a run or a trigger refers to still cannot be deleted; once the run is cleaned up it no longer holds the artifact.

The clean-up runs once when the Engine starts (in the background, startup does not wait for it) and then every `RUNLINE_RETENTION_INTERVAL_SECONDS`, on one thread, so passes never overlap and it assumes a single Engine instance; it is safe to repeat. Work is bounded: each statement removes at most `RUNLINE_RETENTION_BATCH_SIZE` rows in a short transaction of its own, and a pass takes at most 100 batches of each kind (log entries, runs, webhook firings, cron firings), leaving the rest to the next pass. Rows are found through the partial indexes of migration `V6__retention_indexes.sql` (run it with the usual one-off `migrate` process before starting the new Engine; the Engine only checks the version), so a statement reads from the oldest end of an index and stops after one batch; the same migration indexes `trigger_firing.run_id`, without which removing a run would have to read every firing to clear its reference. When the log limit is shorter than the run's, each log statement also steps over the runs between the two limits whose log is already gone (one index-only probe each; about 0.1 s for 70 000 such runs, measured on a development database); with the default, equal limits, there are none. Log entries go first, and a run goes once its log is gone, so a run with a very large log is removed over several passes. While that happens the run can be seen with part of its log; it is already past its retention. The clean-up takes no advisory lock and only locks the rows it removes: it neither waits for nor holds up a change of the allow list, a recheck or an upload, and reads and log streams read a snapshot. A query or a stream under way when its run is removed is not interrupted: a query gets its answer, and a stream ends normally after what it has already delivered (an unbroken start of the log); afterwards the run answers 404.

Metrics (meter `runline.retention`): `runline.retention.runs.removed`, `runline.retention.log_entries.removed`, `runline.retention.trigger_firings.removed` (by `kind`: `webhook`, `cron`), `runline.retention.failures` (by `step`: `log_entries`, `runs`, `webhook_firings`, `cron_firings`), `runline.retention.passes` (by `result`: `completed`, `failed`) and `runline.retention.duration` (seconds). Each pass that removed something is logged at info with the counts and its duration; a pass that stopped at its batch limit logs a warning. A failing step is logged with its error, counted, and tried again at the next pass; the other steps of the pass are done and the Engine goes on serving. Nothing here handles a token or a secret.

## Code formatting (ktfmt)
Kotlin sources (`main` and `test` of `core`, `runner`, `analyzer`, `devkit`, `engine`, plus each module's `build.gradle.kts`) are formatted with [ktfmt](https://github.com/facebook/ktfmt) using its default rules, through the `com.ncorti.ktfmt.gradle` Gradle plugin. The plugin version (which pins the bundled ktfmt version, currently 0.64) is declared once in `gradle/libs.versions.toml` (`ktfmt-gradle`). ktfmt is a build-time tool only; it is not a runtime dependency of any module. Generated output under `build/` is not touched.

| Command | Description |
|---------|-------------|
| `./gradlew ktfmtFormat` | Format all modules in place (idempotent) |
| `./gradlew ktfmtCheck`  | Check only; fails and lists the non-compliant files |
| `./gradlew :core:ktfmtFormat` | Format a single module |

`ktfmtCheck` is intentionally not wired into `check` or `build`; run it explicitly. The commands are identical in the devcontainer and on a local machine (JDK 25 via the Gradle toolchain, no extra installation). The first run needs network access to resolve the plugin and ktfmt from the Gradle plugin portal / Maven Central; if they cannot be resolved the build fails explicitly.

## Node toolchain (WI-30, ADR-015)
The Console frontend is built with Node. Node is a build-time tool only: it is not part of the runtime environment or of any release artifact.

- **Version pin:** the version is written once, in `.node-version` at the repository root (an exact version, currently the Node 24 LTS line). Both the devcontainer and `mise` read this file; to change the version, edit only this file (then rebuild the devcontainer).
- **Devcontainer:** `.devcontainer/Dockerfile` installs exactly that version from nodejs.org (checksum-verified) on top of the JDK 25 image. After a rebuild, `node` and `npm` are on the `PATH`; nothing needs to be installed by hand.
- **Local machine (no devcontainer):** install [mise](https://mise.jdx.dev) and run `mise install` in the repository root. `mise` picks up `.node-version` when the idiomatic version file setting is enabled for node; `mise.toml` is a personal, untracked file, so add this to yours once: `[settings]` / `idiomatic_version_file_enable_tools = ["node"]` (or run `mise settings add idiomatic_version_file_enable_tools node`).
- **Verify:** `node --version` must print the version in `.node-version`; `npm --version` must also work. In a mise-managed shell, `mise ls --current` lists node with the source `.node-version`.

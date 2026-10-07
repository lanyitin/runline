# devkit: Development entry point

Runs and debugs a pipeline on the developer's machine through the same Runner the Engine uses (own class loader per run,
one dedicated platform thread named by the run id), after printing the same safe/unsafe verdict the Engine computes
(`SafetyAnalyzer` from the analyzer module; nothing is re-implemented here). No Engine, database, Ktor or OpenTelemetry
is needed.

The module depends only on `core`, `runner` and `analyzer`. It is not loaded into a run's class loader, so a pipeline
cannot see it. A boundary test (`DevkitBoundaryTest`) checks that Engine, Ktor, OpenTelemetry helpers and a database
driver are absent.

## Use

Main class `dev.lawlan.runline.devkit.DevMainKt`, arguments `<pipeline-jar> <pipeline-class> [name=value ...]`, working
directory = the development project. Output is `[status]`, `[stdout]` and `[stderr]` lines plus the verdict header.
Exit code: 0 succeeded, 1 the run did not succeed, 2 nothing was run (bad arguments, settings, jar or class),
3 no result within the wait limit.

| Environment variable | Meaning | Default |
|---|---|---|
| `RUNLINE_SHARED_ROOT` | Root of the per-pipeline shared directories (kept between executions) | `.runline/shared` in the project |
| `RUNLINE_RUN_ROOT` | Root of the per-run private directories (fresh each execution) | `.runline/runs` in the project |
| `RUNLINE_RESOURCE_ROOT` | Root of the files of `file` shared resources (ADR-019) | `.runline/resources` in the project |
| `RUNLINE_RESOURCES` | The local definitions of shared resources (there is no Engine to ask): comma-separated `name=type[:path]`, e.g. `audit=file:logs/out.txt,gate=counter`; a resource the pipeline declares with a type must be defined here with that type, or nothing runs | none |
| `RUNLINE_ALLOW_LIST` | Comma-separated allow list entries that replace the default list completely: a package (`kotlin`), a package followed by `:exact` for "this package only" (`kotlin:exact`), or `class:` plus a fully qualified class (`class:java.io.PrintStream`: that class and its nested classes only). The format is the Engine's `ALLOWLIST_PACKAGES` format (shared, `AllowListText` in the analyzer module). An invalid entry fails at startup; the earlier trailing `!` form is no longer accepted and fails with a message that says to use `:exact`. Set but empty means an empty list | the project's default allow list (`DefaultAllowList` of the analyzer module) |
| `RUNLINE_ALLOW_LIST_VERSION` | Version shown with the verdict when `RUNLINE_ALLOW_LIST` is set; ignored for the default list, which carries its own version | `local` (`local-empty` for an empty list) |
| `RUNLINE_SHOW_ALLOW_LIST` | `true` lists every entry of the list used, in the form `RUNLINE_ALLOW_LIST` reads | off |
| `RUNLINE_DEV_WAIT_SECONDS` | How long to wait for a run's result before reporting and asking it to stop | `3600` |
| `RUNLINE_RECORD` | `true` records the run's IO and writes a metadata proposal (see Recording); anything else fails at startup | off |
| `RUNLINE_RECORD_MAX_EVENTS` | With recording: how many events are kept one by one (0 keeps only the summary); the rest is summarized, and the proposal is complete either way | `10000` |
| `RUNLINE_RECORD_DIR` | With recording: where the output goes, in a sub-directory named by the run id | `.runline/recordings` |

The verdict output says whether the list is the default or an override, its version and its number of entries, and that it is the development entry point's list, not the Engine's current one (an administrator may have changed the Engine's list).

Relative paths are relative to the working directory. A pipeline timeout is deliberately not applied, so pausing at a
breakpoint does not trip it.

## Recording

With `RUNLINE_RECORD=true` the run is recorded and the metadata limits for files, network and processes are relaxed
(everything is allowed and noted), so one run gives a complete proposal. The directory boundaries (absolute paths,
leaving a directory, symbolic links out) and the disk usage limit still apply; refused attempts are noted but not
proposed. The output says so before the run starts, and the result is not what normal mode would do. Afterwards
`<RUNLINE_RECORD_DIR>/<run id>/events.txt` (every event: category, scope and relative path / host and port / command,
read or write, order; no content, arguments or absolute paths) and `proposal.md` are written and the proposal is
printed. The proposal lists the file scopes (read-only or read-write), the hosts (lower case, no port) and the commands
the run used, as Kotlin and Java `@PipelineDefinition` members; what was not used is "not allowed" (empty), never
"unrestricted". It is not applied for you. The Engine cannot record. Details: `docs/stable/devkit-guide.md`.

The step-by-step guide for IntelliJ IDEA is in `docs/stable/devkit-guide.md`.

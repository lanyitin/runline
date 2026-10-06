# core: Pipeline authoring contract

This module is what pipeline authors compile against. It depends only on the JDK and the Kotlin standard library
(no Ktor, no OpenTelemetry helpers, no Engine, no database), so it can be loaded inside a run's isolated class loader.

## Declaring a pipeline

A pipeline is a class that implements `Pipeline` (a plain sequential `run(context)`), has a public no-argument
constructor, and is annotated with `@PipelineDefinition`. The annotation values are the metadata. They live in the
compiled class, and `PipelineMetadataReader.read(Class)` reads them without instantiating the class or running its
static initializers.

| Metadata | Annotation member | Meaning |
|---|---|---|
| Name | `name` | Identifies the pipeline; also names its shared directory |
| Parameters | `parameters = [Param(...)]` | Required, or optional with a default; supplying undeclared or missing required ones is rejected |
| File scopes | `files = [FileAccess(scope, mode)]` | `PIPELINE_SHARED` (kept across runs) and `RUN_PRIVATE` (removed after the run), each `READ_ONLY` or `READ_WRITE`. A scope not listed is unavailable |
| Network | `network = AccessLimit(allow = [hosts])` | Allowed hosts, or `AccessLimit(unrestricted = true)`. Not provided means unrestricted |
| External processes | `processes = AccessLimit(allow = [commands])` | Allowed executables (matched against the first command element), or unrestricted. Not provided means unrestricted |
| Shared resources | `resources = [names]` | Names only; acquired by the Engine in the initialization phase, never through the context. Does not affect safe/unsafe |

Triggers are not part of the metadata; administrators bind them in the Engine.

## Using the context

`PipelineContext` exposes `parameters`, `files`, `network` and `processes`. File operations take a `FileScope` and a
path relative to that scope's root. Absolute paths, `..` escapes and symbolic links that leave the scope are denied.
Any IO outside the metadata raises `PipelineAccessDenied`, whose message names the pipeline, the IO category and
the target, and whose fields (`pipeline`, `category`, `target`, `reason`) are available programmatically.

Enforcement is cooperative: only IO performed through the context is checked. `RestrictedContext` is the
implementation that applies the metadata; the Runner constructs it with the scope directories prepared during
initialization.

## Recording mode (development entry only)

`RecordingContext` is the same context in another mode, used by the development entry to learn what a pipeline does.
The metadata's file, network and process limits are not applied: every action is allowed and noted in an `IoRecorder`.
The boundaries that are not metadata stay: only the shared and private directories, no absolute paths, no escapes
and no symbolic links out, and the disk usage limit. A refused boundary violation is recorded as rejected.

The recorder keeps the first N events one by one (category, scope and relative path, host and port, command; access is
read or write; never file content, arguments or absolute paths) and counts every event in a summary that is complete
whatever N is: one entry per category, scope, access and rejected-ness, and per host or command for the network and
processes (file paths are left out). Its `snapshot()` is JDK types only, so it can leave the run's class loader. The
Engine has no way to ask for this mode; see `docs/stable/devkit-guide.md`.

## Crossing the class loader boundary

The contract uses only JDK and Kotlin stdlib types, and a test loads it in a class loader whose parent is only the
platform class loader. The Runner must still convert metadata to JDK built-in types at the Engine boundary (see
`docs/stable/pipeline-engine/05-ipc.md`).

## Example

See `src/test/kotlin/examples/ExamplePipeline.kt` and `ExamplePipelineTest`.

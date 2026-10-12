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
| Expected resource types | `typedResources = [TypedResource(name, type, purpose = "")]` | A resource role: the name the pipeline uses together with the type it expects (`ResourceTypes.COUNTER`, `FILE`, `JDBC_POOL`, `OPENAI_COMPATIBLE`; the set is closed) and an optional short `purpose` (at most 200 characters, for people only). The name is also a declared resource name, so it is acquired like any other; a run is refused when the resource it resolves to has another type. Which resource a role gets is decided by a binding outside the pipeline, else it is the resource of the same name; the pipeline only ever sees the role (ADR-025). A name declared without a type fits any type. Does not affect safe/unsafe |
| Views | `views = [View(...)]` | Read-only, named queries over a database role, shown by the Console (ADR-033); see "Declaring views" below. Does not affect safe/unsafe, resource roles or bindings |

Triggers are not part of the metadata; administrators bind them in the Engine.

## Declaring views

A view is a read-only, named query over one of the pipeline's database roles, declared like the rest of the metadata: as
annotation values, read without running the pipeline. The Engine checks the declaration at upload and refuses the
whole jar when it is wrong (the checks and the stored form are in `docs/stable/pipeline-engine/08-api.md`, "view
宣告"). A jar with no `views` behaves exactly as before.

| Member | Meaning |
|---|---|
| `View(id, title, description = "", resource, query, parameters = [], columns, sort = [])` | `id` is unique within the pipeline. `resource` is a role declared with `TypedResource(..., ResourceTypes.JDBC_POOL)`. `query` is text in that database's dialect that refers to its parameters by name, as `:name` (see below); parameter values are passed as values, never joined into it |
| `ViewParam(name, type = TEXT, required = true, options = [])` | `type` is `ViewParamType.TEXT`, `INTEGER` or `CHOICE`; a `CHOICE` lists its fixed `options` |
| `ViewColumn(name, label, type = TEXT, tones = [], drill = [])` | `type` is `ViewColumnType.TEXT`, `INTEGER`, `TIME` or `STATUS`. For a `STATUS` column, `tones = [StatusTone(value, Tone.POSITIVE / NEUTRAL / NEGATIVE)]`; a value not listed is neutral. `drill` holds at most one `Drill` |
| `Drill(view, parameter, from = "")` | Opens another view of the same pipeline, giving it the value of column `from` of the row (empty: the column that declares the drill) as its parameter `parameter` |
| `ViewSort(column, descending = false)` | The default order, by column names of the view |

```kotlin
@PipelineDefinition(
    name = "feed",
    typedResources = [TypedResource("feed-db", ResourceTypes.JDBC_POOL)],
    views = [
        View(
            id = "articles", title = "Articles", resource = "feed-db",
            query = "SELECT title, state FROM article WHERE run = :run",
            parameters = [ViewParam("run", ViewParamType.INTEGER)],
            columns = [
                ViewColumn("title", "Title"),
                ViewColumn("state", "State", ViewColumnType.STATUS,
                    tones = [StatusTone("downloaded", Tone.POSITIVE), StatusTone("failed", Tone.NEGATIVE)]),
            ],
            sort = [ViewSort("title")],
        ),
    ],
)
```

A query refers to its parameters by name: `:run` is the value of the parameter `run`, as many times as it is written,
and every declared parameter must be referred to at least once. A `:name` is a placeholder only outside strings,
quoted identifiers, comments and `$tag$ ... $tag$` quotes, and not after another `:` or a name character, so `x::text`,
`a[1:n]` and `'10:30'` are not placeholders. A `?` (position) is refused at upload, and so is a placeholder for a
parameter that is not declared; `@name`, `$name`, `?NNN` and `$1` are not supported.

The Runner does not read `views`: a run reads the same metadata with or without them.

## Using the context

`PipelineContext` exposes `parameters`, `files`, `network` and `processes`. File operations take a `FileScope` and a
path relative to that scope's root. Absolute paths, `..` escapes and symbolic links that leave the scope are denied.
Any IO outside the metadata raises `PipelineAccessDenied`, whose message names the pipeline, the IO category and
the target, and whose fields (`pipeline`, `category`, `target`, `reason`) are available programmatically.

Enforcement is cooperative: only IO performed through the context is checked. `RestrictedContext` is the
implementation that applies the metadata; the Runner constructs it with the scope directories prepared during
initialization.

## Typed resources: accessors

A pipeline that declared a typed shared resource reaches it through `context.accessors` (ADR-019). `accessors.file(name)` returns a `FileAccessor` (`readText`, `readBytes`, `writeText`, `writeBytes`, `appendText`, `appendBytes`) for the one file behind a `file` resource: no path is exposed and no other file can be reached. It refuses, with a `ResourceAccessException` whose `failure` says why, a resource the pipeline did not declare (`NOT_DECLARED`), declared by name only (`NO_TYPE_DECLARED`), declared with another type (`TYPE_MISMATCH`) or that the host did not provide (`NOT_PROVIDED`); there is no operation to acquire a resource while a run executes (ADR-007). Operation failures carry a category (`ENDED`, `FORCE_RELEASED`, `PATH_REJECTED`, `NOT_FOUND`, `TOO_LARGE`, `FAILED`) and an errorId, never a path. The operations execute on the host's side (Engine or development entry) through `ResourceLink`, whose only call takes and returns JDK types; the host's side is the `accessors` module, which is not part of the run runtime. `accessors.jdbcPool(name)` returns a `JdbcAccessor` for a `jdbc-pool` resource (WI-48): `query(sql[, parameters])` gives `JdbcRows`, `update(sql[, parameters])` the number of rows changed, and `begin`/`commit`/`rollback` an explicit transaction. The SQL goes to the database as written; parameters and values are `null`, text, booleans, integers, floating point numbers, `BigDecimal` and bytes (anything else the database has comes back as text); the address, account, password and driver belong to the resource, and a failure carries a category (`SQL_ERROR` with the SQLState, `DENIED`, `CONNECTION_FAILED`, `TOTAL_TIMEOUT`, `QUOTA_WAIT_TIMEOUT`, `TRANSACTION_STATE`, ...) and an errorId, never the words of the database or the driver. Using accessors does not change the safe/unsafe verdict.

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

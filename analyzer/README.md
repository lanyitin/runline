# analyzer: Static analysis and safe/unsafe verdicts

Given a pipeline jar and an allow list, `SafetyAnalyzer` returns a safe or unsafe verdict for each pipeline, with every
reason and the dependency path that introduced it (ADR-002, ADR-011). The analysis reads compiled class references; it
never loads or runs pipeline code and never triggers static initializers.

The same pass reads each pipeline's declared metadata (name, parameters with required flag and default, file scopes and
modes, network and process limits, resources) from the class file, so verdict and metadata come from one source
(WI-19). It also reports core classes bundled in the jar, pipelines whose declaration cannot be read, and class files
that cannot be parsed. Defaults for members that are not written mirror those on the annotations in `core`; a test in
this module compares the result with `PipelineMetadataReader` on real compiled pipelines so the two cannot drift.

The module depends only on the JDK and the Kotlin standard library (no core, runner, Ktor, OpenTelemetry helpers,
Engine or database), so the Engine and the development entry point share the same verdict. `core` is recognised by its
package name (`dev.lawlan.runline.core`), not by type. Tests use `core` only to compile sample pipelines.

`SafetyReport.limitations` states the limits of the analysis in Traditional Chinese: reflection and dynamic loading are
not covered (also for JVM exit calls and IO sensitive members), classes on the allow list are not inspected, only the
listed IO sensitive members are covered, a subclass as receiver is not caught, and which reference forms are detected.

## The default allow list (WI-10)

`DefaultAllowList` is the one place the default allow list is defined: `entries` and a `VERSION` that names its content
(`allowList()` makes it an `AllowList`). The Engine starts with it the first time it runs (an administrator changes the
Engine's own list afterwards) and the development entry point (`devkit`) uses it unless `RUNLINE_ALLOW_LIST` is set;
no other module keeps a copy. It follows ADR-013 and ADR-014: only packages that give no file or network IO, with the
sub-packages that do kept out by listing the package "this package only" and the needed sub-packages one by one
(`java.lang`, `java.util` and `kotlin`), plus four class entries: standard output (`java.io.PrintStream`), console input (`kotlin.io.ConsoleKt`) and resource closing
(`kotlin.io.CloseableKt`, which Kotlin's `use` compiles to, and the interface `java.io.Closeable`; neither opens a file
or a connection, so what opens the resource is still judged on its own). The version is `default-2`, 44 entries (WI-25).
An Engine whose database already holds an allow list does not get the new entries by itself: the default is only the
initial content, and an administrator adds them through the allow list API. It has no `java.io`, `java.nio.file`, `java.nio.channels`, `java.net`, `java.sql` or
`java.security`. `DefaultAllowListTest` checks it on pipelines that were really compiled (Kotlin and Java): typical
Kotlin pipelines that print and read standard input are safe, direct use of files, network or processes is unsafe, the
excluded sub-packages are not trusted, and removing any entry makes at least one sample unsafe. Four entries
(`kotlin.system`, `kotlin.experimental`, `kotlin.contracts`, `kotlin.jdk7`) cannot be reached by Kotlin source (their
members are inline-only and are not referenced by the compiled code), so they are shown needed only by a Java sample that
names a class of that package. Changing the content needs a new `VERSION`.

`AllowListEntry.covers(other)` and `coversClass(className)` are the matching rules the analyzer itself uses, public so
that the Engine can tell a duplicate or an entry that makes another unnecessary without a second copy of the rules.

## Allow list entries (ADR-014, WI-24)

An `AllowList` has a version and entries of two explicit kinds (`AllowListEntry`, a sealed interface), never told apart
by guessing at a name:

- `PackageEntry(packageName, exactOnly)`: the package and its sub-packages, or only the package with `exactOnly`. The
  function `AllowListEntry(name, exactOnly)` still builds one, so lists written before class entries keep working and
  are judged exactly as before.
- `ClassEntry(className)`: that class and its nested classes (`Outer$Inner`, at any depth) only. Other classes of its
  package, a class whose name merely starts with the entry, and sub-packages are not trusted. A class must have a
  package (`java.io.PrintStream`, not `PrintStream`).

Names are validated when an entry is built (dot-separated Java identifiers, no blanks, wildcards or empty segments); an
invalid one throws `IllegalArgumentException` naming the entry. A trusted class is not expanded, but trusting a class
does not excuse its IO sensitive members: those are matched on the member and still make a pipeline unsafe (for
example `new PrintStream("out.txt")` with `java.io.PrintStream` allowed). `ClassEntry.TOKEN_PREFIX` (`class:`) is the
marker for a class entry in the text form.

## The text form (WI-25)

`AllowListText` is the one place an allow list is written to and read from text, used by the Engine's `ALLOWLIST_PACKAGES`
and the development entry point's `RUNLINE_ALLOW_LIST`, so both accept and reject the same text. Items are separated by
commas (blanks around an item and empty items are ignored): `package`, `package:exact` ("this package only") or
`class:full.Name`. `AllowListText.format(entries)` writes it and `parse(text)` reads it back as the same list.
`parse` returns an `AllowListParse`: the valid `entries` and a `problems` list with one `AllowListTextProblem(entry,
reason)` per rejected item, so the caller words its own error (naming its variable). The earlier trailing `!` form is
rejected with a reason that says to write `:exact`.

## IO sensitive members (ADR-013, WI-21)

A reference to one of the members below makes the pipeline unsafe with `UnsafeReason.IoSensitiveMember` (kind
`IO_SENSITIVE_MEMBER` in the Engine API). It is matched on the member itself, next to the JVM exit members, while the
class reference tree is expanded, so it holds even when `java.lang`, `java.util` or `kotlin` are on the allow list and
it also covers third-party classes bundled in the jar. The reason carries the member as owner, name and JVM descriptor
(for example `java.lang.Runtime.exec(Ljava/lang/String;)Ljava/lang/Process;`) and the full path from the pipeline class.

A rule is `owner + name`, optionally with an exact set of descriptors (`MemberRule`, taken from the NameAndType of the
constant pool). Without descriptors every overload matches; with descriptors only the listed overloads do. The list
was evaluated against JDK 25.0.4.1 and Kotlin standard library 2.4.0; `IoSensitiveMembersJdkTest` checks it against
the JDK that runs the tests.

| Category | Members | Match |
|---|---|---|
| Starts an external process | `ProcessBuilder.start()`, `ProcessBuilder.startPipeline(List)`, `Runtime.exec` (all 6 overloads) | by name |
| Loads native code | `System.load(String)`, `System.loadLibrary(String)`, `Runtime.load(String)`, `Runtime.loadLibrary(String)`, `foreign.Linker.nativeLinker()`, `foreign.SymbolLookup.libraryLookup` (String and Path overloads) | by name |
| Opens files or network, inside a package that is normally trusted | `java.util.Formatter` constructors whose first parameter is a `String` (file name) or `File` (8 overloads) | by descriptor |
| | `java.util.Scanner` constructors whose first parameter is a `File` or `Path` (6 overloads) | by descriptor |
| | `java.io.PrintStream` constructors whose first parameter is a `String` (file name) or `File` (6 overloads) | by descriptor |
| | `java.util.zip.ZipFile`, `java.util.jar.JarFile`, `java.util.logging.FileHandler`, `java.util.logging.SocketHandler` constructors | by name (all overloads) |

Memory-only overloads stay safe: `Formatter()`, `Formatter(Appendable)`, `Formatter(Locale)`, `Formatter(OutputStream
...)`, `Scanner(String)`, `Scanner(Readable)`, `Scanner(InputStream ...)`, `PrintStream(OutputStream ...)`. The
`PrintStream` file-opening constructors are listed so that a later class-level allow list entry for
`java.io.PrintStream` (to let `println` work) would still stop code that opens a file with it. The Kotlin standard
library has no member that needs listing: `kotlin.io` and `kotlin.io.path` are caught as classes outside the allow list,
and `exitProcess` is handled as a JVM exit member.

Considered and not listed:

- `System.out`, `System.err`, `System.in`, `PrintStream.println`: standard output and input are attributed to the run
  by the Runner.
- `System.getenv`, `System.getProperty`, `System.setProperty`: not one of the three categories.
- `Class.getResource*`, `ClassLoader.getResource*`: read the classpath only, and their signatures are already caught as
  classes outside the allow list.
- `Class.forName`, `ClassLoader.loadClass`, `Lookup.defineClass`, `ServiceLoader`: dynamic loading, which ADR-013 does
  not cover.
- `ProcessHandle.destroy` and `ProcessHandle.of`: act on an existing process, they do not start one.
- `ResourceBundle.getBundle`, `Properties.load`, `Base64.wrap` and zip stream wrappers: only wrap a stream the caller
  supplies.
- `ToolProvider` (signature has `PrintStream`, rare), `ModuleFinder.of` and `ClassFile.parse` (signature has `Path`),
  `HotSpotDiagnosticMXBean.dumpHeap` (obscure, in a package excluded by default), `java.util.prefs.Preferences`
  (indirect IO).
- `ProcessBuilder` construction: it only builds, and listing it would report the same code twice.
- `Scanner` and `Formatter` as a whole by name: it would flag the in-memory overloads.

Limits: reflection and dynamic loading are not covered; calls made inside classes on the allow list are not inspected;
only the listed members are covered; and a reference whose owner in the constant pool is a subclass (a call on a
subclass-typed receiver) is not matched, which is the same limit as for the JVM exit members. Detected forms are direct
calls and constructions, method handles and method references (a Java method reference, or a Kotlin callable reference,
which compiles to a synthetic class whose member reference is expanded), and calls the Kotlin compiler generates
(inline functions, delegates, lambdas).

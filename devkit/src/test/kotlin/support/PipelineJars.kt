package dev.lawlan.runline.devkit.support

import dev.lawlan.runline.core.Pipeline
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Compiles real Java sources against `core` with the JDK compiler and packs them into a jar, the
 * same artifact form a pipeline author publishes.
 */
object PipelineJars {
  private val compileClasspath: String =
      listOf(Pipeline::class.java, Unit::class.java)
          .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
          .joinToString(java.io.File.pathSeparator)

  /** [sources] maps a simple class name (default package) to its Java source. */
  fun build(dir: Path, jarName: String, sources: Map<String, String>): Path {
    val work = Files.createTempDirectory(dir, "jar-build-")
    val src = work.resolve("src").createDirectories()
    val out = work.resolve("classes").createDirectories()
    val files = sources.map { (name, code) ->
      src.resolve("$name.java").also { it.writeText(code) }
    }
    val compiler = ToolProvider.getSystemJavaCompiler() ?: error("A JDK is required to run tests")
    val diagnostics = java.io.StringWriter()
    val ok =
        compiler
            .getTask(
                diagnostics,
                null,
                null,
                listOf("-proc:none", "-classpath", compileClasspath, "-d", out.toString()),
                null,
                compiler
                    .getStandardFileManager(null, null, null)
                    .getJavaFileObjectsFromPaths(files),
            )
            .call()
    check(ok) { "Compilation failed:\n$diagnostics" }

    val jar = dir.resolve(jarName)
    JarOutputStream(Files.newOutputStream(jar)).use { jos ->
      Files.walk(out).use { paths ->
        paths
            .filter { Files.isRegularFile(it) }
            .sorted()
            .forEach {
              jos.putNextEntry(JarEntry(out.relativize(it).toString().replace('\\', '/')))
              Files.copy(it, jos)
              jos.closeEntry()
            }
      }
    }
    return jar
  }

  /**
   * Source of a pipeline class [className] in the default package. Both file scopes are writable
   * and network and processes are restricted to nothing, so by itself it is safe. [body] may throw
   * checked exceptions; [members] adds further class members.
   */
  fun pipeline(
      className: String,
      name: String,
      body: String,
      members: String = "",
      extraAnnotation: String = "",
  ): String =
      """
      import dev.lawlan.runline.core.*;

      @PipelineDefinition(
          name = "$name",
          files = {
              @FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE),
              @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)
          },
          network = @AccessLimit(allow = {}),
          processes = @AccessLimit(allow = {})$extraAnnotation)
      public class $className implements Pipeline {
        $members
        private void body(PipelineContext context) throws Exception { $body }

        @Override
        public void run(PipelineContext context) {
          try {
            body(context);
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        }
      }
      """
          .trimIndent()
}

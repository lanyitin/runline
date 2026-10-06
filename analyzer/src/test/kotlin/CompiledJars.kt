package dev.lawlan.runline.analyzer

import dev.lawlan.runline.core.Pipeline
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Compiles real Java sources against `core` with the JDK compiler and packs the output into a jar,
 * the same artifact form a pipeline author publishes.
 */
object CompiledJars {
  private val compileClasspath: String =
      listOf(Pipeline::class.java, Unit::class.java)
          .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
          .joinToString(java.io.File.pathSeparator)

  /** [sources] maps a fully qualified class name to its Java source. */
  fun build(
      dir: Path,
      jarName: String,
      sources: Map<String, String>,
      classOverrides: Map<String, (ByteArray) -> ByteArray> = emptyMap(),
      extraEntries: Map<String, ByteArray> = emptyMap(),
  ): Path {
    val work = Files.createTempDirectory(dir, "jar-build-")
    val src = work.resolve("src").createDirectories()
    val out = work.resolve("classes").createDirectories()
    val files = sources.map { (name, code) ->
      val file = src.resolve(name.replace('.', '/') + ".java")
      file.parent.createDirectories()
      file.also { it.writeText(code) }
    }
    val compiler = ToolProvider.getSystemJavaCompiler() ?: error("A JDK is required to run tests")
    val diagnostics = java.io.StringWriter()
    val ok =
        files.isEmpty() ||
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
    classOverrides.forEach { (name, transform) ->
      val file = out.resolve(name.replace('.', '/') + ".class")
      Files.write(file, transform(Files.readAllBytes(file)))
    }
    return pack(dir.resolve(jarName), out, extraEntries)
  }

  /** Packs the compiled classes under [classes] plus raw [extraEntries] into a jar. */
  fun pack(jar: Path, classes: Path, extraEntries: Map<String, ByteArray>): Path {
    JarOutputStream(Files.newOutputStream(jar)).use { jos ->
      Files.walk(classes).use { paths ->
        paths
            .filter { Files.isRegularFile(it) }
            .sorted()
            .forEach {
              jos.putNextEntry(JarEntry(classes.relativize(it).toString().replace('\\', '/')))
              Files.copy(it, jos)
              jos.closeEntry()
            }
      }
      extraEntries.forEach { (name, bytes) ->
        jos.putNextEntry(JarEntry(name))
        jos.write(bytes)
        jos.closeEntry()
      }
    }
    return jar
  }

  /** A pipeline class with the given annotation members (network/processes limits) and body. */
  fun pipeline(
      fqcn: String,
      limits: String = "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {})",
      members: String = "",
      body: String = "",
  ): String {
    val pkg = fqcn.substringBeforeLast('.', "")
    val simple = fqcn.substringAfterLast('.')
    val header = if (pkg.isEmpty()) "" else "package $pkg;"
    return """
        $header
        import dev.lawlan.runline.core.*;

        @PipelineDefinition(name = "$simple-pipeline"${if (limits.isBlank()) "" else ", $limits"})
        public class $simple implements Pipeline {
          $members
          @Override
          public void run(PipelineContext context) throws RuntimeException { $body }
        }
        """
        .trimIndent()
  }
}

/** Jars made from classes the Kotlin compiler already produced for this test source set. */
object KotlinFixtures {
  /** Packs the compiled class files of [classes] (read as bytes, never loaded) into a jar. */
  fun jarOf(dir: Path, jarName: String, vararg classes: Class<*>): Path {
    val out = Files.createTempDirectory(dir, "kotlin-jar-")
    classes.forEach {
      // The class itself plus the synthetic and nested classes the Kotlin compiler generates for
      // it.
      val simple = it.name.substringAfterLast('.')
      val directory = it.name.substringBeforeLast('.', "").replace('.', '/')
      val source = Path.of(it.getResource("$simple.class")!!.toURI()).parent
      Files.list(source).use { siblings ->
        siblings
            .filter { f ->
              f.fileName.toString().let { n ->
                n == "$simple.class" || (n.startsWith("$simple$") && n.endsWith(".class"))
              }
            }
            .forEach { f ->
              val target = out.resolve(directory).resolve(f.fileName.toString())
              target.parent.createDirectories()
              Files.copy(f, target)
            }
      }
    }
    return CompiledJars.pack(dir.resolve(jarName), out, emptyMap())
  }
}

package dev.lawlan.runline.engine.support

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

  /**
   * [sources] maps a fully qualified class name to its Java source; [extraEntries] adds raw jar
   * entries (name to bytes) next to the compiled classes.
   */
  fun build(
      dir: Path,
      jarName: String,
      sources: Map<String, String>,
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
    return pack(dir.resolve(jarName), out, extraEntries)
  }

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

  /**
   * Copies [jar] and adds an entry [entryName] of [size] zero bytes, written as a stream so that
   * the data never exists in memory at once. Deflate packs it to about a thousandth of its size.
   */
  fun withZeroEntry(jar: Path, target: Path, entryName: String, size: Long): Path {
    java.util.zip.ZipFile(jar.toFile()).use { zip ->
      JarOutputStream(Files.newOutputStream(target)).use { out ->
        for (entry in zip.entries()) {
          out.putNextEntry(JarEntry(entry.name))
          zip.getInputStream(entry).use { it.copyTo(out) }
          out.closeEntry()
        }
        out.putNextEntry(JarEntry(entryName))
        val chunk = ByteArray(1 shl 20)
        var written = 0L
        while (written < size) {
          val n = minOf(chunk.size.toLong(), size - written).toInt()
          out.write(chunk, 0, n)
          written += n
        }
        out.closeEntry()
      }
    }
    return target
  }

  /**
   * A pipeline class [fqcn] named [name]. Without further arguments network and processes are
   * restricted to nothing, so the pipeline is safe on its own; [definition] replaces the annotation
   * members after `name`.
   */
  fun pipeline(
      fqcn: String,
      name: String,
      definition: String =
          "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {})",
      members: String = "",
      body: String = "",
  ): String {
    val pkg = fqcn.substringBeforeLast('.', "")
    val simple = fqcn.substringAfterLast('.')
    val header = if (pkg.isEmpty()) "" else "package $pkg;"
    val extra = if (definition.isBlank()) "" else ", $definition"
    return """
        $header
        import dev.lawlan.runline.core.*;

        @PipelineDefinition(name = "$name"$extra)
        public class $simple implements Pipeline {
          $members
          @Override
          public void run(PipelineContext context) throws RuntimeException { $body }
        }
        """
        .trimIndent()
  }

  /** The compiled bytes of a real class of core, as found on the test class path. */
  fun coreClassBytes(simpleName: String): ByteArray =
      Pipeline::class
          .java
          .classLoader
          .getResourceAsStream("dev/lawlan/runline/core/$simpleName.class")!!
          .use { it.readAllBytes() }

  /**
   * A jar of the class files the Kotlin compiler produced for [classes] in this test source set.
   */
  fun jarOf(dir: Path, jarName: String, vararg classes: Class<*>): Path {
    val out = Files.createTempDirectory(dir, "kotlin-jar-")
    classes.forEach {
      val resource = it.name.replace('.', '/') + ".class"
      val target = out.resolve(resource)
      target.parent.createDirectories()
      Files.write(
          target,
          it.classLoader.getResourceAsStream(resource)!!.use { s -> s.readAllBytes() },
      )
    }
    return pack(dir.resolve(jarName), out, emptyMap())
  }
}

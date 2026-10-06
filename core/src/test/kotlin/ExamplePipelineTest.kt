package dev.lawlan.runline.core

import dev.lawlan.runline.core.examples.ExamplePipeline
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class ExamplePipelineTest {
  @TempDir lateinit var tmp: Path

  @Test
  fun `example pipeline runs against a restricted context`() {
    val shared = tmp.resolve("shared").createDirectories()
    val run = tmp.resolve("run").createDirectories()
    val meta = PipelineMetadataReader.read(ExamplePipeline::class.java)

    ExamplePipeline().run(RestrictedContext(meta, mapOf("target" to "world"), shared, run))

    assertEquals("hello world\n", run.resolve("log.txt").readText())
    assertEquals("1", shared.resolve("count").readText())
  }

  @Test
  fun `contract works from an isolated class loader whose parent is only the platform loader`() {
    val urls =
        System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .map { Path.of(it).toUri().toURL() }
            .toTypedArray()
    URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
      val reader = loader.loadClass(PipelineMetadataReader::class.java.name)
      val pipeline = loader.loadClass(ExamplePipeline::class.java.name)
      val metadata =
          reader.getField("INSTANCE").get(null).let {
            reader.getMethod("read", Class::class.java).invoke(it, pipeline)
          }
      assertNotEquals<ClassLoader>(
          PipelineMetadata::class.java.classLoader,
          metadata.javaClass.classLoader,
      )
      assertEquals("example-report", metadata.javaClass.getMethod("getName").invoke(metadata))
    }
  }
}

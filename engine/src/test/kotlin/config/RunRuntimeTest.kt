package dev.lawlan.runline.engine.config

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.runner.isolated.RunEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RunRuntimeTest {
  private val dir: Path = TestDirectories.forThisTest("run-runtime")

  /** The jar a class of the Runner, core or the Kotlin library was loaded from. */
  private fun jarOf(type: Class<*>): Path =
      Path.of(type.protectionDomain.codeSource.location.toURI()).also {
        require(Files.isRegularFile(it)) { "$type is not loaded from a jar: $it" }
      }

  private fun stage(vararg types: Class<*>): Path {
    val target = Files.createTempDirectory(dir, "runtime")
    types.forEach { Files.copy(jarOf(it), target.resolve(jarOf(it).fileName)) }
    return target
  }

  @Test
  fun `a directory with the Runner, core and the Kotlin library yields their jars`() {
    val runtime =
        RunRuntime.fromDirectory(
            stage(RunEntry::class.java, Pipeline::class.java, Unit::class.java)
        )

    val names = runtime.classpath.map { Path.of(it.toURI()).fileName.toString() }
    assertEquals(
        setOf(jarOf(RunEntry::class.java), jarOf(Pipeline::class.java), jarOf(Unit::class.java))
            .map { it.fileName.toString() }
            .toSet(),
        names.toSet(),
    )
  }

  @Test
  fun `files that are not jars are ignored`() {
    val target = stage(RunEntry::class.java, Pipeline::class.java, Unit::class.java)
    Files.writeString(target.resolve("README.txt"), "not a jar")

    assertEquals(3, RunRuntime.fromDirectory(target).classpath.size)
  }

  @Test
  fun `a directory that does not exist is refused naming it`() {
    val e =
        assertFailsWith<ConfigurationException> { RunRuntime.fromDirectory(dir.resolve("absent")) }

    assertTrue(e.message!!.contains("absent"), e.message)
  }

  @Test
  fun `a directory missing the Runner, core or the Kotlin library is refused saying which`() {
    val all = listOf(RunEntry::class.java, Pipeline::class.java, Unit::class.java)
    val labels = listOf("Runner", "core", "Kotlin")
    for ((index, label) in labels.withIndex()) {
      val e =
          assertFailsWith<ConfigurationException>(label) {
            RunRuntime.fromDirectory(
                stage(*all.filterIndexed { i, _ -> i != index }.toTypedArray())
            )
          }
      assertTrue(e.message!!.contains(label), "$label missing from: ${e.message}")
    }
  }

  @Test
  fun `a directory holding Engine classes is refused because a run would then see the Engine`() {
    val target = stage(RunEntry::class.java, Pipeline::class.java, Unit::class.java)
    PipelineJars.jarOf(target, "engine-part.jar", RunRuntime::class.java)

    val e = assertFailsWith<ConfigurationException> { RunRuntime.fromDirectory(target) }

    assertTrue(e.message!!.contains("Engine"), e.message)
  }
}

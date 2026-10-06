package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.migratedDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class PostgresDefinitionStoreTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val dir: Path = Files.createTempDirectory("definition-store")
  private val pipelines = StoredPipelines(PostgresArtifactStore(dataSource), dir)
  private val store = PostgresDefinitionStore(dataSource)

  @Test
  fun `finds a definition by version and pipeline name with what a run needs`() {
    val hash = pipelines.save("v1", "nightly", uploader = "alice", verdict = Verdict.UNSAFE)

    val found = store.find(hash, "nightly")!!

    assertEquals(hash, found.contentHash)
    assertEquals("alice", found.uploadedBy)
    assertEquals("nightly", found.name)
    assertEquals("p.nightly", found.className)
    assertEquals(Verdict.UNSAFE, found.verdict)
    assertEquals(pipelines.metadata, found.metadata)
    assertTrue(found.id > 0)
  }

  @Test
  fun `a new definition does not allow unsafe execution and has no setter`() {
    val hash = pipelines.save("v1", "nightly")

    val found = store.find(hash, "nightly")!!

    assertFalse(found.allowUnsafeExecution)
    assertNull(found.unsafeSettingSetBy)
    assertNull(found.unsafeSettingSetAt)
  }

  @Test
  fun `an unknown version or pipeline name finds nothing`() {
    val hash = pipelines.save("v1", "nightly")

    assertNull(store.find(hash, "other"))
    assertNull(store.find("f".repeat(64), "nightly"))
  }

  @Test
  fun `the same pipeline name in another version is a separate definition`() {
    val v1 = pipelines.save("v1", "nightly")
    val v2 = pipelines.save("v2", "nightly")

    assertNotEquals(store.find(v1, "nightly")!!.id, store.find(v2, "nightly")!!.id)
  }

  @Test
  fun `setting unsafe execution records the decision, who made it and when`() {
    val hash = pipelines.save("v1", "nightly", verdict = Verdict.UNSAFE)
    val at = Instant.now().truncatedTo(ChronoUnit.MICROS)

    val updated = store.setUnsafeExecution(hash, "nightly", true, "root", at)!!

    assertTrue(updated.allowUnsafeExecution)
    assertEquals("root", updated.unsafeSettingSetBy)
    assertEquals(at, updated.unsafeSettingSetAt)
    assertEquals(updated, store.find(hash, "nightly"))
  }

  @Test
  fun `a later change replaces the earlier decision and its author`() {
    val hash = pipelines.save("v1", "nightly", verdict = Verdict.UNSAFE)
    val first = Instant.now().truncatedTo(ChronoUnit.MICROS)
    store.setUnsafeExecution(hash, "nightly", true, "root", first)

    val later = first.plusSeconds(60)
    val updated = store.setUnsafeExecution(hash, "nightly", false, "ops", later)!!

    assertFalse(updated.allowUnsafeExecution)
    assertEquals("ops", updated.unsafeSettingSetBy)
    assertEquals(later, updated.unsafeSettingSetAt)
  }

  @Test
  fun `the setting belongs to one definition and is not shared with other versions`() {
    val v1 = pipelines.save("v1", "nightly", verdict = Verdict.UNSAFE)
    val v2 = pipelines.save("v2", "nightly", verdict = Verdict.UNSAFE)

    store.setUnsafeExecution(v1, "nightly", true, "root", Instant.now())

    assertFalse(store.find(v2, "nightly")!!.allowUnsafeExecution)
  }

  @Test
  fun `setting an unknown definition changes nothing and says so`() {
    pipelines.save("v1", "nightly")

    assertNull(store.setUnsafeExecution("f".repeat(64), "nightly", true, "root", Instant.now()))
  }

  @Test
  fun `copies the stored jar byte for byte`() {
    val content = ByteArray(5000) { (it * 7).toByte() }
    val hash = pipelines.save("v1", "nightly", content = content)
    val target = dir.resolve("copy.jar")

    assertTrue(store.copyContent(hash, target))

    assertContentEquals(content, Files.readAllBytes(target))
  }

  @Test
  fun `copying an unknown version writes nothing`() {
    val target = dir.resolve("none.jar")

    assertFalse(store.copyContent("f".repeat(64), target))
    assertFalse(Files.exists(target))
  }
}

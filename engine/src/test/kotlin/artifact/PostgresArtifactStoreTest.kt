package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.db.DatabaseMigrator
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.getWithin
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class PostgresArtifactStoreTest {
  private val database = PostgresTestContainer.newDatabase().also { DatabaseMigrator(it).migrate() }
  private val dataSource = dataSourceOf(database)
  private val store = PostgresArtifactStore(dataSource)
  private val dir: Path = Files.createTempDirectory("store-test")

  private val metadata =
      MetadataDoc(
          parameters = listOf(ParameterDoc("env", required = false, default = "dev")),
          files = listOf(FileAccessDoc("RUN_PRIVATE", "READ_WRITE")),
          network = AccessLimitDoc(unrestricted = false, allow = listOf("example.com")),
          processes = AccessLimitDoc(unrestricted = true),
          resources = listOf("db-lock"),
      )

  private fun definition(name: String, className: String = "p.$name") =
      NewDefinition(
          className,
          name,
          metadata,
          Verdict.UNSAFE,
          listOf(
              ReasonDoc(
                  ReasonKind.NOT_ALLOW_LISTED,
                  className = "java.io.File",
                  path = listOf(className, "java.io.File"),
              ),
              ReasonDoc(ReasonKind.UNRESTRICTED_ACCESS, category = "PROCESSES"),
          ),
          "config",
      )

  private fun artifact(
      hash: String,
      uploader: String = "alice",
      content: ByteArray = "jar-$hash".toByteArray(),
      vararg definitions: NewDefinition = arrayOf(definition("one")),
  ): NewArtifact {
    val file = Files.createTempFile(dir, "a", ".jar").also { Files.write(it, content) }
    return NewArtifact(
        sha256Hex(hash),
        file,
        content.size.toLong(),
        uploader,
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        definitions.toList(),
    )
  }

  private fun sha256Hex(label: String) =
      java.security.MessageDigest.getInstance("SHA-256").digest(label.toByteArray()).joinToString(
          ""
      ) {
        "%02x".format(it)
      }

  private fun scalar(sql: String): Long =
      dataSource.connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery(sql).use {
            it.next()
            it.getLong(1)
          }
        }
      }

  @Test
  fun `metadata stored before resource types existed is read with no declared types`() {
    val hash = (store.saveIfAbsent(artifact("legacy")) as SaveResult.Created).artifact.contentHash
    dataSource.connection.use { c ->
      c.createStatement().use {
        it.executeUpdate(
            "UPDATE pipeline_definition SET metadata = '{\"parameters\":[],\"files\":[]," +
                "\"network\":{\"unrestricted\":true,\"allow\":[]}," +
                "\"processes\":{\"unrestricted\":true,\"allow\":[]}," +
                "\"resources\":[\"db-lock\"]}'::jsonb"
        )
      }
    }

    val read = store.findByHash(hash)!!.definitions.single().metadata
    val forRun = PostgresDefinitionStore(dataSource).find(hash, "one")!!.metadata

    assertEquals(listOf("db-lock"), read.resources)
    assertEquals(emptyMap(), read.resourceTypes)
    assertEquals(read, forRun)
  }

  @Test
  fun `saves an artifact with its definitions and reads everything back`() {
    val new = artifact("a1", definitions = arrayOf(definition("one"), definition("two")))

    val result = store.saveIfAbsent(new)

    val created = assertIs<SaveResult.Created>(result).artifact
    assertEquals(new.contentHash, created.contentHash)
    assertEquals("alice", created.uploadedBy)
    assertEquals(new.uploadedAt, created.uploadedAt)
    assertEquals(new.sizeBytes, created.sizeBytes)
    assertEquals(listOf("one", "two"), created.definitions.map { it.name }.sorted())
    val one = created.definitions.first { it.name == "one" }
    assertEquals(metadata, one.metadata)
    assertEquals(Verdict.UNSAFE, one.verdict)
    assertEquals(definition("one").reasons, one.reasons)
    assertEquals("config", one.allowListVersion)
    assertEquals("p.one", one.className)
    assertEquals(created, store.findByHash(new.contentHash))
  }

  @Test
  fun `stores the jar bytes unchanged`() {
    val bytes = ByteArray(300_000) { (it % 251).toByte() }
    val new = artifact("bytes", content = bytes)
    store.saveIfAbsent(new)

    val stored =
        dataSource.connection.use { c ->
          c.prepareStatement("SELECT content FROM pipeline_artifact WHERE content_hash = ?").use {
            it.setString(1, new.contentHash)
            it.executeQuery().use { rs ->
              rs.next()
              rs.getBytes(1)
            }
          }
        }
    assertContentEquals(bytes, stored)
  }

  @Test
  fun `a new definition is never allowed to run unsafe`() {
    val created = assertIs<SaveResult.Created>(store.saveIfAbsent(artifact("safe1"))).artifact

    val d = created.definitions.single()
    assertFalse(d.allowUnsafeExecution)
    assertNull(d.unsafeSettingSetBy)
  }

  @Test
  fun `the same content is stored once and the existing record is returned`() {
    val first = artifact("dup", uploader = "alice")
    val created = assertIs<SaveResult.Created>(store.saveIfAbsent(first)).artifact

    val again =
        store.saveIfAbsent(
            artifact("dup", uploader = "bob", definitions = arrayOf(definition("other")))
        )

    assertEquals(created, assertIs<SaveResult.AlreadyExists>(again).artifact)
    assertEquals(1, scalar("SELECT count(*) FROM pipeline_artifact"))
    assertEquals(1, scalar("SELECT count(*) FROM pipeline_definition"))
  }

  @Test
  fun `concurrent uploads of the same content create exactly one record`() {
    val threads = 8
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    val results =
        (1..threads).map {
          pool.submit<SaveResult> {
            val new =
                artifact(
                    "race",
                    uploader = "u$it",
                    definitions = arrayOf(definition("one"), definition("two")),
                )
            start.await()
            store.saveIfAbsent(new)
          }
        }
    start.countDown()
    val outcomes = results.map { it.getWithin("an upload of the same content") }
    pool.shutdown()

    assertEquals(1, outcomes.count { it is SaveResult.Created })
    assertEquals(threads - 1, outcomes.count { it is SaveResult.AlreadyExists })
    assertEquals(1, scalar("SELECT count(*) FROM pipeline_artifact"))
    assertEquals(2, scalar("SELECT count(*) FROM pipeline_definition"))
  }

  @Test
  fun `a failure while writing definitions leaves nothing behind`() {
    val broken =
        artifact(
            "broken",
            definitions = arrayOf(definition("same", "p.A"), definition("same", "p.B")),
        )

    assertFails { store.saveIfAbsent(broken) }

    assertEquals(0, scalar("SELECT count(*) FROM pipeline_artifact"))
    assertEquals(0, scalar("SELECT count(*) FROM pipeline_definition"))
    assertNull(store.findByHash(broken.contentHash))
  }

  @Test
  fun `the same pipeline name coexists across artifact versions`() {
    store.saveIfAbsent(artifact("v1", definitions = arrayOf(definition("nightly"))))
    store.saveIfAbsent(artifact("v2", definitions = arrayOf(definition("nightly"))))

    assertEquals(2, scalar("SELECT count(*) FROM pipeline_definition WHERE name = 'nightly'"))
  }

  @Test
  fun `list is limited by visibility`() {
    store.saveIfAbsent(artifact("a", uploader = "alice"))
    store.saveIfAbsent(artifact("b", uploader = "bob"))
    store.saveIfAbsent(artifact("c", uploader = "alice"))

    assertEquals(3, store.list(Visibility.All).size)
    val own = store.list(Visibility.OwnedBy("alice"))
    assertEquals(2, own.size)
    assertTrue(own.all { it.uploadedBy == "alice" })
    assertEquals(emptyList(), store.list(Visibility.OwnedBy("carol")))
  }

  @Test
  fun `unknown hash is not found`() {
    assertNull(store.findByHash("f".repeat(64)))
  }

  @Test
  fun `delete removes the artifact and its definitions`() {
    val new = artifact("del")
    store.saveIfAbsent(new)

    assertEquals(DeleteResult.Deleted, store.delete(new.contentHash))

    assertNull(store.findByHash(new.contentHash))
    assertEquals(0, scalar("SELECT count(*) FROM pipeline_definition"))
    assertEquals(DeleteResult.NotFound, store.delete(new.contentHash))
  }

  @Test
  fun `an artifact whose definition is referenced cannot be deleted`() {
    val new = artifact("used")
    store.saveIfAbsent(new)
    // A later work item's table (trigger, run) references a definition with ON DELETE RESTRICT.
    dataSource.connection.use { c ->
      c.createStatement().use {
        it.execute(
            "CREATE TABLE test_reference (definition_id BIGINT NOT NULL " +
                "REFERENCES pipeline_definition (id) ON DELETE RESTRICT)"
        )
        it.execute("INSERT INTO test_reference SELECT id FROM pipeline_definition")
      }
    }

    assertEquals(DeleteResult.InUse, store.delete(new.contentHash))

    assertNotNull(store.findByHash(new.contentHash))
    assertEquals(1, scalar("SELECT count(*) FROM pipeline_definition"))
  }
}

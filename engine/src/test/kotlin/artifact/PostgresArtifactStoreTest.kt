package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.accessors.support.TestDirectories
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
  private val dir: Path = TestDirectories.forThisTest("store-test")

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

    val read = store.find(hash, "alice")!!.definitions.single().metadata
    val forRun = PostgresDefinitionStore(dataSource).find(hash, "alice", "one")!!.metadata

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
    assertEquals(created, store.find(new.contentHash, "alice"))
  }

  @Test
  fun `stores the jar bytes unchanged`() {
    val bytes = ByteArray(300_000) { (it % 251).toByte() }
    val new = artifact("bytes", content = bytes)
    store.saveIfAbsent(new)

    val stored =
        dataSource.connection.use { c ->
          c.prepareStatement("SELECT content FROM artifact_content WHERE content_hash = ?").use {
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
  fun `the same uploader storing the same content again gets their version back and nothing is written`() {
    val created = assertIs<SaveResult.Created>(store.saveIfAbsent(artifact("dup"))).artifact

    val again = store.saveIfAbsent(artifact("dup", definitions = arrayOf(definition("other"))))

    assertEquals(created, assertIs<SaveResult.AlreadyExists>(again).artifact)
    assertEquals(1, scalar("SELECT count(*) FROM pipeline_artifact"))
    assertEquals(1, scalar("SELECT count(*) FROM pipeline_definition"))
  }

  @Test
  fun `another uploader of the same content gets a version of their own and the jar is kept once`() {
    val alice = assertIs<SaveResult.Created>(store.saveIfAbsent(artifact("dup"))).artifact

    val bob =
        assertIs<SaveResult.Created>(
                store.saveIfAbsent(
                    artifact("dup", uploader = "bob", definitions = arrayOf(definition("other")))
                )
            )
            .artifact

    assertEquals(alice.contentHash, bob.contentHash)
    assertEquals("bob", bob.uploadedBy)
    assertEquals(listOf("other"), bob.definitions.map { it.name })
    assertEquals(
        listOf("one"),
        store.find(alice.contentHash, "alice")!!.definitions.map { it.name },
    )
    assertEquals(listOf("alice", "bob"), store.uploadersOf(alice.contentHash))
    assertEquals(2, scalar("SELECT count(*) FROM pipeline_artifact"))
    assertEquals(1, scalar("SELECT count(*) FROM artifact_content"))
  }

  @Test
  fun `concurrent uploads of the same content by different uploaders each create their version`() {
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

    assertEquals(threads, outcomes.count { it is SaveResult.Created })
    assertEquals(threads, scalar("SELECT count(*) FROM pipeline_artifact").toInt())
    assertEquals(1, scalar("SELECT count(*) FROM artifact_content"))
    assertEquals(2L * threads, scalar("SELECT count(*) FROM pipeline_definition"))
  }

  @Test
  fun `concurrent uploads of the same content by one uploader create exactly one version`() {
    val threads = 8
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    val results =
        (1..threads).map {
          pool.submit<SaveResult> {
            val new = artifact("race", definitions = arrayOf(definition("one"), definition("two")))
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
    assertNull(store.find(broken.contentHash, "alice"))
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
    assertNull(store.find("f".repeat(64), "alice"))
    assertEquals(emptyList(), store.uploadersOf("f".repeat(64)))
  }

  @Test
  fun `delete removes the artifact and its definitions`() {
    val new = artifact("del")
    store.saveIfAbsent(new)

    assertEquals(DeleteResult.Deleted, store.delete(new.contentHash, "alice"))

    assertNull(store.find(new.contentHash, "alice"))
    assertEquals(0, scalar("SELECT count(*) FROM pipeline_definition"))
    assertEquals(DeleteResult.NotFound, store.delete(new.contentHash, "alice"))
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

    assertEquals(DeleteResult.InUse, store.delete(new.contentHash, "alice"))

    assertNotNull(store.find(new.contentHash, "alice"))
    assertEquals(1, scalar("SELECT count(*) FROM pipeline_definition"))
  }

  @Test
  fun `deleting one uploader's version keeps the jar and the other version, and the last takes the jar`() {
    val alice = artifact("shared", uploader = "alice")
    store.saveIfAbsent(alice)
    store.saveIfAbsent(artifact("shared", uploader = "bob"))

    assertEquals(DeleteResult.Deleted, store.delete(alice.contentHash, "alice"))

    assertNull(store.find(alice.contentHash, "alice"))
    assertNotNull(store.find(alice.contentHash, "bob"))
    assertEquals(1, scalar("SELECT count(*) FROM artifact_content"))
    assertEquals(1, scalar("SELECT count(*) FROM pipeline_definition"))
    val bytes =
        dataSource.connection.use { c ->
          c.createStatement().use { s ->
            s.executeQuery("SELECT content FROM artifact_content").use {
              it.next()
              it.getBytes(1)
            }
          }
        }
    assertContentEquals("jar-shared".toByteArray(), bytes)

    assertEquals(DeleteResult.Deleted, store.delete(alice.contentHash, "bob"))
    assertEquals(0, scalar("SELECT count(*) FROM artifact_content"))
  }

  @Test
  fun `a version in use does not stop another uploader's version from being deleted`() {
    val alice = artifact("shared", uploader = "alice")
    store.saveIfAbsent(alice)
    store.saveIfAbsent(artifact("shared", uploader = "bob"))
    dataSource.connection.use { c ->
      c.createStatement().use {
        it.execute(
            "CREATE TABLE test_reference (definition_id BIGINT NOT NULL " +
                "REFERENCES pipeline_definition (id) ON DELETE RESTRICT)"
        )
        it.execute(
            "INSERT INTO test_reference SELECT d.id FROM pipeline_definition d " +
                "JOIN pipeline_artifact a ON a.id = d.artifact_id WHERE a.uploaded_by = 'alice'"
        )
      }
    }

    assertEquals(DeleteResult.InUse, store.delete(alice.contentHash, "alice"))
    assertEquals(DeleteResult.Deleted, store.delete(alice.contentHash, "bob"))

    assertNotNull(store.find(alice.contentHash, "alice"))
    assertNull(store.find(alice.contentHash, "bob"))
    assertEquals(1, scalar("SELECT count(*) FROM artifact_content"))
  }

  @Test
  fun `uploads racing with the deletion of the last other version never leave a version without its jar`() {
    repeat(25) { round ->
      val label = "round-$round"
      val alice = artifact(label, uploader = "alice")
      store.saveIfAbsent(alice)
      val pool = Executors.newFixedThreadPool(2)
      val start = CountDownLatch(1)
      val delete =
          pool.submit<DeleteResult> {
            start.await()
            store.delete(alice.contentHash, "alice")
          }
      val upload =
          pool.submit<SaveResult> {
            val bob = artifact(label, uploader = "bob")
            start.await()
            store.saveIfAbsent(bob)
          }
      start.countDown()
      delete.getWithin("the deletion")
      upload.getWithin("the upload")
      pool.shutdown()

      assertNotNull(store.find(alice.contentHash, "bob"), label)
      assertEquals(
          1,
          scalar(
              "SELECT count(*) FROM artifact_content WHERE content_hash = '${alice.contentHash}'"
          ),
          label,
      )
    }
  }
}

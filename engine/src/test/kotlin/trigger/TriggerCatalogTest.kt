package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.PostgresArtifactStore
import dev.lawlan.runline.engine.artifact.PostgresDefinitionStore
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.migratedDatabase
import java.nio.file.Files
import kotlin.test.*

class TriggerCatalogTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val pipelines =
      StoredPipelines(PostgresArtifactStore(dataSource), Files.createTempDirectory("catalog"))
  private val definitions = PostgresDefinitionStore(dataSource)
  private val store = PostgresTriggerStore(dataSource)
  private val clock = MutableClock()
  private val admin = TriggerAdmin(definitions, PostgresArtifactStore(dataSource), store, clock)
  private val catalog = TriggerCatalog(definitions, store)
  private val root = ApiIdentity("root", Role.ADMIN)
  private val v1 = pipelines.save("v1", "nightly")

  private fun create(name: String, parameters: Map<String, String>) =
      admin.create(
          CreateTrigger(name, TriggerKind.CRON, v1, "nightly", parameters, "0 2 * * *"),
          root,
      )

  @Test
  fun `a trigger is shown with the parameters it holds and the defaults the version adds`() {
    create("a", mapOf("env" to "prod"))
    create("b", mapOf("env" to "stage", "retries" to "9"))

    val a = catalog.find("a")!!
    assertEquals(mapOf("env" to "prod"), a.trigger.parameters)
    assertEquals(mapOf("env" to "prod", "retries" to "3"), a.effectiveParameters)
    assertEquals(mapOf("env" to "stage", "retries" to "9"), catalog.find("b")!!.effectiveParameters)
    assertEquals(listOf("a", "b"), catalog.list().map { it.trigger.name })
    assertNull(catalog.find("nobody"))
  }

  @Test
  fun `firings are listed for an existing trigger only`() {
    create("a", mapOf("env" to "prod"))
    val trigger = store.find("a")!!
    store.claimOccurrence(trigger.id, clock.instant(), clock.instant())

    assertEquals(1, catalog.firings("a", 10)!!.size)
    assertNull(catalog.firings("nobody", 10))
  }
}

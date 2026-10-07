package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.*
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.ParameterProblem
import dev.lawlan.runline.engine.run.ParameterProblemKind
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.migratedDatabase
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

class TriggerAdminTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val pipelines =
      StoredPipelines(PostgresArtifactStore(dataSource), Files.createTempDirectory("trigger-admin"))
  private val definitions = PostgresDefinitionStore(dataSource)
  private val store = PostgresTriggerStore(dataSource)
  private val clock = MutableClock()
  private val admin =
      TriggerAdmin(definitions, VersionResolver(PostgresArtifactStore(dataSource)), store, clock)
  private val root = ApiIdentity("root", Role.ADMIN)
  private val ops = ApiIdentity("ops", Role.ADMIN)
  private val v1 = pipelines.save("v1", "nightly")

  private fun cron(
      name: String = "every-night",
      hash: String = v1,
      parameters: Map<String, String> = mapOf("env" to "prod"),
      expression: String? = "0 2 * * *",
      zone: String? = "Asia/Taipei",
  ) = CreateTrigger(name, TriggerKind.CRON, hash, "nightly", parameters, expression, zone)

  private fun hook(
      name: String = "on-push",
      parameters: Map<String, String> = mapOf("env" to "prod"),
  ) = CreateTrigger(name, TriggerKind.WEBHOOK, v1, "nightly", parameters)

  private fun created(request: CreateTrigger) =
      assertIs<CreateTriggerResult.Created>(admin.create(request, root)).trigger

  // ---- create ----

  @Test
  fun `a cron trigger is bound to the version, keeps its parameters and records its creator`() {
    val result = assertIs<CreateTriggerResult.Created>(admin.create(cron(), root))

    assertNull(result.secret)
    val trigger = result.trigger
    assertEquals("every-night", trigger.name)
    assertEquals(v1, trigger.contentHash)
    assertEquals("nightly", trigger.pipeline)
    assertEquals(mapOf("env" to "prod"), trigger.parameters)
    assertEquals("0 2 * * *", trigger.cronExpression)
    assertEquals("Asia/Taipei", trigger.timeZone)
    assertTrue(trigger.enabled)
    assertEquals("root", trigger.createdBy)
    assertEquals(clock.instant(), trigger.createdAt)
    assertEquals(trigger, store.find("every-night"))
  }

  @Test
  fun `a cron trigger without a time zone uses UTC`() {
    assertEquals("UTC", created(cron(zone = null)).timeZone)
  }

  @Test
  fun `a trigger can be created disabled`() {
    assertFalse(created(cron().copy(enabled = false)).enabled)
  }

  @Test
  fun `a webhook trigger gets a secret that is shown once and only its hash is kept`() {
    val result = assertIs<CreateTriggerResult.Created>(admin.create(hook(), root))

    val secret = checkNotNull(result.secret)
    assertTrue(secret.length >= 43)
    assertEquals(clock.instant(), result.trigger.secretRotatedAt)
    val credential = checkNotNull(store.webhookCredential("on-push"))
    assertEquals(WebhookSecrets.hash(secret), credential.secretHash)
    assertNotEquals(secret, credential.secretHash)
    assertNotEquals(
        secret,
        assertIs<CreateTriggerResult.Created>(admin.create(hook("another"), root)).secret,
    )
  }

  @Test
  fun `a name that is not valid or is taken is refused`() {
    listOf("", " ", "has space", "-starts-with-dash", "a".repeat(101), "slash/name").forEach {
      assertEquals(
          CreateTriggerResult.Invalid(InvalidTrigger.NAME),
          admin.create(cron(it), root),
          it,
      )
    }
    created(cron())

    assertEquals(CreateTriggerResult.NameTaken, admin.create(cron(), root))
    assertEquals(CreateTriggerResult.NameTaken, admin.create(hook("every-night"), root))
  }

  @Test
  fun `a definition that does not exist is refused`() {
    assertEquals(
        CreateTriggerResult.DefinitionNotFound,
        admin.create(cron(hash = "0".repeat(64)), root),
    )
    assertEquals(
        CreateTriggerResult.DefinitionNotFound,
        admin.create(cron().copy(pipeline = "missing"), root),
    )
    assertTrue(store.list().isEmpty())
  }

  @Test
  fun `parameters are checked against the metadata and the parameter at fault is named`() {
    assertEquals(
        CreateTriggerResult.InvalidParameters(
            listOf(ParameterProblem("env", ParameterProblemKind.MISSING))
        ),
        admin.create(cron(parameters = emptyMap()), root),
    )
    assertEquals(
        CreateTriggerResult.InvalidParameters(
            listOf(ParameterProblem("nope", ParameterProblemKind.UNDECLARED))
        ),
        admin.create(cron(parameters = mapOf("env" to "prod", "nope" to "1")), root),
    )
    assertTrue(store.list().isEmpty())
  }

  @Test
  fun `a cron trigger needs a valid expression and zone and a webhook has none`() {
    assertEquals(
        CreateTriggerResult.Invalid(InvalidTrigger.CRON_REQUIRED),
        admin.create(cron(expression = null), root),
    )
    assertEquals(
        CreateTriggerResult.Invalid(InvalidTrigger.CRON_EXPRESSION),
        admin.create(cron(expression = "every day"), root),
    )
    assertEquals(
        CreateTriggerResult.Invalid(InvalidTrigger.TIME_ZONE),
        admin.create(cron(zone = "Mars/Olympus"), root),
    )
    assertEquals(
        CreateTriggerResult.Invalid(InvalidTrigger.SCHEDULE_ON_WEBHOOK),
        admin.create(hook().copy(cronExpression = "0 2 * * *"), root),
    )
    assertEquals(
        CreateTriggerResult.Invalid(InvalidTrigger.SCHEDULE_ON_WEBHOOK),
        admin.create(hook().copy(timeZone = "UTC"), root),
    )
    assertTrue(store.list().isEmpty())
  }

  // ---- update ----

  @Test
  fun `changing parameters, schedule and enabled records who and when`() {
    created(cron())
    clock.advance(Duration.ofMinutes(5))

    val updated =
        assertIs<UpdateTriggerResult.Updated>(
                admin.update(
                    "every-night",
                    UpdateTrigger(
                        parameters = mapOf("env" to "stage", "retries" to "5"),
                        enabled = false,
                        cronExpression = "30 4 * * 1",
                        timeZone = "UTC",
                    ),
                    ops,
                )
            )
            .trigger

    assertEquals(mapOf("env" to "stage", "retries" to "5"), updated.parameters)
    assertFalse(updated.enabled)
    assertEquals("30 4 * * 1", updated.cronExpression)
    assertEquals("UTC", updated.timeZone)
    assertEquals("ops", updated.updatedBy)
    assertEquals(clock.instant(), updated.updatedAt)
    assertEquals("root", updated.createdBy)
    assertEquals(v1, updated.contentHash)
  }

  @Test
  fun `what an update does not mention stays`() {
    created(cron())

    val updated =
        assertIs<UpdateTriggerResult.Updated>(
                admin.update("every-night", UpdateTrigger(enabled = false), ops)
            )
            .trigger

    assertEquals(mapOf("env" to "prod"), updated.parameters)
    assertEquals("0 2 * * *", updated.cronExpression)
    assertEquals("Asia/Taipei", updated.timeZone)
    assertEquals(v1, updated.contentHash)
  }

  @Test
  fun `an update is checked like a creation`() {
    created(cron())
    created(hook())

    assertEquals(
        UpdateTriggerResult.NotFound,
        admin.update("nobody", UpdateTrigger(enabled = true), ops),
    )
    assertEquals(
        UpdateTriggerResult.Invalid(InvalidTrigger.NOTHING_TO_CHANGE),
        admin.update("every-night", UpdateTrigger(), ops),
    )
    assertEquals(
        UpdateTriggerResult.Invalid(InvalidTrigger.CRON_EXPRESSION),
        admin.update("every-night", UpdateTrigger(cronExpression = "nope"), ops),
    )
    assertEquals(
        UpdateTriggerResult.Invalid(InvalidTrigger.TIME_ZONE),
        admin.update("every-night", UpdateTrigger(timeZone = "Nowhere/Land"), ops),
    )
    assertEquals(
        UpdateTriggerResult.Invalid(InvalidTrigger.SCHEDULE_ON_WEBHOOK),
        admin.update("on-push", UpdateTrigger(cronExpression = "0 2 * * *"), ops),
    )
    assertEquals(
        UpdateTriggerResult.InvalidParameters(
            listOf(ParameterProblem("nope", ParameterProblemKind.UNDECLARED))
        ),
        admin.update(
            "every-night",
            UpdateTrigger(parameters = mapOf("env" to "x", "nope" to "1")),
            ops,
        ),
    )
    assertEquals("0 2 * * *", store.find("every-night")!!.cronExpression)
  }

  @Test
  fun `moving the binding to another version checks the parameters against that version`() {
    created(cron(parameters = mapOf("env" to "prod", "retries" to "5")))
    val strictMetadata =
        pipelines.metadata.copy(
            parameters =
                listOf(
                    ParameterDoc("env", required = true),
                    ParameterDoc("region", required = true),
                )
        )
    val v2 = pipelines.save("v2", "nightly", metadata = strictMetadata)

    // The held parameters do not fit v2: region is missing and retries is not declared.
    val refused =
        assertIs<UpdateTriggerResult.InvalidParameters>(
            admin.update("every-night", UpdateTrigger(contentHash = v2), ops)
        )
    assertEquals(
        setOf(
            ParameterProblem("region", ParameterProblemKind.MISSING),
            ParameterProblem("retries", ParameterProblemKind.UNDECLARED),
        ),
        refused.problems.toSet(),
    )
    assertEquals(v1, store.find("every-night")!!.contentHash)

    val moved =
        assertIs<UpdateTriggerResult.Updated>(
                admin.update(
                    "every-night",
                    UpdateTrigger(
                        contentHash = v2,
                        parameters = mapOf("env" to "prod", "region" to "eu"),
                    ),
                    ops,
                )
            )
            .trigger
    assertEquals(v2, moved.contentHash)
    assertEquals(mapOf("env" to "prod", "region" to "eu"), moved.parameters)
  }

  @Test
  fun `a binding does not follow a newer version`() {
    created(cron())

    pipelines.save("v2", "nightly")
    pipelines.save("v3", "nightly")

    assertEquals(v1, store.find("every-night")!!.contentHash)
  }

  @Test
  fun `moving to a version that does not exist is refused`() {
    created(cron())

    assertEquals(
        UpdateTriggerResult.DefinitionNotFound,
        admin.update("every-night", UpdateTrigger(contentHash = "0".repeat(64)), ops),
    )
    assertEquals(v1, store.find("every-night")!!.contentHash)
  }

  // ---- secret and delete ----

  @Test
  fun `rotating the secret gives a new one, once, and the old one stops matching`() {
    val first = assertIs<CreateTriggerResult.Created>(admin.create(hook(), root)).secret!!
    clock.advance(Duration.ofHours(1))

    val rotated = assertIs<RotateSecretResult.Rotated>(admin.rotateSecret("on-push", ops))

    assertNotEquals(first, rotated.secret)
    assertEquals(clock.instant(), rotated.trigger.secretRotatedAt)
    assertEquals("ops", rotated.trigger.updatedBy)
    val hash = store.webhookCredential("on-push")!!.secretHash
    assertTrue(WebhookSecrets.matches(rotated.secret, hash))
    assertFalse(WebhookSecrets.matches(first, hash))
  }

  @Test
  fun `only a webhook trigger has a secret to rotate`() {
    created(cron())

    assertEquals(RotateSecretResult.NotAWebhook, admin.rotateSecret("every-night", ops))
    assertEquals(RotateSecretResult.NotFound, admin.rotateSecret("nobody", ops))
  }

  @Test
  fun `deleting a trigger unbinds it`() {
    created(cron())

    assertTrue(admin.delete("every-night", ops))

    assertNull(store.find("every-night"))
    assertFalse(admin.delete("every-night", ops))
  }
}

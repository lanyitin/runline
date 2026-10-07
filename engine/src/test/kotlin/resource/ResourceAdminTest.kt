package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ResourceAdminTest {
  private val store = PostgresResourceStore(dataSourceOf(migratedDatabase()))
  private val changes = AtomicInteger()
  private val now = Instant.parse("2026-10-04T10:00:00Z")
  private val admin =
      ResourceAdmin(store, Clock.fixed(now, ZoneOffset.UTC)) { changes.incrementAndGet() }
  private val root = ApiIdentity("root", Role.ADMIN)
  private val ops = ApiIdentity("ops", Role.ADMIN)

  @Test
  fun `a resource is created enabled and records who created it and when`() {
    val result = admin.create("lemonade", 2, root)

    val created = assertIs<CreateResourceResult.Created>(result).resource
    assertEquals("lemonade", created.name)
    assertEquals(2, created.capacity)
    assertTrue(created.enabled)
    assertEquals("root", created.createdBy)
    assertEquals("root", created.updatedBy)
    assertEquals(now, created.createdAt)
    assertEquals(created, admin.find("lemonade"))
  }

  @Test
  fun `creating a name that exists is refused and keeps the existing definition`() {
    admin.create("lemonade", 2, root)

    assertEquals(CreateResourceResult.AlreadyExists, admin.create("lemonade", 5, ops))

    assertEquals(2, admin.find("lemonade")!!.capacity)
  }

  @Test
  fun `a capacity below one is refused`() {
    assertEquals(
        CreateResourceResult.Invalid(InvalidResource.CAPACITY),
        admin.create("lemonade", 0, root),
    )
    admin.create("ok", 1, root)
    assertEquals(
        UpdateResourceResult.Invalid(InvalidResource.CAPACITY),
        admin.update("ok", 0, null, root),
    )
    assertNull(admin.find("lemonade"))
    assertEquals(1, admin.find("ok")!!.capacity)
  }

  @Test
  fun `names that are empty, too long or not path safe are refused`() {
    listOf("", " ", "a b", "a/b", "-a", "x".repeat(101), "é").forEach {
      assertEquals(
          CreateResourceResult.Invalid(InvalidResource.NAME),
          admin.create(it, 1, root),
          "name '$it'",
      )
    }
    assertIs<CreateResourceResult.Created>(admin.create("x".repeat(100), 1, root))
    assertIs<CreateResourceResult.Created>(admin.create("GPU-1.a_b", 1, root))
  }

  @Test
  fun `the capacity can be changed and the change is attributed to the caller`() {
    admin.create("lemonade", 1, root)

    val result = admin.update("lemonade", 3, null, ops)

    val updated = assertIs<UpdateResourceResult.Updated>(result).resource
    assertEquals(3, updated.capacity)
    assertTrue(updated.enabled)
    assertEquals("ops", updated.updatedBy)
    assertEquals("root", updated.createdBy)
  }

  @Test
  fun `a resource can be disabled and enabled again`() {
    admin.create("lemonade", 1, root)

    assertFalse(
        assertIs<UpdateResourceResult.Updated>(admin.update("lemonade", null, false, ops))
            .resource
            .enabled
    )
    assertTrue(
        assertIs<UpdateResourceResult.Updated>(admin.update("lemonade", null, true, ops))
            .resource
            .enabled
    )
  }

  @Test
  fun `changing a resource that does not exist says so`() {
    assertEquals(UpdateResourceResult.NotFound, admin.update("nothing", 2, null, root))
  }

  @Test
  fun `an update that changes nothing is refused`() {
    admin.create("lemonade", 1, root)

    assertEquals(
        UpdateResourceResult.Invalid(InvalidResource.NOTHING_TO_CHANGE),
        admin.update("lemonade", null, null, root),
    )
  }

  @Test
  fun `a change that may free or block waiting runs is announced, a refused one is not`() {
    admin.create("lemonade", 1, root)
    assertEquals(0, changes.get())

    admin.update("lemonade", 2, null, root)
    admin.update("lemonade", null, false, root)
    assertEquals(2, changes.get())

    admin.update("lemonade", 0, null, root)
    admin.update("nothing", 2, null, root)
    assertEquals(2, changes.get())
  }

  @Test
  fun `list returns every resource by name`() {
    admin.create("b", 1, root)
    admin.create("a", 1, root)

    assertEquals(listOf("a", "b"), admin.list().map { it.name })
  }

  private val someSettings = JsonObject(mapOf("path" to JsonPrimitive("a.txt")))

  @Test
  fun `a resource created without a type is a counter`() {
    val created = assertIs<CreateResourceResult.Created>(admin.create("lemonade", 2, root)).resource

    assertEquals(ResourceType.COUNTER, created.type)
    assertEquals(JsonObject(emptyMap()), created.settings)
    assertNull(created.secretAlias)
  }

  @Test
  fun `a counter can be asked for by name and may be given empty settings`() {
    val result =
        admin.create("lemonade", 1, root, type = "counter", settings = JsonObject(emptyMap()))

    assertEquals(ResourceType.COUNTER, assertIs<CreateResourceResult.Created>(result).resource.type)
  }

  @Test
  fun `the types that are in the set but not yet implemented are refused and nothing is stored`() {
    listOf("file", "jdbc-pool", "openai-compatible").forEach {
      assertEquals(
          CreateResourceResult.Invalid(InvalidResource.UNSUPPORTED_TYPE),
          admin.create("r-$it", 1, root, type = it),
          it,
      )
      assertNull(admin.find("r-$it"))
    }
  }

  @Test
  fun `a type that is not in the closed set is refused as unknown`() {
    listOf("http-endpoint", "", "Counter", "COUNTER", " counter").forEach {
      assertEquals(
          CreateResourceResult.Invalid(InvalidResource.UNKNOWN_TYPE),
          admin.create("lemonade", 1, root, type = it),
          "type '$it'",
      )
    }
    assertNull(admin.find("lemonade"))
  }

  @Test
  fun `a counter given settings or a secret alias is refused`() {
    assertEquals(
        CreateResourceResult.Invalid(InvalidResource.INVALID_SETTINGS),
        admin.create("a", 1, root, settings = someSettings),
    )
    assertEquals(
        CreateResourceResult.Invalid(InvalidResource.INVALID_SECRET_ALIAS),
        admin.create("b", 1, root, secretAlias = "key"),
    )
    assertEquals(
        CreateResourceResult.Invalid(InvalidResource.INVALID_SECRET_ALIAS),
        admin.create("c", 1, root, secretAlias = ""),
    )
    assertEquals(emptyList(), admin.list())
  }

  @Test
  fun `the name and the capacity are judged before the type`() {
    assertEquals(
        CreateResourceResult.Invalid(InvalidResource.NAME),
        admin.create("a b", 1, root, type = "nope"),
    )
    assertEquals(
        CreateResourceResult.Invalid(InvalidResource.CAPACITY),
        admin.create("ok", 0, root, type = "nope"),
    )
  }

  @Test
  fun `the name and the type cannot be changed, even to the same value`() {
    admin.create("lemonade", 1, root)

    assertEquals(
        UpdateResourceResult.Invalid(InvalidResource.IMMUTABLE_NAME),
        admin.update("lemonade", 2, null, root, newName = "other"),
    )
    assertEquals(
        UpdateResourceResult.Invalid(InvalidResource.IMMUTABLE_TYPE),
        admin.update("lemonade", 2, null, root, type = "file"),
    )
    assertEquals(
        UpdateResourceResult.Invalid(InvalidResource.IMMUTABLE_TYPE),
        admin.update("lemonade", 2, null, root, type = "counter"),
    )
    assertEquals(1, admin.find("lemonade")!!.capacity)
    assertEquals(0, changes.get())
  }

  @Test
  fun `a counter cannot be given settings or an alias by an update either`() {
    admin.create("lemonade", 1, root)

    assertEquals(
        UpdateResourceResult.Invalid(InvalidResource.INVALID_SETTINGS),
        admin.update("lemonade", 2, null, root, settings = someSettings),
    )
    assertEquals(
        UpdateResourceResult.Invalid(InvalidResource.INVALID_SECRET_ALIAS),
        admin.update("lemonade", 2, null, root, secretAlias = "key"),
    )
    assertEquals(1, admin.find("lemonade")!!.capacity)
    assertEquals(
        UpdateResourceResult.NotFound,
        admin.update("nothing", 2, null, root, settings = someSettings),
    )
  }
}

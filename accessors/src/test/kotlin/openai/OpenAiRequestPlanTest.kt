package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How a call of a pipeline becomes the request of the resource (WI-46): the resource's defaults
 * come first and the pipeline's values over them, what the administrator locked, bounded or limited
 * holds, and nothing a pipeline gives can name another host, path, method or header.
 */
class OpenAiRequestPlanTest {
  private fun settings(extra: String = "") =
      (OpenAiSettings.parse(
              Json.parseToJsonElement(
                      """{"baseUrl":"http://svc.internal:9000/v1"${if (extra.isEmpty()) "" else ",$extra"}}"""
                  )
                  .jsonObject
          ) as SettingsResult.Valid)
          .settings

  private fun call(
      endpoint: String,
      body: String? = null,
      path: Map<String, String> = emptyMap(),
      query: Map<String, String> = emptyMap(),
      timeouts: Map<String, Long> = emptyMap(),
  ): Map<String, Any?> =
      mapOf(
          "endpoint" to endpoint,
          "body" to body,
          "pathParameters" to path,
          "query" to query,
          "timeoutsMillis" to timeouts,
      )

  private fun plan(settings: OpenAiSettings, arguments: Map<String, Any?>) =
      OpenAiRequestPlan.of(settings, arguments)

  private fun refused(settings: OpenAiSettings, arguments: Map<String, Any?>): ResourceFailure =
      assertFailsWith<ResourceOperationFailure> { plan(settings, arguments) }.failure

  private fun bodyOf(plan: OpenAiRequestPlan): JsonObject =
      Json.parseToJsonElement(plan.body!!.decodeToString()).jsonObject

  @Test
  fun `the request goes to the entry's method and path below the base address`() {
    val p = plan(settings(), call("chat.completions", """{"messages":[]}"""))

    assertEquals("POST", p.method)
    assertEquals("http://svc.internal:9000/v1/chat/completions", p.uri.toString())
    assertEquals("chat.completions", p.endpoint.id)
  }

  @Test
  fun `path and query parameters go into the address`() {
    val s = settings("""{"x":1}""".let { """"endpoints":["files.list","models.retrieve"]""" })

    assertEquals(
        "http://svc.internal:9000/v1/models/m1",
        plan(s, call("models.retrieve", path = mapOf("model" to "m1"))).uri.toString(),
    )
    assertEquals(
        "http://svc.internal:9000/v1/files?limit=5",
        plan(s, call("files.list", query = mapOf("limit" to "5"))).uri.toString(),
    )
    assertNull(plan(s, call("files.list")).body)
  }

  @Test
  fun `an endpoint that is not in the catalog or not enabled is refused before anything is sent`() {
    val s = settings()

    assertEquals(ResourceFailure.UNKNOWN_ENDPOINT, refused(s, call("chat/completions")))
    assertEquals(ResourceFailure.UNKNOWN_ENDPOINT, refused(s, call("http://evil.example/")))
    assertEquals(
        ResourceFailure.ENDPOINT_NOT_ENABLED,
        refused(s, call("responses.delete", path = mapOf("id" to "r1"))),
    )
    assertEquals(ResourceFailure.ENDPOINT_NOT_ENABLED, refused(s, call("images.edits")))
  }

  @Test
  fun `the resource's defaults come first and the pipeline's values over them`() {
    val s = settings(""""defaults":{"model":"m1","temperature":0.2,"max_tokens":100}""")

    val body =
        bodyOf(
            plan(
                s,
                call(
                    "chat.completions",
                    """{"messages":[{"role":"user","content":"hi"}],"temperature":0.9}""",
                ),
            )
        )

    assertEquals("m1", body["model"]!!.jsonPrimitive.content)
    assertEquals("0.9", body["temperature"]!!.jsonPrimitive.content)
    assertEquals("100", body["max_tokens"]!!.jsonPrimitive.content)
    assertTrue("messages" in body)
  }

  @Test
  fun `a call without a body gets the defaults, and a number is kept exactly as written`() {
    val s = settings(""""defaults":{"model":"m1"}""")

    assertEquals("m1", bodyOf(plan(s, call("completions")))["model"]!!.jsonPrimitive.content)
    assertEquals(
        "12345678901234567890",
        bodyOf(plan(s, call("chat.completions", """{"seed":12345678901234567890}""")))["seed"]!!
            .jsonPrimitive
            .content,
    )
  }

  @Test
  fun `sampling defaults go only to the entries that take them and the model only where there is one`() {
    val s =
        settings(
            """"defaults":{"model":"m1","temperature":0.2},"endpoints":["embeddings","moderations","batches.create","models.list"]"""
        )

    val embeddings = bodyOf(plan(s, call("embeddings", """{"input":"x"}""")))
    assertEquals("m1", embeddings["model"]!!.jsonPrimitive.content)
    assertTrue("temperature" !in embeddings)
    assertTrue("model" !in bodyOf(plan(s, call("batches.create", """{"input_file_id":"f"}"""))))
    assertNull(plan(s, call("models.list")).body)
  }

  @Test
  fun `a locked parameter cannot be set by the pipeline, whatever the value`() {
    val s =
        settings(
            """"defaults":{"temperature":0.2,"model":"m1"},"lockedParameters":["temperature","model"]"""
        )

    assertEquals(
        ResourceFailure.PARAMETER_LOCKED,
        refused(s, call("chat.completions", """{"temperature":0.2}""")),
    )
    assertEquals(
        ResourceFailure.PARAMETER_LOCKED,
        refused(s, call("chat.completions", """{"model":"other"}""")),
    )

    val body = bodyOf(plan(s, call("chat.completions", """{"messages":[]}""")))
    assertEquals("0.2", body["temperature"]!!.jsonPrimitive.content)
    assertEquals("m1", body["model"]!!.jsonPrimitive.content)
  }

  @Test
  fun `only the allowed models may be asked for, the default included`() {
    val s = settings(""""allowedModels":["m1","m2"],"defaults":{"model":"m1"}""")

    assertEquals(
        ResourceFailure.MODEL_NOT_ALLOWED,
        refused(s, call("chat.completions", """{"model":"m3"}""")),
    )
    assertEquals(
        "m2",
        bodyOf(plan(s, call("chat.completions", """{"model":"m2"}""")))["model"]!!
            .jsonPrimitive
            .content,
    )
    assertEquals(
        "m1",
        bodyOf(plan(s, call("chat.completions", """{}""")))["model"]!!.jsonPrimitive.content,
    )
    // A model that cannot be told is not allowed when only some are.
    val none = settings(""""allowedModels":["m1"]""")
    assertEquals(
        ResourceFailure.MODEL_NOT_ALLOWED,
        refused(none, call("chat.completions", """{}""")),
    )
    assertEquals(
        ResourceFailure.INVALID_ARGUMENT,
        refused(none, call("chat.completions", """{"model":5}""")),
    )
    assertNull(plan(none, call("models.list")).body)
  }

  @Test
  fun `a number above the ceiling is refused, one at it is not`() {
    val s = settings(""""maxValues":{"max_tokens":500}""")

    assertEquals(
        ResourceFailure.VALUE_ABOVE_LIMIT,
        refused(s, call("chat.completions", """{"max_tokens":501}""")),
    )
    assertEquals(
        ResourceFailure.VALUE_ABOVE_LIMIT,
        refused(s, call("chat.completions", """{"max_tokens":1e9}""")),
    )
    assertEquals(
        "500",
        bodyOf(plan(s, call("chat.completions", """{"max_tokens":500}""")))["max_tokens"]!!
            .jsonPrimitive
            .content,
    )
    assertEquals(
        ResourceFailure.INVALID_ARGUMENT,
        refused(s, call("chat.completions", """{"max_tokens":"many"}""")),
    )
  }

  @Test
  fun `a streamed answer is not given by this call`() {
    val s = settings()

    assertEquals(
        ResourceFailure.STREAM_NOT_SUPPORTED,
        refused(s, call("chat.completions", """{"stream":true}""")),
    )
    assertEquals(
        ResourceFailure.STREAM_NOT_SUPPORTED,
        refused(s, call("chat.completions", """{"stream":"true"}""")),
    )
    assertEquals(
        ResourceFailure.STREAM_NOT_SUPPORTED,
        refused(s, call("chat.completions", """{"stream_options":{"include_usage":true}}""")),
    )
    assertEquals(
        "false",
        bodyOf(plan(s, call("chat.completions", """{"stream":false}""")))["stream"]!!
            .jsonPrimitive
            .content,
    )
  }

  @Test
  fun `a body that is not a JSON object, or is given to an entry without one, is refused`() {
    val s = settings(""""endpoints":["models.list","chat.completions"]""")

    for (bad in listOf("not json", "[1,2]", "\"text\"", "5", "null", "{", """{"a":1} extra""")) {
      assertEquals(ResourceFailure.INVALID_ARGUMENT, refused(s, call("chat.completions", bad)), bad)
    }
    assertEquals(ResourceFailure.INVALID_ARGUMENT, refused(s, call("models.list", "{}")))
  }

  @Test
  fun `a request larger than the resource allows is refused`() {
    val s =
        settings(
            """"maxRequestBytes":100,"defaults":{"model":"a-model-name-that-is-long-enough-to-matter"}"""
        )

    assertEquals(
        ResourceFailure.REQUEST_TOO_LARGE,
        refused(s, call("chat.completions", """{"messages":"${"x".repeat(200)}"}""")),
    )
    // What the defaults add counts too.
    assertEquals(
        ResourceFailure.REQUEST_TOO_LARGE,
        refused(s, call("chat.completions", """{"messages":"${"x".repeat(60)}"}""")),
    )
    assertIs<OpenAiRequestPlan>(plan(s, call("chat.completions", """{"m":"y"}""")))
  }

  @Test
  fun `a limit on time can be made shorter by the pipeline and never longer`() {
    val s =
        settings(
            """"timeouts":{"connectMs":1000,"firstByteMs":60000,"idleMs":30000,"totalMs":90000,"quotaWaitMs":5000}"""
        )

    val shorter =
        plan(
            s,
            call(
                "chat.completions",
                timeouts =
                    mapOf(
                        "connect" to 200L,
                        "firstByte" to 1000L,
                        "idle" to 500L,
                        "total" to 2000L,
                        "quotaWait" to 100L,
                    ),
            ),
        )
    assertEquals(OpenAiLimits(200, 1000, 500, 2000, 100), shorter.limits)

    val longer =
        plan(
            s,
            call(
                "chat.completions",
                timeouts =
                    mapOf(
                        "connect" to 9999L,
                        "firstByte" to 999999L,
                        "idle" to 999999L,
                        "total" to 999999L,
                        "quotaWait" to 99999L,
                    ),
            ),
        )
    assertEquals(OpenAiLimits(1000, 60000, 30000, 90000, 5000), longer.limits)
    assertEquals(
        OpenAiLimits(1000, 60000, 30000, 90000, 5000),
        plan(s, call("chat.completions")).limits,
    )
  }

  @Test
  fun `a pipeline can set a total limit where the resource has none`() {
    val s = settings()

    assertNull(plan(s, call("chat.completions")).limits.totalMillis)
    assertEquals(
        700L,
        plan(s, call("chat.completions", timeouts = mapOf("total" to 700L))).limits.totalMillis,
    )
  }

  @Test
  fun `a limit that is not a positive number, or is none of the five, is refused`() {
    val s = settings()

    assertEquals(
        ResourceFailure.INVALID_ARGUMENT,
        refused(s, call("chat.completions", timeouts = mapOf("total" to 0L))),
    )
    assertEquals(
        ResourceFailure.INVALID_ARGUMENT,
        refused(s, call("chat.completions", timeouts = mapOf("firstByte" to -5L))),
    )
    assertEquals(
        ResourceFailure.INVALID_ARGUMENT,
        refused(s, call("chat.completions", timeouts = mapOf("forever" to 5L))),
    )
  }

  @Test
  fun `arguments of the wrong shape are refused`() {
    val s = settings()

    assertEquals(ResourceFailure.INVALID_ARGUMENT, refused(s, mapOf("endpoint" to 5)))
    assertEquals(ResourceFailure.UNKNOWN_ENDPOINT, refused(s, mapOf("endpoint" to "nope")))
    assertEquals(
        ResourceFailure.INVALID_ARGUMENT,
        refused(s, call("chat.completions") + mapOf("body" to 5)),
    )
  }

  @Test
  fun `nothing a pipeline gives can add a header, change the method or name a host`() {
    val s = settings(""""endpoints":["models.retrieve","files.list"]""")
    val hostile =
        listOf(
            "http://evil.example/x",
            "//evil.example",
            "..",
            "a/../b",
            "a\r\nHost: evil",
            "x@evil",
        )

    for (value in hostile) {
      val p =
          try {
            plan(s, call("models.retrieve", path = mapOf("model" to value)))
          } catch (e: ResourceOperationFailure) {
            continue
          }
      assertEquals("svc.internal", p.uri.host)
      assertEquals("GET", p.method)
    }
    for (value in hostile) {
      val p =
          try {
            plan(s, call("files.list", query = mapOf("purpose" to value)))
          } catch (e: ResourceOperationFailure) {
            continue
          }
      assertEquals("svc.internal", p.uri.host)
      assertEquals("/v1/files", p.uri.rawPath)
    }
    assertEquals(
        ResourceFailure.INVALID_ARGUMENT,
        refused(s, call("files.list", query = mapOf("Host" to "evil.example"))),
    )
  }
}

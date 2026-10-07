package dev.lawlan.runline.accessors.openai

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * What an administrator may fix for an `openai-compatible` resource (WI-46): every setting is
 * checked when it is given, and what is stored says every effective value, so a newer Engine's
 * defaults never change a resource that exists.
 */
class OpenAiSettingsTest {
  private fun parse(json: String) = OpenAiSettings.parse(Json.parseToJsonElement(json).jsonObject)

  private fun valid(json: String): OpenAiSettings =
      assertIs<SettingsResult.Valid>(parse(json)).settings

  private fun problem(json: String): OpenAiSettingsProblem =
      assertIs<SettingsResult.Invalid>(parse(json)).problem

  @Test
  fun `a base address alone is enough and everything else has the documented default`() {
    val s = valid("""{"baseUrl":"http://127.0.0.1:8000/api/v1"}""")

    assertEquals(URI.create("http://127.0.0.1:8000/api/v1"), s.baseUrl)
    assertNull(s.organization)
    assertNull(s.project)
    assertEquals(emptyMap(), s.headers)
    assertEquals(
        listOf("chat.completions", "completions", "embeddings", "models.list", "models.retrieve"),
        s.endpoints.toList(),
    )
    assertEquals(10_000L, s.timeouts.connectMillis)
    assertEquals(15L * 60 * 1000, s.timeouts.firstByteMillis)
    assertEquals(5L * 60 * 1000, s.timeouts.idleMillis)
    assertNull(s.timeouts.totalMillis)
    assertEquals(60_000L, s.timeouts.quotaWaitMillis)
    assertEquals(1, s.requestsPerRun)
    assertEquals(32L * 1024 * 1024, s.maxRequestBytes)
    assertEquals(8L * 1024 * 1024, s.maxResponseBytes)
    assertEquals(256L * 1024 * 1024, s.maxDownloadBytes)
    assertEquals(emptySet(), s.allowedModels)
  }

  @Test
  fun `the normalised form says every effective value and parses back to the same settings`() {
    val first = assertIs<SettingsResult.Valid>(parse("""{"baseUrl":"https://llm.internal/v1/"}"""))
    val written = first.normalized

    assertEquals("https://llm.internal/v1", written["baseUrl"]!!.jsonPrimitive.content)
    assertEquals(
        listOf("chat.completions", "completions", "embeddings", "models.list", "models.retrieve"),
        written["endpoints"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    val timeouts = written["timeouts"]!!.jsonObject
    assertEquals(10_000L, timeouts["connectMs"]!!.jsonPrimitive.long)
    assertEquals(900_000L, timeouts["firstByteMs"]!!.jsonPrimitive.long)
    assertEquals(300_000L, timeouts["idleMs"]!!.jsonPrimitive.long)
    assertEquals(60_000L, timeouts["quotaWaitMs"]!!.jsonPrimitive.long)
    assertTrue("totalMs" !in timeouts)
    assertEquals(1, written["requestsPerRun"]!!.jsonPrimitive.int)
    assertEquals(
        first.normalized,
        assertIs<SettingsResult.Valid>(OpenAiSettings.parse(written)).normalized,
    )
  }

  @Test
  fun `the base address is http or https, has no user information and no query`() {
    for (bad in
        listOf(
            "ftp://h/v1",
            "file:///etc",
            "//h/v1",
            "h/v1",
            "http://user:pw@h/v1",
            "http://user@h/v1",
            "http://h/v1?x=1",
            "http://h/v1#f",
            "http://",
            "http:///v1",
            "http://h/a b",
            "",
            "javascript:alert(1)",
        )) {
      assertEquals(
          OpenAiSettingsProblem.INVALID_BASE_URL,
          problem("""{"baseUrl":${JsonPrimitive(bad)}}"""),
          bad,
      )
    }
    assertEquals(OpenAiSettingsProblem.INVALID_BASE_URL, problem("""{"baseUrl":5}"""))
    assertEquals(OpenAiSettingsProblem.INVALID_SETTINGS, problem("""{}"""))
  }

  @Test
  fun `a member that is not a setting is refused`() {
    assertEquals(
        OpenAiSettingsProblem.INVALID_SETTINGS,
        problem("""{"baseUrl":"http://h","apiKey":"sk-123"}"""),
    )
    assertEquals(
        OpenAiSettingsProblem.INVALID_SETTINGS,
        problem("""{"baseUrl":"http://h","path":"/v1/chat/completions"}"""),
    )
  }

  @Test
  fun `a header whose name speaks of credentials is refused whatever its case`() {
    for (name in
        listOf(
            "Authorization",
            "authorization",
            "Proxy-Authorization",
            "X-Api-Key",
            "X-API-KEY",
            "apikey",
            "X-Auth-Token",
            "X-Token",
            "X-Secret",
            "Cookie",
            "X-Cookie-Thing",
            "X-AUTH",
        )) {
      assertEquals(
          OpenAiSettingsProblem.INVALID_HEADER,
          problem("""{"baseUrl":"http://h","headers":{${JsonPrimitive(name)}:"v"}}"""),
          name,
      )
    }
    assertEquals(
        mapOf("X-Team" to "blue", "X-Trace" to "on"),
        valid("""{"baseUrl":"http://h","headers":{"X-Team":"blue","X-Trace":"on"}}""").headers,
    )
  }

  @Test
  fun `a header that the Engine sets itself or that frames the request is refused`() {
    for (name in
        listOf(
            "Host",
            "Content-Length",
            "Content-Type",
            "Accept",
            "Transfer-Encoding",
            "Connection",
            "Upgrade",
            "Expect",
            "TE",
            "OpenAI-Organization",
            "OpenAI-Project",
        )) {
      assertEquals(
          OpenAiSettingsProblem.INVALID_HEADER,
          problem("""{"baseUrl":"http://h","headers":{${JsonPrimitive(name)}:"v"}}"""),
          name,
      )
    }
  }

  @Test
  fun `a header that is not well formed is refused`() {
    for (headers in
        listOf(
            """{"bad name":"v"}""",
            """{"X-A":"line\r\nX-Injected: yes"}""",
            """{"X-A":"a\nb"}""",
            """{"X-A":"\u0000"}""",
            """{"":"v"}""",
            """{"X-A":5}""",
            """["X-A"]""",
        )) {
      assertEquals(
          OpenAiSettingsProblem.INVALID_HEADER,
          problem("""{"baseUrl":"http://h","headers":$headers}"""),
          headers,
      )
    }
  }

  @Test
  fun `organization and project are plain header values`() {
    val s = valid("""{"baseUrl":"http://h","organization":"org-1","project":"proj_9"}""")

    assertEquals("org-1", s.organization)
    assertEquals("proj_9", s.project)
    assertEquals(
        OpenAiSettingsProblem.INVALID_HEADER,
        problem("""{"baseUrl":"http://h","organization":"a\r\nb"}"""),
    )
    assertEquals(
        OpenAiSettingsProblem.INVALID_SETTINGS,
        problem("""{"baseUrl":"http://h","project":5}"""),
    )
  }

  @Test
  fun `the endpoints enabled must be in the catalog`() {
    assertEquals(
        listOf("models.list", "files.list"),
        valid("""{"baseUrl":"http://h","endpoints":["files.list","models.list"]}""")
            .endpoints
            .toList()
            .sortedBy { listOf("models.list", "files.list").indexOf(it) },
    )
    for (bad in
        listOf(
            """["no.such.endpoint"]""",
            """["chat/completions"]""",
            """[]""",
            """"chat.completions"""",
            """[5]""",
        )) {
      assertEquals(
          OpenAiSettingsProblem.INVALID_ENDPOINT,
          problem("""{"baseUrl":"http://h","endpoints":$bad}"""),
          bad,
      )
    }
  }

  @Test
  fun `a stateful entry is enabled only when the administrator names it`() {
    val s = valid("""{"baseUrl":"http://h"}""")
    assertTrue("responses.delete" !in s.endpoints)
    assertTrue("files.delete" !in s.endpoints)
    assertTrue("batches.cancel" !in s.endpoints)

    assertTrue(
        "files.delete" in valid("""{"baseUrl":"http://h","endpoints":["files.delete"]}""").endpoints
    )
  }

  @Test
  fun `the timeouts are positive and the total is optional`() {
    val s =
        valid(
            """{"baseUrl":"http://h","timeouts":{"connectMs":500,"firstByteMs":1000,"idleMs":200,"totalMs":9000,"quotaWaitMs":50}}"""
        )
    assertEquals(500L, s.timeouts.connectMillis)
    assertEquals(1000L, s.timeouts.firstByteMillis)
    assertEquals(200L, s.timeouts.idleMillis)
    assertEquals(9000L, s.timeouts.totalMillis)
    assertEquals(50L, s.timeouts.quotaWaitMillis)
    assertNull(valid("""{"baseUrl":"http://h","timeouts":{"totalMs":null}}""").timeouts.totalMillis)

    for (bad in
        listOf(
            """{"connectMs":0}""",
            """{"firstByteMs":-1}""",
            """{"idleMs":"5"}""",
            """{"totalMs":0}""",
            """{"quotaWaitMs":1.5}""",
            """{"connectMs":999999999999}""",
            """{"unknownMs":1}""",
            """5""",
        )) {
      assertEquals(
          OpenAiSettingsProblem.INVALID_TIMEOUT,
          problem("""{"baseUrl":"http://h","timeouts":$bad}"""),
          bad,
      )
    }
  }

  @Test
  fun `the requests per run and the size limits are bounded`() {
    val s =
        valid(
            """{"baseUrl":"http://h","requestsPerRun":4,"maxRequestBytes":1000,"maxResponseBytes":2000,"maxDownloadBytes":3000}"""
        )
    assertEquals(4, s.requestsPerRun)
    assertEquals(1000L, s.maxRequestBytes)
    assertEquals(2000L, s.maxResponseBytes)
    assertEquals(3000L, s.maxDownloadBytes)

    for (bad in
        listOf(
            """"requestsPerRun":0""",
            """"requestsPerRun":-1""",
            """"requestsPerRun":100000""",
            """"requestsPerRun":"1"""",
            """"maxRequestBytes":0""",
            """"maxResponseBytes":-5""",
            """"maxResponseBytes":99999999999999""",
            """"maxDownloadBytes":0""",
            """"maxDownloadBytes":99999999999999""",
            """"maxDownloadBytes":"1"""",
        )) {
      assertEquals(
          OpenAiSettingsProblem.INVALID_LIMIT,
          problem("""{"baseUrl":"http://h",$bad}"""),
          bad,
      )
    }
  }

  @Test
  fun `request parameter defaults are the model and the sampling and length parameters`() {
    val s =
        valid(
            """{"baseUrl":"http://h","defaults":{"model":"m1","temperature":0.2,"max_tokens":100,"stop":["x"],"seed":7,"response_format":{"type":"json_object"}},
              "allowedModels":["m1","m2"],"lockedParameters":["temperature"],"maxValues":{"max_tokens":500}}"""
        )

    assertEquals("m1", s.defaults["model"]!!.jsonPrimitive.content)
    assertEquals(setOf("m1", "m2"), s.allowedModels)
    assertEquals(setOf("temperature"), s.lockedParameters)
    assertEquals(500.0, s.maxValues["max_tokens"])
  }

  @Test
  fun `a default that is not such a parameter, or breaks the administrator's own rules, is refused`() {
    for (bad in
        listOf(
            """"defaults":{"messages":[]}""",
            """"defaults":{"stream":true}""",
            """"defaults":{"tools":[]}""",
            """"defaults":{"input":"x"}""",
            """"defaults":"x"""",
            """"defaults":{"model":5}""",
            """"defaults":{"max_tokens":600},"maxValues":{"max_tokens":500}""",
            """"defaults":{"model":"other"},"allowedModels":["m1"]""",
            """"lockedParameters":["messages"]""",
            """"lockedParameters":"temperature"""",
            """"allowedModels":[5]""",
            """"allowedModels":"m1"""",
            """"maxValues":{"messages":5}""",
            """"maxValues":{"max_tokens":0}""",
            """"maxValues":{"max_tokens":"big"}""",
        )) {
      assertEquals(
          OpenAiSettingsProblem.INVALID_REQUEST_DEFAULTS,
          problem("""{"baseUrl":"http://h",$bad}"""),
          bad,
      )
    }
  }

  @Test
  fun `a setting of the wrong shape is refused`() {
    assertEquals(
        OpenAiSettingsProblem.INVALID_SETTINGS,
        problem("""{"baseUrl":"http://h","organization":""}"""),
    )
    assertTrue(
        parse("""{"baseUrl":"http://h","endpoints":["chat.completions"]}""") is SettingsResult.Valid
    )
    val s = valid("""{"baseUrl":"http://h","defaults":{"stop":["a","b"]}}""")
    assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), s.defaults["stop"])
    assertTrue(JsonObject(emptyMap()).isEmpty())
  }
}

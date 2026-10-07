package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Checking an `openai-compatible` resource (WI-46, WI-43): a real service (the Fake on a real
 * socket), a real keystore, the real check endpoint. A check connects and reads one light thing; it
 * takes no capacity, no share of requests and makes nothing to be generated.
 */
class OpenAiResourceCheckTest : ResourceApiSupport() {
  private val key = "sk-check-0123456789abcdef"
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val server = FakeOpenAiServer(requiredKey = key)

  @AfterTest fun stop() = server.close()

  private fun keystore(vararg secrets: Pair<String, String>): Path =
      keystores.pkcs12("check-${System.nanoTime()}.p12", mapOf(*secrets))

  private fun ApplicationTestBuilder.engineWith(file: Path?, seconds: Int = 5) =
      engine(
          "resources.checkTimeoutSeconds" to "$seconds",
          *(if (file == null) emptyArray()
          else
              arrayOf(
                  "secrets.keystorePath" to "$file",
                  "secrets.passwordFile" to "$passwordFile",
              )),
      )

  private suspend fun ApplicationTestBuilder.defineOpenAi(
      name: String = "lemon",
      base: String = server.baseUrl,
      extra: String = "",
      alias: String? = "lemon-key",
  ) =
      define(
          name,
          1,
          """"type":"openai-compatible","settings":{"baseUrl":"$base"$extra}""" +
              (alias?.let { ""","secretAlias":"$it"""" } ?: ""),
      )

  private suspend fun ApplicationTestBuilder.check(name: String = "lemon") =
      client.post("/api/v1/resources/$name/check") { bearer(TestTokens.ROOT)() }

  private suspend fun ApplicationTestBuilder.failureOf(name: String = "lemon"): String? {
    val response = check(name)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.json()["failure"]?.jsonPrimitive?.contentOrNull
  }

  @Test
  fun `a service that is there and takes the key passes, having been asked for its models`() =
      testApplication {
        engineWith(keystore("lemon-key" to key))
        defineOpenAi()

        val response = check()

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals(JsonPrimitive(true), body["ok"])
        assertEquals(setOf("ok", "failure", "checkedAt"), body.keys)
        val seen = server.requests.single()
        assertEquals("GET", seen.method)
        assertEquals("/v1/models", seen.path)
        assertEquals("Bearer $key", seen.header("authorization"))
        assertEquals(JsonPrimitive(true), resource("lemon").obj("lastCheck")["ok"])
        assertFalse(response.bodyAsText().contains(server.baseUrl))
      }

  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

  @Test
  fun `without the models endpoint a check only looks that the service answers and generates nothing`() =
      testApplication {
        engineWith(keystore("lemon-key" to key))
        defineOpenAi(extra = ""","endpoints":["chat.completions"]""")

        assertNull(failureOf())

        val seen = server.requests.single()
        assertEquals("GET", seen.method)
        assertTrue(seen.path == "/v1" || seen.path == "/v1/", seen.path)
        assertTrue(server.requests.none { it.method == "POST" })
      }

  @Test
  fun `a key the service does not accept is rejected`() = testApplication {
    engineWith(keystore("lemon-key" to "sk-wrong-0123456789"))
    defineOpenAi()

    assertEquals("rejected", failureOf())
  }

  @Test
  fun `an alias the keystore does not have is alias_missing and nothing is sent`() =
      testApplication {
        engineWith(keystore("another" to key))
        defineOpenAi()

        assertEquals("alias_missing", failureOf())
        assertEquals(0, server.requests.size)
      }

  @Test
  fun `without a keystore an alias is alias_missing and nothing is sent`() = testApplication {
    engineWith(null)
    defineOpenAi()

    assertEquals("alias_missing", failureOf())
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `an alias whose secret is not usable is alias_invalid and nothing is sent`() =
      testApplication {
        engineWith(keystore("lemon-key" to "café"))
        defineOpenAi()

        assertEquals("alias_invalid", failureOf())
        assertEquals(0, server.requests.size)
      }

  @Test
  fun `a service that needs no key is checked without one`() = testApplication {
    val open = FakeOpenAiServer()
    try {
      engineWith(null)
      defineOpenAi(base = open.baseUrl, alias = null)

      assertNull(failureOf())
      assertNull(open.requests.single().header("authorization"))
    } finally {
      open.close()
    }
  }

  @Test
  fun `a service that cannot be reached is connection_failed`() = testApplication {
    val closed = ServerSocket(0).use { it.localPort }
    engineWith(null)
    defineOpenAi(base = "http://127.0.0.1:$closed/v1", alias = null)

    assertEquals("connection_failed", failureOf())
  }

  @Test
  fun `a service that fails is server_error`() = testApplication {
    server.script = { _, response ->
      response.json(503, "{}")
      true
    }
    engineWith(keystore("lemon-key" to key))
    defineOpenAi()

    assertEquals("server_error", failureOf())
  }

  @Test
  fun `a service that is busy generating answers late and the check says timeout, within its own short limit`() =
      testApplication {
        server.responseDelayMillis = 4000
        engineWith(keystore("lemon-key" to key), seconds = 1)
        // The resource's own limits are long; the check does not use them.
        defineOpenAi(extra = ""","timeouts":{"firstByteMs":900000,"connectMs":900000}""")

        val start = System.nanoTime()
        val failure = failureOf()
        val took = (System.nanoTime() - start) / 1_000_000

        assertEquals("timeout", failure)
        assertTrue(took < 3500, "took $took ms")
      }

  @Test
  fun `a check takes no capacity of the resource and works on a disabled one`() = testApplication {
    engineWith(keystore("lemon-key" to key))
    defineOpenAi()
    change("lemon", """{"enabled":false}""")

    assertNull(failureOf())
    assertEquals(0, resource("lemon").array("holders").size)
  }

  @Test
  fun `no check puts the key, the address or the keystore in the answer`() = testApplication {
    val file = keystore("lemon-key" to key)
    engineWith(file)
    defineOpenAi()
    server.script = { _, response ->
      response.json(401, """{"error":{"message":"bad key $key"}}""", mapOf("X-Echo" to key))
      true
    }

    val response = check()

    val text = response.bodyAsText()
    assertEquals("rejected", response.json()["failure"]!!.jsonPrimitive.content)
    assertFalse(text.contains(key))
    assertFalse(text.contains(server.baseUrl))
    assertFalse(text.contains(file.toString()))
    assertTrue(Files.exists(file))
  }
}

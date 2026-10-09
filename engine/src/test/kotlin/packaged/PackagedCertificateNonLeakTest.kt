package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.jdbc.TlsPostgres
import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.PackagedEngine
import dev.lawlan.runline.engine.support.PostgresTestContainer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * The certificates of the keystore verified as a whole on the packaged Engine (WI-51, ADR-019
 * decision 12): a real keystore with a trusted certificate, a private key and a secret, made with
 * keytool; the Fake OpenAI compatible service behind real TLS that requires a client certificate,
 * and a real PostgreSQL that speaks TLS. The private key, anything made from it and the keystore
 * password are on no surface; a certificate is shown by alias, subject, expiry and SHA-256
 * fingerprint only; no setting, connection property or JVM flag turns off the verification of the
 * host name; and a pipeline gets nothing of the key or the security context through the contract.
 */
class PackagedCertificateNonLeakTest {
  private val work: Path = TestDirectories.forThisTest("packaged-certificates")
  private val engine = PackagedEngine(work)
  private val keystores = Keystores(Files.createDirectories(work.resolve("keystore")))
  private val pki = TestPki(Files.createDirectories(work.resolve("pki")))
  private val authority = pki.authority("Internal CA")
  private val serviceIdentity = pki.issue(authority, "localhost", listOf("dns:localhost"))
  private val client = pki.issue(authority, "runline-client")
  private val apiKey = "sk-cert-marker-${UUID.randomUUID().toString().take(12)}"
  private val dbPassword = "dbpw-cert-marker-${UUID.randomUUID().toString().take(12)}"
  private val storePassword = "storepass-cert-marker-${UUID.randomUUID().toString().take(12)}"
  private val closeable = mutableListOf<AutoCloseable>()

  /**
   * The private key as text in the forms it could be said in: base64 (whole, as PEM lines) and hex.
   */
  private val keyMarkers: List<String> =
      client.key.encoded.let { der ->
        val base64 = Base64.getEncoder().encodeToString(der)
        listOf(base64.take(40), HexFormat.of().formatHex(der).take(40)) + base64.chunked(64)
      }

  private val markers = keyMarkers + listOf(apiKey, dbPassword, storePassword)

  @AfterTest
  fun close() {
    engine.close()
    closeable.reversed().forEach { runCatching { it.close() } }
  }

  private fun define(name: String, type: String, settings: String, alias: String? = null) =
      engine.call(
          "POST",
          "/api/v1/resources",
          body =
              """{"name":"$name","capacity":1,"type":"$type",""" +
                  (alias?.let { """"secretAlias":"$it",""" } ?: "") +
                  """"settings":$settings}""",
      )

  private fun check(name: String): JsonObject =
      engine.expect(200, "POST", "/api/v1/resources/$name/check")

  private fun fingerprint(der: ByteArray) =
      MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

  @Test
  fun `no private key, keystore password or secret is on any surface and host names are verified on every path`() {
    val service =
        FakeOpenAiServer(
                requiredKey = apiKey,
                tls = pki.serverContext(serviceIdentity, clientsOf = listOf(authority.certificate)),
                requireClientCertificate = true,
            )
            .also { closeable += it }
    val postgres =
        TlsPostgres(
                pki,
                pki.issue(authority, "localhost", listOf("dns:localhost")),
                listOf(authority.certificate),
            )
            .also { closeable += it }
    val role = "tls_" + UUID.randomUUID().toString().replace("-", "").take(10)
    postgres.createRole(role, dbPassword)
    val passwordFile = keystores.passwordFile(storePassword, "engine.pw")
    val keystore =
        keystores.pkcs12(
            "engine.p12",
            mapOf("api-key" to apiKey, "db-pw" to dbPassword),
            storePassword,
        )
    pki.addTrusted(keystore, "internal-ca", authority, passwordFile)
    pki.addPrivateKey(keystore, "app-client", client, passwordFile)
    // The JVM's default trust knows the authority; first the JDK's own switch that turns off the
    // verification of host names in its HTTP client is on as well: neither may make a wrong host
    // pass.
    val trustPassword = keystores.passwordFile("trust-pass-1", "trust.pw")
    val trust = keystores.pkcs12("trust.p12", emptyMap(), "trust-pass-1")
    pki.addTrusted(trust, "internal-ca", authority, trustPassword)
    engine.migrate()
    val settings =
        mapOf(
            "RUNLINE_KEYSTORE_PATH" to keystore.toString(),
            "RUNLINE_KEYSTORE_PASSWORD_FILE" to passwordFile.toString(),
        )
    val defaultTrustKnowsTheAuthority =
        System.getenv("JAVA_TOOL_OPTIONS").orEmpty() +
            " -Djavax.net.ssl.trustStore=$trust -Djavax.net.ssl.trustStoreType=PKCS12" +
            " -Djavax.net.ssl.trustStorePassword=trust-pass-1"
    engine.start(
        settings +
            ("JAVA_TOOL_OPTIONS" to
                defaultTrustKnowsTheAuthority +
                    " -Djdk.internal.httpclient.disableHostnameVerification=true")
    )
    assertTrue(engine.engineOutput().contains("disableHostnameVerification=true"))

    fun openAi(host: String, tls: String) =
        """{"baseUrl":"${service.baseUrl(host)}","endpoints":["chat.completions","models.list"]$tls}"""
    val pinned = ""","trustAliases":["internal-ca"],"clientCertAlias":"app-client""""
    val defaultTrust = ""","clientCertAlias":"app-client""""
    for ((name, settings) in
        listOf(
            "llm" to openAi("localhost", pinned),
            "llm-by-number" to openAi("127.0.0.1", pinned),
            "llm-default" to openAi("localhost", defaultTrust),
            "llm-default-by-number" to openAi("127.0.0.1", defaultTrust),
        )) {
      assertEquals(201, define(name, "openai-compatible", settings, "api-key").statusCode())
    }
    fun jdbc(host: String) =
        """{"kind":"postgresql","host":"$host","port":${postgres.port},""" +
            """"database":"${postgres.database}","username":"$role",""" +
            """"trustAliases":["internal-ca"],"clientCertAlias":"app-client"}"""
    assertEquals(201, define("db", "jdbc-pool", jdbc("localhost"), "db-pw").statusCode())
    assertEquals(201, define("db-by-number", "jdbc-pool", jdbc("127.0.0.1"), "db-pw").statusCode())

    // No setting turns the verification off.
    val refused =
        listOf(
            define(
                "off-1",
                "openai-compatible",
                openAi("127.0.0.1", ""","verifyHostname":false"""),
                "api-key",
            ),
            define(
                "off-2",
                "openai-compatible",
                openAi("127.0.0.1", ""","insecure":true"""),
                "api-key",
            ),
            define(
                "off-3",
                "jdbc-pool",
                jdbc("127.0.0.1").dropLast(1) + ""","properties":{"sslmode":"require"}}""",
                "db-pw",
            ),
            define(
                "off-4",
                "jdbc-pool",
                jdbc("127.0.0.1").dropLast(1) +
                    ""","properties":{"sslhostnameverifier":"org.postgresql.ssl.NonValidatingFactory"}}""",
                "db-pw",
            ),
        )
    assertEquals(
        listOf(422, 422, 422, 422),
        refused.map { it.statusCode() },
        "${refused.map { it.body() }}",
    )
    assertEquals(
        listOf(
            "invalid_settings",
            "invalid_settings",
            "property_not_allowed",
            "property_not_allowed",
        ),
        refused.map { engine.json(it)["problem"]!!.jsonPrimitive.content },
    )

    // With the JDK's switch on, the HTTP client asks for no verification of the host name, and the
    // Engine's trust refuses every such connection (WI-52): it fails, it never passes unverified.
    for (name in listOf("llm", "llm-default", "llm-by-number", "llm-default-by-number")) {
      assertEquals("hostname_mismatch", check(name)["failure"]!!.jsonPrimitive.content, name)
    }
    // PostgreSQL does not use that client: the right host passes, the wrong one does not.
    assertEquals(JsonPrimitive(true), check("db")["ok"])
    assertEquals("hostname_mismatch", check("db-by-number")["failure"]!!.jsonPrimitive.content)

    // Without the switch, every path verifies the host name: pinned trust and the JVM's default.
    engine.stop()
    engine.start(settings + ("JAVA_TOOL_OPTIONS" to defaultTrustKnowsTheAuthority))
    assertEquals(JsonPrimitive(true), check("llm")["ok"])
    assertEquals(JsonPrimitive(true), check("llm-default")["ok"])
    for (name in listOf("llm-by-number", "llm-default-by-number", "db-by-number")) {
      assertEquals("hostname_mismatch", check(name)["failure"]!!.jsonPrimitive.content, name)
    }
    val run =
        engine.runToEnd(
            "tls-calls",
            PackagedEngine.typed(
                "llm" to "openai-compatible",
                "llm-by-number" to "openai-compatible",
                "llm-default-by-number" to "openai-compatible",
                "db" to "jdbc-pool",
                "db-by-number" to "jdbc-pool",
            ),
            """
            StringBuilder found = new StringBuilder();
            for (String name : new String[] {"llm", "llm-by-number", "llm-default-by-number"}) {
              try {
                OpenAiAccessor llm = context.getAccessors().openAiCompatible(name);
                OpenAiResponse r = llm.call(new OpenAiRequest("chat.completions", "{\"model\":\"m\"}"));
                found.append(name).append(" ").append(r.getStatus()).append('\n');
                // Everything the contract returns, by every method that takes nothing.
                for (Object[] pair : new Object[][] {{OpenAiAccessor.class, llm}, {OpenAiResponse.class, r}}) {
                  found.append(pair[1]).append('\n');
                  for (java.lang.reflect.Method m : ((Class<?>) pair[0]).getMethods()) {
                    if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
                    found.append(m.getName()).append(" = ").append(m.invoke(pair[1])).append('\n');
                  }
                }
              } catch (ResourceAccessException e) {
                found.append(name).append(" ").append(e.getFailure()).append('\n');
              } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            }
            for (String name : new String[] {"db", "db-by-number"}) {
              try {
                JdbcRows rows = context.getAccessors().jdbcPool(name).query("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()");
                found.append(name).append(" ").append(rows.getRows()).append(' ').append(rows).append('\n');
              } catch (ResourceAccessException e) {
                found.append(name).append(" ").append(e.getFailure()).append('\n');
              }
            }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "found", found.toString());
            """
                .trimIndent(),
        )
    assertEquals("SUCCEEDED", run["state"]!!.jsonPrimitive.content, "$run")
    val found = Files.readString(engine.shared("tls-calls", "found"))
    val outcome = found.lines().filter { it.matches(Regex("^(llm|db)[a-z-]* .*")) }
    assertTrue("llm 200" in outcome, found)
    assertTrue("llm-by-number CONNECTION_FAILED" in outcome, found)
    assertTrue("llm-default-by-number CONNECTION_FAILED" in outcome, found)
    assertTrue(outcome.any { it.startsWith("db [[true]]") }, found)
    assertTrue("db-by-number CONNECTION_FAILED" in outcome, found)
    assertTrue(
        service.requests.isNotEmpty() && service.requests.all { it.clientCertificate != null }
    )
    // The pipeline got nothing of the key or the security context.
    for (marker in markers) assertFalse(found.contains(marker), "$marker is in:\n$found")
    for (type in listOf("SSLContext", "PrivateKey", "KeyStore", "KeyManager", "BEGIN")) {
      assertFalse(found.contains(type), "$type is in:\n$found")
    }

    // A certificate is said by alias, subject, expiry and fingerprint, and nothing else.
    engine.expect(200, "POST", "/api/v1/secrets/reload")
    val secrets =
        engine.expect(200, "GET", "/api/v1/secrets")["secrets"]!!.jsonArray.map { it.jsonObject }
    val own = secrets.single { it["alias"]!!.jsonPrimitive.content == "app-client" }
    val shown = own["certificates"]!!.jsonArray.map { it.jsonObject }
    assertEquals(
        client.chain.map { fingerprint(it.encoded) },
        shown.map { it["fingerprint"]!!.jsonPrimitive.content },
    )
    for (certificate in shown) {
      assertEquals(
          setOf("subject", "notAfter", "daysLeft", "fingerprint", "expiry"),
          certificate.keys,
      )
    }
    assertEquals(setOf("alias", "type", "status", "usedBy", "certificates"), own.keys)
    for (name in listOf("llm", "db")) engine.call("GET", "/api/v1/resources/$name")
    engine.call("GET", "/api/v1/resources")
    engine.call("GET", "/api/v1/runs/${run["runId"]!!.jsonPrimitive.content}/log")
    engine.stop()

    val surfaces =
        mapOf(
            "the answers of the API" to engine.exchanges.joinToString("\n"),
            "the Engine's output" to engine.engineOutput(),
            "the dump of the Engine's database" to PostgresTestContainer.dump(engine.database),
        )
    for ((surface, text) in surfaces) {
      for (marker in markers) assertFalse(text.contains(marker), "$marker is in $surface")
    }
    assertTrue(
        surfaces
            .getValue("the answers of the API")
            .contains(fingerprint(client.certificate.encoded))
    )
  }
}

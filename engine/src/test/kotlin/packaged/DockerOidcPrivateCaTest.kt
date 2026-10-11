package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.engine.support.ComposeProject
import dev.lawlan.runline.engine.support.Keytool
import dev.lawlan.runline.engine.support.ProviderBrowser
import dev.lawlan.runline.engine.support.TestTimeouts
import dev.lawlan.runline.engine.support.TimedHttp
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Signing in as Docker deploys it (WI-1203): the real `deploy/docker/compose.yaml` with its
 * `compose.oidc.yaml` and `compose.oidc-ca.yaml`, the packaged Engine in a container, a real
 * PostgreSQL, and a real Keycloak serving https with a certificate from a certificate authority
 * made for the test (the Engine's JVM knows nothing of it). Without the overlay that trusts the
 * authority the sign-in cannot start; with the truststore the manual says how to make, a person
 * signs in and has the role of their group. The client secret and the session key reach the Engine
 * as Docker secrets. Needs Docker; without it the test fails, it is not skipped.
 */
class DockerOidcPrivateCaTest {
  private val repo = Path.of(System.getProperty("runline.repoRoot"))

  private val work: Path =
      Files.createTempDirectory(
          Files.createDirectories(repo.resolve("engine/build/docker-oidc")),
          "run",
      )
  private val project = "wi1203" + System.nanoTime().toString(36)
  private val enginePort = ServerSocket(0).use { it.localPort }
  private val providerPort = ServerSocket(0).use { it.localPort }
  private val http = TimedHttp()

  // What the realm of the tests registers (runline-realm.json), and what the Engine is given.
  private val publicBaseUrl = "http://localhost:18080"
  private val issuer = "https://keycloak:8443/realms/runline"
  private val clientSecret = "client-secret-for-tests"
  private val sessionKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
  private val username = "grace"
  private val password = "pw-grace-for-the-ca-test"
  private val storePassword = "changeit"

  private fun deployFile(name: String) = repo.resolve("deploy/docker/$name")

  private val certs = Files.createDirectories(work.resolve("certs"))
  private val trustedCa = Files.createDirectories(work.resolve("trusted-ca"))
  private val truststoreDir = Files.createDirectories(work.resolve("truststore"))
  private val secrets = Files.createDirectories(work.resolve("secrets"))

  /** A CA that exists for this test only, and a certificate of it for the provider's name. */
  private fun makeAuthority() {
    val ca = certs.resolve("ca.p12").toString()
    val server = certs.resolve("server.p12").toString()
    fun keytool(vararg args: String, stdin: String = "") =
        Keytool.run(*args, "-storepass", storePassword, "-keypass", storePassword, stdin = stdin)
    keytool(
        "-genkeypair",
        "-alias",
        "ca",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-dname",
        "CN=Runline Test Private CA",
        "-ext",
        "bc:c",
        "-validity",
        "2",
        "-keystore",
        ca,
        "-storetype",
        "PKCS12",
    )
    keytool(
        "-exportcert",
        "-rfc",
        "-alias",
        "ca",
        "-keystore",
        ca,
        "-file",
        trustedCa.resolve("ca.pem").toString(),
    )
    keytool(
        "-genkeypair",
        "-alias",
        "server",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-dname",
        "CN=keycloak",
        "-validity",
        "2",
        "-keystore",
        server,
        "-storetype",
        "PKCS12",
    )
    val request = certs.resolve("server.csr").toString()
    keytool("-certreq", "-alias", "server", "-keystore", server, "-file", request)
    val signed = certs.resolve("server.pem").toString()
    keytool(
        "-gencert",
        "-alias",
        "ca",
        "-keystore",
        ca,
        "-infile",
        request,
        "-outfile",
        signed,
        "-rfc",
        "-ext",
        "san=dns:keycloak,ip:127.0.0.1",
        "-ext",
        "eku=serverAuth",
        "-validity",
        "2",
    )
    keytool(
        "-importcert",
        "-noprompt",
        "-alias",
        "ca",
        "-keystore",
        server,
        "-file",
        trustedCa.resolve("ca.pem").toString(),
    )
    keytool("-importcert", "-noprompt", "-alias", "server", "-keystore", server, "-file", signed)
    // Keycloak runs as another account in its container.
    certs.toFile().walk().forEach { it.setReadable(true, false) }
  }

  /** The realm of the other tests, with one more person: a member of the admin group. */
  private fun writeRealm(): Path {
    val realm =
        Json.parseToJsonElement(
                checkNotNull(javaClass.getResourceAsStream("/oidc/runline-realm.json")).use {
                  it.readBytes().decodeToString()
                }
            )
            .jsonObject
    val person = buildJsonObject {
      put("username", username)
      put("enabled", true)
      put("email", "$username@example.test")
      put("emailVerified", true)
      put("firstName", "Grace")
      put("lastName", "Hopper")
      put(
          "credentials",
          buildJsonArray {
            add(
                buildJsonObject {
                  put("type", "password")
                  put("value", password)
                  put("temporary", false)
                }
            )
          },
      )
      put("groups", buildJsonArray { add("/runline-admin") })
      // A person made in the import has no role until given one; this one carries offline_access.
      put("realmRoles", buildJsonArray { add("default-roles-runline") })
    }
    val file = work.resolve("realm.json")
    Files.writeString(
        file,
        JsonObject(realm + ("users" to buildJsonArray { add(person) })).toString(),
    )
    file.toFile().setReadable(true, false)
    return file
  }

  private fun composeProject(vararg overlays: String): ComposeProject {
    Files.writeString(
        work.resolve("env"),
        """
        POSTGRES_DB=runline
        POSTGRES_USER=runline
        POSTGRES_PASSWORD=oidc-test-db-password
        POSTGRES_URL=jdbc:postgresql://postgres:5432/runline
        API_TOKENS=ops:admin:tok-admin-for-the-oidc-test
        RUNLINE_MAX_CONCURRENT_RUNS=2
        RUNLINE_WORKSPACE_MAX_BYTES=1073741824
        RUNLINE_FAILED_RUN_RETENTION_SECONDS=3600
        RUNLINE_SHUTDOWN_GRACE_SECONDS=5
        OTEL_TRACES_EXPORTER=none
        OTEL_LOGS_EXPORTER=none
        OTEL_METRICS_EXPORTER=none
        RUNLINE_OIDC_ISSUER=$issuer
        RUNLINE_OIDC_CLIENT_ID=runline
        RUNLINE_PUBLIC_BASE_URL=$publicBaseUrl
        OIDC_CLIENT_SECRET_HOST_FILE=${secrets.resolve("oidc-client-secret")}
        SESSION_ENCRYPTION_KEY_HOST_FILE=${secrets.resolve("session-encryption-key")}
        TRUSTSTORE_HOST_DIR=$truststoreDir
        RUNLINE_IMAGE_TAG=$project
        """
            .trimIndent(),
    )
    val override = work.resolve("override.yaml")
    Files.writeString(
        override,
        """
        services:
          migrate:
            env_file: !override
              - ${work.resolve("env")}
          engine:
            env_file: !override
              - ${work.resolve("env")}
            ports: !override
              - "127.0.0.1:$enginePort:8080"
          keycloak:
            image: quay.io/keycloak/keycloak:26.4
            command:
              - start-dev
              - --import-realm
              - --hostname=https://keycloak:8443
              - --https-key-store-file=/certs/server.p12
              - --https-key-store-password=$storePassword
            environment:
              KC_BOOTSTRAP_ADMIN_USERNAME: admin
              KC_BOOTSTRAP_ADMIN_PASSWORD: admin
            volumes:
              - ${certs}:/certs:ro
              - ${work.resolve("realm.json")}:/opt/keycloak/data/import/runline-realm.json:ro
            ports:
              - "127.0.0.1:$providerPort:8443"
        """
            .trimIndent() + "\n",
    )
    return ComposeProject(
        work,
        project,
        work.resolve("env"),
        (listOf("compose.yaml") + overlays).map(::deployFile) + listOf(override),
    )
  }

  private lateinit var stack: ComposeProject

  @AfterTest
  fun tearDown() {
    if (this::stack.isInitialized) {
      stack.tearDown()
      stack.docker("image", "rm", "-f", "runline-engine:$project", timeoutMinutes = 3)
    }
    work.toFile().deleteRecursively()
  }

  // The browser of the test trusts the authority, as the browser of a person on the network would,
  // and reaches the name that only the Compose network knows through the published port.
  private fun browser(): ProviderBrowser {
    val context = trustingContext()
    return ProviderBrowser(publicBaseUrl, context) { uri ->
      if (uri.host == "keycloak")
          URI(uri.toString().replace("keycloak:8443", "127.0.0.1:$providerPort"))
      else uri
    }
  }

  private val noRedirects: HttpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  private fun engine(path: String, cookie: String? = null) =
      noRedirects.send(
          HttpRequest.newBuilder(URI("http://127.0.0.1:$enginePort$path"))
              .GET()
              .also { b -> cookie?.let { b.header("Cookie", it) } }
              .build(),
          java.net.http.HttpResponse.BodyHandlers.ofString(),
      )

  @Test
  fun `a provider with a private CA is reached only with the truststore, and then a person signs in`() {
    makeAuthority()
    writeRealm()
    Files.writeString(secrets.resolve("oidc-client-secret"), "$clientSecret\n")
    Files.writeString(secrets.resolve("session-encryption-key"), "$sessionKey\n")
    secrets.toFile().walk().forEach { it.setReadable(true, false) }

    // The JVM knows only the CAs of the JDK: the provider is not usable, and the person is sent
    // back
    // to the sign-in page of the Console with the code for it.
    stack = composeProject("compose.oidc.yaml")
    stack.compose("up", "--build", "-d", "--wait", "--wait-timeout", "300")
    awaitProvider()
    val untrusted = engine("/api/v1/auth/oidc/login")
    assertEquals(302, untrusted.statusCode())
    assertEquals(
        "/?login_error=provider_unavailable",
        untrusted.headers().firstValue("Location").get(),
    )

    // The truststore as deploy/authentik.md makes it: the script of deploy/bin, run in the image.
    val made =
        stack.docker(
            "run",
            "--rm",
            "--user",
            "0:0",
            "-v",
            "${repo.resolve("deploy/bin")}:/deploy-bin:ro",
            "-v",
            "$trustedCa:/ca:ro",
            "-v",
            "$truststoreDir:/out",
            "--entrypoint",
            "/deploy-bin/runline-build-truststore",
            "runline-engine:$project",
            "/ca",
            "/out/truststore.p12",
        )
    assertEquals(0, made.exitCode, made.output)
    truststoreDir.toFile().walk().forEach { it.setReadable(true, false) }

    stack = composeProject("compose.oidc.yaml", "compose.oidc-ca.yaml")
    stack.compose("up", "-d", "--wait", "--wait-timeout", "300", "engine")

    val start = engine("/api/v1/auth/oidc/login?return=/pipelines")
    assertEquals(302, start.statusCode())
    val authorization = URI(start.headers().firstValue("Location").get())
    assertTrue(
        authorization.toString().startsWith("$issuer/protocol/openid-connect/auth"),
        authorization.toString(),
    )

    val back = browser().signIn(authorization, username, password)
    val callback = engine("${back.rawPath}?${back.rawQuery}")
    assertEquals(302, callback.statusCode(), callback.body())
    assertEquals(
        "/pipelines",
        callback.headers().firstValue("Location").get(),
        stack.compose("logs", "--no-color", "engine", "keycloak"),
    )
    val cookie =
        callback.headers().allValues("Set-Cookie").single { it.startsWith("runline_session=") }
    assertFalse("Secure" in cookie, "an http public address does not make a Secure cookie: $cookie")

    val session = engine("/api/v1/auth/session", cookie.substringBefore(';'))
    assertEquals(200, session.statusCode(), session.body())
    val body = Json.parseToJsonElement(session.body()).jsonObject
    assertEquals(username, body["name"]!!.jsonPrimitive.content)
    assertEquals("admin", body["role"]!!.jsonPrimitive.content)

    // The secret and the key came as files: neither is in the container's environment or its log.
    val container = stack.containerId("engine")
    val environment = stack.docker("inspect", "-f", "{{json .Config.Env}}", container).output
    assertFalse(clientSecret in environment, "the environment holds the client secret")
    assertFalse(sessionKey in environment, "the environment holds the session key")
    val logs = stack.compose("logs", "--no-color", "engine")
    assertContains(logs, "Signed in $username as")
    assertFalse(clientSecret in logs, "the log holds the client secret")
    assertFalse(sessionKey in logs, "the log holds the session key")
  }

  /** Waits for the provider to answer its discovery document over https, through the browser. */
  private fun awaitProvider() {
    val deadline = System.nanoTime() + TestTimeouts.container.toNanos()
    while (System.nanoTime() < deadline) {
      val up = runCatching {
        val client = HttpClient.newBuilder().sslContext(trustingContext()).build()
        client
            .send(
                HttpRequest.newBuilder(
                        URI(
                            "https://127.0.0.1:$providerPort/realms/runline/.well-known/openid-configuration"
                        )
                    )
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            .statusCode() == 200
      }
          .getOrDefault(false)
      if (up) return
      Thread.sleep(2000)
    }
    fail(
        "the provider did not answer within ${TestTimeouts.container}:\n${stack.compose("logs", "--no-color", "keycloak")}"
    )
  }

  private fun trustingContext(): SSLContext {
    val authority =
        trustedCa.resolve("ca.pem").toFile().inputStream().use {
          CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
    val store =
        KeyStore.getInstance("PKCS12").apply {
          load(null)
          setCertificateEntry("ca", authority)
        }
    return SSLContext.getInstance("TLS").apply {
      init(null, TrustManagerFactory.getInstance("PKIX").apply { init(store) }.trustManagers, null)
    }
  }
}

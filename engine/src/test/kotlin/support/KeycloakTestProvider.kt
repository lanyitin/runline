package dev.lawlan.runline.engine.support

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

/** A person made at the provider for a test. */
data class ProviderUser(val id: String, val username: String, val password: String) {
  val displayName: String
    get() = "First $username"
}

/**
 * A real Keycloak, the OpenID Connect provider the Engine's login is tested against (WI-1200). It
 * is a standard provider with a realm of the test's own (`oidc/runline-realm.json`): one
 * confidential client that requires PKCE, the groups the Engine maps to roles, and a groups claim
 * in the ID token. One instance serves every test of the JVM, like the database; each test makes
 * the people it needs (names are unique), through the provider's own administration API.
 */
object KeycloakTestProvider {
  const val REALM = "runline"
  const val CLIENT_ID = "runline"
  const val CLIENT_SECRET = "client-secret-for-tests"

  /** Registered with the client; nothing listens there, a test only reads where it is sent. */
  const val PUBLIC_BASE_URL = "http://localhost:18080"

  private val container: GenericContainer<*> by lazy {
    GenericContainer<Nothing>("quay.io/keycloak/keycloak:26.4").apply {
      withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
      withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
      withCopyFileToContainer(
          MountableFile.forClasspathResource("oidc/runline-realm.json"),
          "/opt/keycloak/data/import/runline-realm.json",
      )
      withCommand("start-dev", "--import-realm")
      withExposedPorts(8080)
      waitingFor(
          Wait.forHttp("/realms/$REALM/.well-known/openid-configuration")
              .forPort(8080)
              .withStartupTimeout(Duration.ofMinutes(4))
      )
      start()
    }
  }

  private val base: String
    get() = "http://${container.host}:${container.getMappedPort(8080)}"

  /** The issuer of the realm, as the Engine is given it. */
  val issuer: String
    get() = "$base/realms/$REALM"

  private val http: HttpClient = HttpClient.newHttpClient()

  private fun form(vararg pairs: Pair<String, String>) =
      pairs.joinToString("&") {
        URLEncoder.encode(it.first, Charsets.UTF_8) +
            "=" +
            URLEncoder.encode(it.second, Charsets.UTF_8)
      }

  private fun adminToken(): String {
    val response =
        http.send(
            HttpRequest.newBuilder(URI("$base/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form(
                            "grant_type" to "password",
                            "client_id" to "admin-cli",
                            "username" to "admin",
                            "password" to "admin",
                        )
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    check(response.statusCode() == 200) { "admin token: ${response.statusCode()}" }
    return Json.parseToJsonElement(response.body())
        .jsonObject
        .getValue("access_token")
        .jsonPrimitive
        .content
  }

  private fun admin(
      method: String,
      path: String,
      body: JsonObject? = null,
  ): HttpResponse<String> =
      http.send(
          HttpRequest.newBuilder(URI("$base/admin/realms/$REALM$path"))
              .header("Authorization", "Bearer ${adminToken()}")
              .header("Content-Type", "application/json")
              .method(
                  method,
                  body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) }
                      ?: HttpRequest.BodyPublishers.noBody(),
              )
              .build(),
          HttpResponse.BodyHandlers.ofString(),
      )

  private fun groupId(name: String): String =
      Json.parseToJsonElement(admin("GET", "/groups?search=$name&exact=true").body())
          .jsonArray
          .first { it.jsonObject.getValue("name").jsonPrimitive.content == name }
          .jsonObject
          .getValue("id")
          .jsonPrimitive
          .content

  /**
   * A real ID token of the realm, signed with its keys, for [user]: it is for the client
   * `runline-direct` (not the Engine's) and lives three seconds. The password grant of that client
   * is the one way to have the provider mint a token without a browser.
   */
  fun idTokenOfAnotherClient(user: ProviderUser): String {
    val response =
        http.send(
            HttpRequest.newBuilder(URI("$issuer/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form(
                            "grant_type" to "password",
                            "client_id" to "runline-direct",
                            "client_secret" to "direct-secret-for-tests",
                            "scope" to "openid",
                            "username" to user.username,
                            "password" to user.password,
                        )
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    check(response.statusCode() == 200) { "token: ${response.statusCode()} ${response.body()}" }
    return Json.parseToJsonElement(response.body())
        .jsonObject
        .getValue("id_token")
        .jsonPrimitive
        .content
  }

  /** A person with a password who is a member of each of [groups]. */
  fun createUser(
      groups: List<String>,
      username: String = "u" + UUID.randomUUID().toString().replace("-", "").take(12),
      password: String = "pw-$username",
  ): ProviderUser {
    val response =
        admin(
            "POST",
            "/users",
            buildJsonObject {
              put("username", username)
              put("enabled", true)
              put("email", "$username.${UUID.randomUUID()}@example.test")
              put("emailVerified", true)
              put("firstName", "First")
              put("lastName", username)
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
            },
        )
    check(response.statusCode() == 201) {
      "create user: ${response.statusCode()} ${response.body()}"
    }
    val id = response.headers().firstValue("Location").get().substringAfterLast('/')
    groups.forEach { addToGroup(id, it) }
    return ProviderUser(id, username, password)
  }

  fun addToGroup(user: ProviderUser, group: String) = addToGroup(user.id, group)

  private fun addToGroup(userId: String, group: String) {
    val response = admin("PUT", "/users/$userId/groups/${groupId(group)}")
    check(response.statusCode() == 204) { "add to group: ${response.statusCode()}" }
  }

  fun removeFromGroup(user: ProviderUser, group: String) {
    val response = admin("DELETE", "/users/${user.id}/groups/${groupId(group)}")
    check(response.statusCode() == 204) { "remove from group: ${response.statusCode()}" }
  }

  /** Disables the person at the provider, as the provider's administrator does. */
  fun disable(user: ProviderUser) {
    val current = Json.parseToJsonElement(admin("GET", "/users/${user.id}").body()).jsonObject
    val response =
        admin(
            "PUT",
            "/users/${user.id}",
            JsonObject(current + ("enabled" to kotlinx.serialization.json.JsonPrimitive(false))),
        )
    check(response.statusCode() == 204) { "disable: ${response.statusCode()}" }
  }

  /**
   * Ends every session of the person at the provider, the offline ones too (the Engine asks for
   * `offline_access`), which revokes their refresh tokens.
   */
  fun logout(user: ProviderUser) {
    val response = admin("POST", "/users/${user.id}/logout")
    check(response.statusCode() == 204) { "logout: ${response.statusCode()}" }
    val client =
        Json.parseToJsonElement(admin("GET", "/clients?clientId=$CLIENT_ID").body())
            .jsonArray
            .first()
            .jsonObject
            .getValue("id")
            .jsonPrimitive
            .content
    val offline =
        Json.parseToJsonElement(admin("GET", "/users/${user.id}/offline-sessions/$client").body())
            .jsonArray
    for (session in offline) {
      val id = session.jsonObject.getValue("id").jsonPrimitive.content
      val gone = admin("DELETE", "/sessions/$id?isOffline=true")
      check(gone.statusCode() == 204) { "offline session: ${gone.statusCode()}" }
    }
  }

  /**
   * Makes the provider rotate refresh tokens (each one can be used once) or stop; the setting is
   * the realm's, so a test that sets it puts it back.
   */
  fun rotateRefreshTokens(on: Boolean) {
    val current = Json.parseToJsonElement(admin("GET", "").body()).jsonObject
    val changed =
        JsonObject(
            current +
                ("revokeRefreshToken" to kotlinx.serialization.json.JsonPrimitive(on)) +
                ("refreshTokenMaxReuse" to kotlinx.serialization.json.JsonPrimitive(0))
        )
    val response = admin("PUT", "", changed)
    check(response.statusCode() == 204) { "realm: ${response.statusCode()} ${response.body()}" }
  }

  /** Renames the person at the provider; the subject stays. */
  fun rename(user: ProviderUser, newUsername: String, firstName: String? = null): ProviderUser {
    val current = Json.parseToJsonElement(admin("GET", "/users/${user.id}").body()).jsonObject
    val changed =
        JsonObject(
            current +
                ("username" to kotlinx.serialization.json.JsonPrimitive(newUsername)) +
                (firstName?.let { "firstName" to kotlinx.serialization.json.JsonPrimitive(it) }
                    ?: ("username" to kotlinx.serialization.json.JsonPrimitive(newUsername)))
        )
    val response = admin("PUT", "/users/${user.id}", changed)
    check(response.statusCode() == 204) { "rename: ${response.statusCode()} ${response.body()}" }
    return user.copy(username = newUsername)
  }

  /** The id the provider gives a person in tokens: the subject. */
  fun subjectOf(user: ProviderUser): String = user.id
}

/**
 * A person at a browser: follows the provider's redirects and fills in its sign-in form, and stops
 * where the provider sends the person back to the Engine, which is what a browser would be handed
 * next. This is the protocol with a real provider, not a replacement of it.
 */
class ProviderBrowser(
    private val callbackBase: String = KeycloakTestProvider.PUBLIC_BASE_URL,
    /** What the browser trusts, for a provider over https with a CA of its own (WI-1203). */
    sslContext: javax.net.ssl.SSLContext? = null,
    /** Where an address of the provider is really reached (a name only its network knows). */
    private val reach: (URI) -> URI = { it },
) {
  private val http: HttpClient =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .apply { sslContext?.let { sslContext(it) } }
          .build()

  // A browser sends the provider's `Secure` cookies to http://localhost, which counts as a secure
  // context; the cookie manager of the JDK does not, so the jar is kept here.
  private val cookies = linkedMapOf<String, String>()

  private fun send(request: HttpRequest.Builder): HttpResponse<String> {
    if (cookies.isNotEmpty()) {
      request.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }
    val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    for (header in response.headers().allValues("Set-Cookie")) {
      val (name, value) = header.substringBefore(';').split('=', limit = 2)
      if (value.isEmpty()) cookies.remove(name) else cookies[name] = value
    }
    return response
  }

  private fun get(uri: URI): HttpResponse<String> = send(HttpRequest.newBuilder(reach(uri)).GET())

  /**
   * Opens [authorizationUri], signs in as [username], and returns the address the provider sends
   * the person back to (the Engine's callback with a code and the state, or with an error).
   */
  fun signIn(authorizationUri: URI, username: String, password: String): URI {
    var response = get(authorizationUri)
    var location = authorizationUri
    var hops = 0
    while (response.statusCode() in 300..399) {
      check(++hops < 20) { "too many redirects" }
      location = location.resolve(response.headers().firstValue("Location").get())
      if (isCallback(location)) return location
      response = get(location)
    }
    check(response.statusCode() == 200) { "sign-in page: ${response.statusCode()}" }
    val form =
        Regex("<form[^>]*id=\"kc-form-login\"[^>]*>").find(response.body())?.value
            ?: error("no sign-in form on the page of the provider")
    val action = Regex("action=\"([^\"]+)\"").find(form)!!.groupValues[1].replace("&amp;", "&")
    var next: HttpResponse<String> =
        send(
            HttpRequest.newBuilder(reach(location.resolve(action)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "username=${URLEncoder.encode(username, Charsets.UTF_8)}" +
                            "&password=${URLEncoder.encode(password, Charsets.UTF_8)}"
                    )
                )
        )
    location = location.resolve(action)
    while (next.statusCode() in 300..399) {
      check(++hops < 20) { "too many redirects" }
      location = location.resolve(next.headers().firstValue("Location").get())
      if (isCallback(location)) return location
      next = get(location)
    }
    error(
        "the provider did not send the person back: ${next.statusCode()} ${next.body().substringAfter("<body").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").take(600)}"
    )
  }

  private fun isCallback(uri: URI) = uri.toString().startsWith(callbackBase)
}

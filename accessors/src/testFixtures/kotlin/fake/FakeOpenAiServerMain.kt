package dev.lawlan.runline.accessors.fake

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Runs [FakeOpenAiServer] as a process of its own, for the Console's real-browser tests (WI-50,
 * WI-52), which define an `openai-compatible` resource on a running Engine and check it: `./gradlew
 * :accessors:fakeOpenAiServer`. Local only, never part of `check`.
 *
 * Environment: `FAKE_OPENAI_KEY_FILE` (optional) is a file whose text, trimmed, is the key a
 * request must carry (`Authorization: Bearer <key>`), otherwise 401; `FAKE_OPENAI_CHAT_DELAY_MS`
 * (optional) keeps each chat completion waiting that long before it is answered, so that a request
 * stays in flight. With `FAKE_OPENAI_TLS_KEYSTORE` (a PKCS12 file holding the server's private key
 * entry, for `localhost`) and `FAKE_OPENAI_TLS_PASSWORD_FILE` (its password) it speaks HTTPS; with
 * `FAKE_OPENAI_TLS_CLIENT_CA` too (a PEM certificate) it requires a client certificate that
 * authority issued (WI-52). It prints its base address
 * (`FAKE_OPENAI_URL=http://127.0.0.1:<port>/v1`, or `https://localhost:<port>/v1`) and writes it to
 * `FAKE_OPENAI_URL_FILE` when that is set; it runs until it is stopped.
 */
fun main() {
  val key = System.getenv("FAKE_OPENAI_KEY_FILE")?.let { Files.readString(Path.of(it)).trim() }
  val delay = System.getenv("FAKE_OPENAI_CHAT_DELAY_MS")?.toLong() ?: 0
  val tls = System.getenv("FAKE_OPENAI_TLS_KEYSTORE")?.let { tlsOf(Path.of(it)) }
  val server =
      FakeOpenAiServer(
          requiredKey = key,
          tls = tls,
          requireClientCertificate = System.getenv("FAKE_OPENAI_TLS_CLIENT_CA") != null,
      )
  server.script = { request, _ ->
    if (request.path.endsWith("/chat/completions")) Thread.sleep(delay)
    false
  }
  val url = if (tls == null) server.baseUrl else server.baseUrl("localhost")
  System.getenv("FAKE_OPENAI_URL_FILE")?.let { Files.writeString(Path.of(it), url) }
  println("FAKE_OPENAI_URL=$url")
  System.out.flush()
  CountDownLatch(1).await()
}

/** The server's identity from [keystore], and the authority of its clients when one is given. */
private fun tlsOf(keystore: Path): SSLContext {
  val password =
      Files.readString(Path.of(System.getenv("FAKE_OPENAI_TLS_PASSWORD_FILE"))).trim().toCharArray()
  val keys = KeyStore.getInstance("PKCS12")
  Files.newInputStream(keystore).use { keys.load(it, password) }
  val keyManagers = KeyManagerFactory.getInstance("PKIX").apply { init(keys, password) }.keyManagers
  val trustManagers =
      System.getenv("FAKE_OPENAI_TLS_CLIENT_CA")?.let { file ->
        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        Files.newInputStream(Path.of(file)).use {
          trust.setCertificateEntry(
              "client-ca",
              CertificateFactory.getInstance("X.509").generateCertificate(it),
          )
        }
        TrustManagerFactory.getInstance("PKIX").apply { init(trust) }.trustManagers
      }
  return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, null) }
}

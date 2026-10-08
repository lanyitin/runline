package dev.lawlan.runline.accessors.tls

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration

/**
 * Run in a JVM of its own by [ResourceTlsDefaultTrustTest], whose default trust is given on its
 * command line: a GET of `args[0]` with a resource's TLS that trusts the certificates in the PEM
 * files `args[1..]` (none: the JVM's default trust). Prints the status, or the failure's category.
 */
fun main(args: Array<String>) {
  val factory = CertificateFactory.getInstance("X.509")
  val trusted =
      args.drop(1).map { file ->
        Files.newInputStream(Path.of(file)).use {
          factory.generateCertificate(it) as X509Certificate
        }
      }
  val context = ResourceTls(trusted, null).newContext()
  val client =
      HttpClient.newBuilder()
          .sslContext(context.sslContext)
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build()
  try {
    val response =
        client.send(
            HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
    println("status ${response.statusCode()}")
  } catch (e: IOException) {
    println("failure ${context.classify(e)?.wire}")
  }
}

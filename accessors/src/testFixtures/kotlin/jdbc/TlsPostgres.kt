package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.tls.TestPki
import java.security.cert.X509Certificate
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.Properties
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A real PostgreSQL that speaks TLS (WI-52), of the version the Engine runs on: it presents
 * [identity] (a certificate a test made with [TestPki]) and checks client certificates against
 * [clientAuthorities]. Accounts made with [createRole] log in with a password; those made with
 * `certificateRequired` need, over TLS, a client certificate those authorities issued as well
 * (`clientcert=verify-ca`), and cannot log in without TLS at all (the driver's default `prefer`
 * would otherwise try again without TLS when refused). Plain connections are accepted for the other
 * accounts, the test's own administration among them, as the `pg_hba.conf` below says.
 */
class TlsPostgres(
    pki: TestPki,
    identity: TestPki.Issued,
    clientAuthorities: List<X509Certificate>,
) : AutoCloseable {
  private val container: PostgreSQLContainer =
      PostgreSQLContainer("postgres:17-alpine").apply {
        withCopyToContainer(
            Transferable.of(pki.chainPem(identity).toFile().readBytes()),
            "/tls/server.pem",
        )
        withCopyToContainer(
            Transferable.of(pki.serverKeyPem(identity).toFile().readBytes()),
            "/tls/server.key",
        )
        withCopyToContainer(
            Transferable.of(pem(clientAuthorities).toByteArray()),
            "/tls/clients.pem",
        )
        withCopyToContainer(Transferable.of(HBA.toByteArray()), "/tls/hba.conf")
        // The server reads its key only when it belongs to it and nobody else may read it: the
        // files are copied where the server's account owns them before the image's own start.
        withCreateContainerCmdModifier {
          it.withEntrypoint(
              "sh",
              "-c",
              "mkdir -p /etc/pgtls && cp /tls/* /etc/pgtls/ && chown -R postgres /etc/pgtls && " +
                  "chmod 600 /etc/pgtls/server.key && exec docker-entrypoint.sh \"$@\"",
              "sh",
          )
        }
        setCommand(
            "postgres",
            "-c",
            "fsync=off",
            "-c",
            "ssl=on",
            "-c",
            "ssl_cert_file=/etc/pgtls/server.pem",
            "-c",
            "ssl_key_file=/etc/pgtls/server.key",
            "-c",
            "ssl_ca_file=/etc/pgtls/clients.pem",
            "-c",
            "hba_file=/etc/pgtls/hba.conf",
        )
        withStartupTimeout(java.time.Duration.ofMinutes(3))
        start()
      }

  init {
    execute("CREATE ROLE certificate_required NOLOGIN")
  }

  /** The name the server's certificate is for, as the tests make it: `localhost`. */
  val host: String
    get() = container.host

  val port: Int
    get() = container.getMappedPort(5432)

  val database: String
    get() = container.databaseName

  /** A connection of the test itself, without TLS, as the administrator. */
  fun admin(): Connection =
      DriverManager.getConnection(
          "jdbc:postgresql://$host:$port/$database",
          Properties().apply {
            setProperty("user", container.username)
            setProperty("password", container.password)
            setProperty("sslmode", "disable")
            setProperty("ApplicationName", "test-observer")
          },
      )

  fun execute(sql: String) {
    admin().use { c -> c.createStatement().use { it.execute(sql) } }
  }

  /** An account that logs in with [password]; over TLS also with a client certificate if asked. */
  fun createRole(role: String, password: String, certificateRequired: Boolean = false) {
    execute("CREATE ROLE $role LOGIN PASSWORD '$password'")
    if (certificateRequired) execute("GRANT certificate_required TO $role")
  }

  override fun close() = container.stop()

  private companion object {
    /** A membership of this role means the account needs a client certificate over TLS. */
    val HBA =
        """
        local all all trust
        hostssl all +certificate_required all scram-sha-256 clientcert=verify-ca
        hostssl all all all scram-sha-256
        host all +certificate_required all reject
        host all all all scram-sha-256
        """
            .trimIndent() + "\n"

    fun pem(certificates: List<X509Certificate>): String =
        certificates.joinToString("") {
          "-----BEGIN CERTIFICATE-----\n" +
              Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it.encoded) +
              "\n-----END CERTIFICATE-----\n"
        }
  }
}

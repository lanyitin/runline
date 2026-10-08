package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.fake.BlackHole
import dev.lawlan.runline.core.ResourceFailure
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The look an administrator's check takes at the database of a resource (WI-48). */
class JdbcProbeTest {
  private val rig = JdbcRig()

  @AfterTest fun close() = rig.close()

  private fun probe(
      settings: JdbcSettings = rig.settings(),
      credential: JdbcCredential = JdbcCredential.Password(rig.password),
      profile: JdbcProfile = PostgresProfile,
  ) = JdbcProbe.check(profile, settings, credential, 1500)?.failure

  @Test
  fun `a database that answers the health query passes, and nothing is left connected`() {
    assertNull(probe())
    assertEquals(0, rig.db.sessions())
  }

  @Test
  fun `a password that is not accepted is denied`() {
    assertEquals(ResourceFailure.DENIED, probe(credential = JdbcCredential.Password("nope")))
    assertEquals(ResourceFailure.DENIED, probe(credential = JdbcCredential.None))
  }

  @Test
  fun `a database nobody listens at, and one that does not answer, are told apart`() {
    val closed = ServerSocket(0).use { it.localPort }
    assertEquals(ResourceFailure.CONNECTION_FAILED, probe(rig.settings(port = closed)))

    BlackHole.baseUrl()
    assertEquals(
        ResourceFailure.CONNECT_TIMEOUT,
        probe(rig.settings(hostName = BlackHole.HOST)),
    )
  }

  @Test
  fun `a password the keystore cannot give is no connection at all`() {
    assertEquals(ResourceFailure.SECRET_UNAVAILABLE, probe(credential = JdbcCredential.Unavailable))
    assertEquals(0, rig.db.sessions())
  }

  @Test
  fun `a health query the database refuses is a SQL error`() {
    val broken =
        object : JdbcProfile by PostgresProfile {
          override val healthQuery = "SELECT * FROM there_is_no_such_table"
        }

    assertEquals(ResourceFailure.SQL_ERROR, probe(profile = broken))
  }
}

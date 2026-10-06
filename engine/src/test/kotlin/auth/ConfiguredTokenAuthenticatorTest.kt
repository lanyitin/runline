package dev.lawlan.runline.engine.auth

import dev.lawlan.runline.engine.config.ApiToken
import kotlin.test.*

class ConfiguredTokenAuthenticatorTest {
  private val authenticator =
      ConfiguredTokenAuthenticator(
          listOf(
              ApiToken("alice", Role.DEVELOPER, "tok-alice-1234"),
              ApiToken("root", Role.ADMIN, "tok-root-5678"),
          )
      )

  @Test
  fun `identifies the holder of a configured token by name and role`() {
    assertEquals(ApiIdentity("alice", Role.DEVELOPER), authenticator.authenticate("tok-alice-1234"))
    assertEquals(ApiIdentity("root", Role.ADMIN), authenticator.authenticate("tok-root-5678"))
  }

  @Test
  fun `rejects unknown, empty and near-miss tokens`() {
    for (candidate in
        listOf(
            "",
            "nope",
            "tok-alice-123",
            "tok-alice-12345",
            "TOK-ALICE-1234",
            " tok-alice-1234",
        )) {
      assertNull(authenticator.authenticate(candidate), candidate)
    }
  }

  @Test
  fun `administrator includes the developer role but not the other way round`() {
    assertTrue(Role.ADMIN.permits(Role.DEVELOPER))
    assertTrue(Role.ADMIN.permits(Role.ADMIN))
    assertTrue(Role.DEVELOPER.permits(Role.DEVELOPER))
    assertFalse(Role.DEVELOPER.permits(Role.ADMIN))
  }

  @Test
  fun `identity text never carries a token`() {
    assertFalse(authenticator.authenticate("tok-alice-1234")!!.toString().contains("tok-"))
  }
}

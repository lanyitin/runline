package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import kotlin.test.*

/** The role a person gets from the groups the provider says they are in (ADR-029, 2). */
class GroupRolesTest {
  private val roles = GroupRoles(adminGroup = "runline-admin", developerGroup = "runline-developer")

  @Test
  fun `the admin group gives admin and the developer group developer`() {
    assertEquals(Role.ADMIN, roles.roleOf(listOf("runline-admin")))
    assertEquals(Role.DEVELOPER, roles.roleOf(listOf("runline-developer")))
  }

  @Test
  fun `a member of both is an administrator, whatever the order`() {
    assertEquals(Role.ADMIN, roles.roleOf(listOf("runline-developer", "runline-admin")))
    assertEquals(Role.ADMIN, roles.roleOf(listOf("runline-admin", "runline-developer")))
  }

  @Test
  fun `other groups, no groups or no claim give no role`() {
    assertNull(roles.roleOf(listOf("staff", "everyone")))
    assertNull(roles.roleOf(emptyList()))
    assertNull(roles.roleOf(null))
  }

  @Test
  fun `a group is its exact name`() {
    assertNull(roles.roleOf(listOf("Runline-Admin", "runline-admin-extra", "runline-develope")))
    assertEquals(Role.DEVELOPER, roles.roleOf(listOf("staff", "runline-developer", "x")))
  }
}

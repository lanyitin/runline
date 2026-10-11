package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role

/**
 * Which role the groups of a person give (ADR-029, 2): the administrator group first, then the
 * developer group, nothing for anybody else. A group is its exact name. A person with no role is
 * refused; there is no role of "nothing" to be given.
 */
class GroupRoles(private val adminGroup: String, private val developerGroup: String) {
  fun roleOf(groups: Collection<String>?): Role? =
      when {
        groups == null -> null
        adminGroup in groups -> Role.ADMIN
        developerGroup in groups -> Role.DEVELOPER
        else -> null
      }
}

package dev.lawlan.runline.engine.auth

/** The two API roles (ADR-012). An administrator can do everything a developer can. */
enum class Role {
  DEVELOPER,
  ADMIN;

  /** Whether holding this role satisfies [required]. */
  fun permits(required: Role): Boolean = this >= required
}

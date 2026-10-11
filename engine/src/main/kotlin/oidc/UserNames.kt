package dev.lawlan.runline.engine.oidc

/** The names of the people who have signed in. */
interface UserNames {
  /** Those of [names] that a person who signed in has. */
  fun taken(names: Collection<String>): Set<String>
}

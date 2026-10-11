package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.config.ApiToken
import dev.lawlan.runline.engine.config.ConfigurationException

/**
 * The names of the API tokens and the names of the people who signed in are one set of identities
 * (ADR-029, 3): ownership is kept under the name, so two holders of one name would own each other's
 * pipelines. Names are said; a token never is.
 */
object TokenNameCheck {
  fun require(tokens: List<ApiToken>, userNames: UserNames) {
    val taken = userNames.taken(tokens.map { it.name }).sorted()
    if (taken.isNotEmpty()) {
      throw ConfigurationException(
          "auth.tokens uses a name that a person who signed in already has: " + taken.joinToString()
      )
    }
  }
}

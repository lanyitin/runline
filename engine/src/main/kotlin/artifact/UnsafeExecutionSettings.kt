package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.auth.ApiIdentity
import java.time.Clock
import org.slf4j.LoggerFactory

/**
 * The administrator's decision whether a pipeline definition may run when it is unsafe (ADR-006).
 * The setting lives on the definition itself, defaults to not allowed, is never inherited by a new
 * version, and records who decided and when.
 */
class UnsafeExecutionSettings(
    private val definitions: DefinitionStore,
    private val resolver: VersionResolver,
    private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(UnsafeExecutionSettings::class.java)

  /**
   * The definition as updated, in the version of [contentHash] that [uploader] names (an
   * administrator is told to name one when there are several, and nothing is changed); not found
   * when there is no such version or definition.
   */
  fun set(
      contentHash: String,
      uploader: String?,
      pipelineName: String,
      allow: Boolean,
      by: ApiIdentity,
  ): VersionOutcome<StoredDefinition> =
      resolver.resolve(contentHash, uploader, by.visibility).flatten { owner ->
        definitions
            .setUnsafeExecution(contentHash, owner, pipelineName, allow, by.name, clock.instant())
            ?.also {
              log.info(
                  "Unsafe execution of {} (version {} of {}) set to {} by {}",
                  pipelineName,
                  owner,
                  contentHash,
                  allow,
                  by.name,
              )
            }
            ?.let { VersionOutcome.Resolved(it) } ?: VersionOutcome.NotFound
      }
}

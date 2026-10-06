package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.auth.ApiIdentity
import java.time.Clock
import org.slf4j.LoggerFactory

/**
 * The administrator's decision whether a pipeline definition may run when it is unsafe (ADR-006).
 * The setting lives on the definition itself, defaults to not allowed, is never inherited by a new
 * version, and records who decided and when.
 */
class UnsafeExecutionSettings(private val definitions: DefinitionStore, private val clock: Clock) {
  private val log = LoggerFactory.getLogger(UnsafeExecutionSettings::class.java)

  /** The definition as updated, or null when there is no such definition. */
  fun set(
      contentHash: String,
      pipelineName: String,
      allow: Boolean,
      by: ApiIdentity,
  ): StoredDefinition? =
      definitions
          .setUnsafeExecution(contentHash, pipelineName, allow, by.name, clock.instant())
          ?.also {
            log.info(
                "Unsafe execution of {} (version {}) set to {} by {}",
                pipelineName,
                contentHash,
                allow,
                by.name,
            )
          }
}

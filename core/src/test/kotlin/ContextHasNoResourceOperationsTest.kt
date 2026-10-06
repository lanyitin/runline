package dev.lawlan.runline.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shared resources are acquired by the Engine before a run starts (ADR-007), never by pipeline code
 * while it runs, so the context must not offer a way to do it.
 */
class ContextHasNoResourceOperationsTest {
  private val capabilities =
      listOf(
          PipelineContext::class.java,
          FileOperations::class.java,
          NetworkAccess::class.java,
          ProcessRunner::class.java,
      )

  @Test
  fun `no capability of the context can acquire, hold or release a shared resource`() {
    val members = capabilities.flatMap { type ->
      type.methods.map { "${type.simpleName}.${it.name}" }
    }

    val suspicious = members.filter { name ->
      listOf("resource", "acquire", "release", "lock", "semaphore").any {
        name.lowercase().contains(it)
      }
    }

    assertEquals(emptyList(), suspicious)
  }
}

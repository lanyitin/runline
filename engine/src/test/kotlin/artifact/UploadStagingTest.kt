package dev.lawlan.runline.engine.artifact

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.test.*

class UploadStagingTest {
  private val staging = UploadStaging(Files.createTempDirectory("staging"))

  @Test
  fun `stages the bytes with their size and SHA-256`() {
    val bytes = ByteArray(100_000) { (it * 7).toByte() }

    staging.stage(ByteArrayInputStream(bytes), maxBytes = 1_000_000).use { staged ->
      assertContentEquals(bytes, Files.readAllBytes(staged.path))
      assertEquals(bytes.size.toLong(), staged.sizeBytes)
      assertEquals(
          MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
          staged.contentHash,
      )
    }
  }

  @Test
  fun `closing removes the staged file`() {
    val staged = staging.stage(ByteArrayInputStream(ByteArray(10)), maxBytes = 100)
    assertTrue(staged.path.exists())

    staged.close()

    assertFalse(staged.path.exists())
  }

  @Test
  fun `a body over the limit is refused and leaves no file behind`() {
    assertFailsWith<UploadTooLargeException> {
      staging.stage(ByteArrayInputStream(ByteArray(101)), maxBytes = 100)
    }

    assertEquals(0, Files.list(staging.directory).use { it.count() })
  }

  @Test
  fun `a body exactly at the limit is accepted`() {
    staging.stage(ByteArrayInputStream(ByteArray(100)), maxBytes = 100).use {
      assertEquals(100, it.sizeBytes)
    }
  }
}

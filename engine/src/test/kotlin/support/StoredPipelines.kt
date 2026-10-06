package dev.lawlan.runline.engine.support

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Writes artifacts with one definition each straight into a store, for tests below the API. */
class StoredPipelines(private val store: ArtifactStore, private val dir: Path) {
  val metadata =
      MetadataDoc(
          parameters =
              listOf(
                  ParameterDoc("env", required = true),
                  ParameterDoc("retries", required = false, default = "3"),
              ),
          files = listOf(FileAccessDoc("RUN_PRIVATE", "READ_WRITE")),
          network = AccessLimitDoc(unrestricted = false, allow = emptyList()),
          processes = AccessLimitDoc(unrestricted = false, allow = emptyList()),
          resources = emptyList(),
      )

  /** Saves a version [label] holding one pipeline [name]; returns the version's content hash. */
  fun save(
      label: String,
      name: String,
      uploader: String = "alice",
      verdict: Verdict = Verdict.SAFE,
      content: ByteArray = "jar-$label".toByteArray(),
      className: String = "p.${name.replace('-', '_')}",
      metadata: MetadataDoc = this.metadata,
  ): String {
    val hash =
        MessageDigest.getInstance("SHA-256").digest(label.toByteArray()).joinToString("") {
          "%02x".format(it)
        }
    val file = Files.createTempFile(dir, "a", ".jar").also { Files.write(it, content) }
    store.saveIfAbsent(
        NewArtifact(
            hash,
            file,
            content.size.toLong(),
            uploader,
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            listOf(NewDefinition(className, name, metadata, verdict, emptyList(), "config")),
        )
    )
    return hash
  }
}

package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.ScopeDirectory
import dev.lawlan.runline.core.ResourceFailure
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.security.SecureRandom
import java.util.Collections
import java.util.HexFormat

/** Where the bytes of a file part come from. */
internal sealed interface UploadSource {
  /** Bytes the pipeline gave; they are already in memory, and are not copied. */
  class Bytes(val bytes: ByteArray) : UploadSource

  /** A file of a scope's directory, read as the form is sent: never held whole in memory. */
  class Scoped(val directory: ScopeDirectory, val relative: String) : UploadSource
}

/** One file part: the entry's form field it goes in, the file name it is sent as, and its bytes. */
internal class UploadPart(val field: String, val filename: String, val source: UploadSource)

/** A form that is opened and ready to be sent: its type, how long it is, and its bytes. */
internal class OpenedUpload(
    val contentType: String,
    val length: Long,
    val stream: InputStream,
    private val files: List<Closeable>,
) : Closeable {
  /** The same form read through [wrapped] (a stream that reads from this one). */
  fun reading(wrapped: InputStream) = OpenedUpload(contentType, length, wrapped, files)

  /** Lets go of the files the form reads from. */
  override fun close() {
    files.forEach { runCatching { it.close() } }
  }
}

/**
 * The multipart form of one request (WI-53), as far as it is known before anything is opened. The
 * Engine makes the form: a pipeline gives text fields and file parts, and the field names, the file
 * part's type and the delimiter are the Engine's alone.
 */
internal class UploadForm(val fields: Map<String, String>, val parts: List<UploadPart>) {
  /** The form as the bytes to send; the parts that are files are opened here and read as sent. */
  fun open(limit: Long): OpenedUpload {
    val boundary = newBoundary()
    val pieces = mutableListOf<InputStream>()
    val files = mutableListOf<Closeable>()
    var length = 0L
    fun add(text: String) {
      val bytes = text.encodeToByteArray()
      length += bytes.size
      pieces += ByteArrayInputStream(bytes)
    }
    for ((name, value) in fields) {
      add("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    }
    try {
      for (part in parts) {
        add(
            "--$boundary\r\nContent-Disposition: form-data; name=\"${part.field}\"; " +
                "filename=\"${part.filename}\"\r\nContent-Type: $PART_TYPE\r\n\r\n"
        )
        when (val source = part.source) {
          is UploadSource.Bytes -> {
            length += source.bytes.size
            pieces += ByteArrayInputStream(source.bytes)
          }
          is UploadSource.Scoped -> {
            val file = source.directory.openForRead(source.relative)
            files += file
            length += file.size
            pieces += file.stream
          }
        }
        add("\r\n")
        if (length > limit) throw ResourceOperationFailure(ResourceFailure.REQUEST_TOO_LARGE)
      }
      add("--$boundary--\r\n")
      if (length > limit) throw ResourceOperationFailure(ResourceFailure.REQUEST_TOO_LARGE)
    } catch (e: Throwable) {
      files.forEach { runCatching { it.close() } }
      throw e
    }
    return OpenedUpload(
        "multipart/form-data; boundary=$boundary",
        length,
        java.io.SequenceInputStream(Collections.enumeration(pieces)),
        files,
    )
  }

  private companion object {
    /** Every file part is sent as this; the service finds out what it is by its name or content. */
    const val PART_TYPE = "application/octet-stream"
    private val RANDOM = SecureRandom()

    /** Unpredictable, made after the pipeline has given everything: nothing it gave can match. */
    fun newBoundary(): String {
      val bytes = ByteArray(16)
      RANDOM.nextBytes(bytes)
      return "runline-" + HexFormat.of().formatHex(bytes)
    }
  }
}

package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One of the two directories of ADR-009 (the pipeline's shared directory, the run's private one) as
 * the host is given it with a call: the files a host reads out of it for a pipeline and the files
 * it writes into it, and nothing outside of it, with the same confinement as a `file` resource
 * ([Confinement]): `..`, an absolute path or a link that leads out is
 * [ResourceFailure.PATH_REJECTED].
 */
internal class ScopeDirectory private constructor(private val root: Path) {
  /** A file of the directory, opened to be read in a stream, and how long it is. */
  class OpenedFile(private val channel: SeekableByteChannel) : Closeable {
    val size: Long = channel.size()

    /** Exactly [size] bytes: a file that has become shorter is an error, one that grew is cut. */
    val stream: InputStream = Exactly(Channels.newInputStream(channel), size)

    override fun close() = channel.close()
  }

  /** Opens the file at [relative] for reading, without following a link at the last step. */
  fun openForRead(relative: String): OpenedFile = Confinement.io {
    val file = Confinement.locate(root, relative, createParents = false)
    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
      throw ResourceOperationFailure(ResourceFailure.FAILED)
    }
    OpenedFile(Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
  }

  private class Exactly(private val inner: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
      if (left <= 0) return -1
      val b = inner.read()
      if (b < 0) throw IOException("the file became shorter while it was read")
      left--
      return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (left <= 0) return -1
      val count = inner.read(buffer, offset, minOf(length.toLong(), left).toInt())
      if (count < 0) throw IOException("the file became shorter while it was read")
      left -= count
      return count
    }

    override fun close() = inner.close()
  }

  companion object {
    /** The directory at [root], which must be an absolute path. */
    fun at(root: String): ScopeDirectory {
      val path =
          try {
            Path.of(root)
          } catch (e: InvalidPathException) {
            throw ResourceOperationFailure(ResourceFailure.FAILED, e)
          }
      if (!path.isAbsolute) throw ResourceOperationFailure(ResourceFailure.FAILED)
      return ScopeDirectory(path)
    }
  }
}

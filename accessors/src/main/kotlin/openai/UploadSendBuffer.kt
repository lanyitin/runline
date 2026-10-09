package dev.lawlan.runline.accessors.openai

/**
 * The upper bound of the send buffer of the JDK HTTP client (ADR-019 decision 4, WI-60), which the
 * Engine and the development entry install as they start.
 *
 * The idle limit of an upload runs from each taking of the form's bytes by the client. A send
 * buffer the operating system lets grow to megabytes takes in a large piece at once, after which a
 * service that keeps reading slowly could go longer than the idle limit without the client taking
 * anything. With the buffer bounded, what the client has taken stays close to what the service has
 * received. The JDK client offers this only as a JVM-wide setting, read for each new connection, so
 * it applies to every JDK HTTP client in the JVM; it is the one such setting the Engine makes.
 */
object UploadSendBuffer {
  /** The value asked for; the operating system may adjust what it gives (Linux doubles it). */
  const val BYTES: Int = 256 * 1024

  /** The JDK HTTP client's setting for it. */
  const val PROPERTY = "jdk.httpclient.sendBufferSize"

  /** Sets the bound for the whole JVM; a value given on the command line is replaced. */
  fun install() {
    System.setProperty(PROPERTY, BYTES.toString())
  }
}

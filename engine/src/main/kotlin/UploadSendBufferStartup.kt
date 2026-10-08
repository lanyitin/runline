package dev.lawlan.runline.engine

import dev.lawlan.runline.accessors.openai.UploadSendBuffer
import io.ktor.server.application.*

/**
 * Bounds the send buffer of the JDK HTTP client, for the whole JVM, before any run starts (ADR-019
 * decision 4, WI-60): the idle limit of an upload then follows what the service has received. The
 * value is fixed; neither administrators nor pipelines set it.
 */
fun Application.configureUploadSendBuffer() {
  UploadSendBuffer.install()
}

package dev.lawlan.runline.engine.artifact

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import org.slf4j.LoggerFactory

/**
 * Log, trace and metrics for uploads. Only the uploader's name is recorded, never a token (the
 * service never sees one).
 */
class UploadTelemetry(openTelemetry: OpenTelemetry) {
  private val log = LoggerFactory.getLogger(UploadTelemetry::class.java)
  private val tracer = openTelemetry.getTracer("runline.upload")
  private val meter = openTelemetry.getMeter("runline.upload")
  private val uploads = meter.counterBuilder("runline.upload.count").build()
  private val pipelines = meter.counterBuilder("runline.upload.pipelines").build()

  /** Runs one upload inside a span and records its outcome. */
  fun observe(uploader: String, upload: () -> UploadResult): UploadResult {
    val span = tracer.spanBuilder("runline.upload").setAttribute(UPLOADER, uploader).startSpan()
    try {
      span.makeCurrent().use {
        val result = upload()
        record(uploader, result, span)
        return result
      }
    } catch (e: Throwable) {
      span.recordException(e)
      span.setStatus(StatusCode.ERROR)
      uploads.add(1, Attributes.of(RESULT, "error"))
      log.error("Upload by {} failed", uploader, e)
      throw e
    } finally {
      span.end()
    }
  }

  private fun record(
      uploader: String,
      result: UploadResult,
      span: io.opentelemetry.api.trace.Span,
  ) {
    val (label, reason) =
        when (result) {
          is UploadResult.Created -> "created" to null
          is UploadResult.Existing -> "existing" to null
          is UploadResult.Rejected -> "rejected" to result.reason.name.lowercase()
        }
    span.setAttribute(UPLOAD_RESULT, label)
    reason?.let { span.setAttribute(REASON, it) }
    uploads.add(
        1,
        if (reason == null) Attributes.of(RESULT, label)
        else Attributes.of(RESULT, label, REASON, reason),
    )
    when (result) {
      is UploadResult.Created -> {
        result.artifact.definitions.forEach {
          pipelines.add(1, Attributes.of(VERDICT, it.verdict.name))
        }
        log.info(
            "Upload by {} created artifact {} with {} pipeline(s)",
            uploader,
            result.artifact.contentHash,
            result.artifact.definitions.size,
        )
      }
      is UploadResult.Existing ->
          log.info(
              "Upload by {} matched existing artifact {}",
              uploader,
              result.artifact.contentHash,
          )
      is UploadResult.Rejected -> log.info("Upload by {} rejected: {}", uploader, reason)
    }
  }

  private companion object {
    val UPLOADER = AttributeKey.stringKey("runline.uploader")
    val UPLOAD_RESULT = AttributeKey.stringKey("runline.upload.result")
    val RESULT = AttributeKey.stringKey("result")
    val REASON = AttributeKey.stringKey("reason")
    val VERDICT = AttributeKey.stringKey("verdict")
  }
}

package dev.lawlan.runline.engine

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry

fun Application.configureOpenTelemetry() {
  val openTelemetry: OpenTelemetry by dependencies
  install(KtorServerTelemetry) {
    setOpenTelemetry(openTelemetry)
    capturedRequestHeaders(HttpHeaders.UserAgent)
    spanKindExtractor {
      if (httpMethod == HttpMethod.Post) {
        SpanKind.PRODUCER
      } else {
        SpanKind.CLIENT
      }
    }
    attributesExtractor {
      onStart { attributes.put("start-time", System.currentTimeMillis()) }
      onEnd { attributes.put("end-time", System.currentTimeMillis()) }
    }
  }
}

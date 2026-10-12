package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.MemoryTraceExporter
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.di.*
import io.ktor.server.testing.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * The reads of views through the whole Engine, as the log and the trace have them (WI-1301): each
 * one, with the view and the result and the rows; and neither the text of a query nor the value of
 * a parameter in any of them, nor in any answer, whatever the result.
 */
class ViewObservabilityApiTest : ViewApiSupport() {
  private val queryMarker = "QUERY-MARKER-91C2"
  private val valueMarker = "VALUE-MARKER-6E04"

  @Test
  fun `every read is logged and traced with its view, result and rows, and none holds a query or a value`() {
    val logs = CapturedLogs()
    try {
      MemoryTraceExporter.recording {
        testApplication {
          engineWithRoot()
          defineFeedDb()
          fillArticles()
          val hash =
              uploadFeed(
                  view(
                      id = "marked",
                      query =
                          "SELECT title, '$queryMarker' AS tag FROM article WHERE title <> :not",
                      parameters = """@ViewParam(name = "not")""",
                      columns = """@ViewColumn(name = "title", label = "Title")""",
                      sort = "",
                  ),
                  view(
                      id = "writes",
                      query = "DELETE FROM article WHERE title = '$queryMarker'",
                      parameters = "",
                      columns = """@ViewColumn(name = "title", label = "Title")""",
                      sort = "",
                  ),
                  view(
                      id = "missing",
                      query = "SELECT '$queryMarker' AS title FROM no_such_table WHERE 1 = :one",
                      parameters = """@ViewParam(name = "one", type = ViewParamType.INTEGER)""",
                      columns = """@ViewColumn(name = "title", label = "Title")""",
                      sort = "",
                  ),
              )

          val answers =
              listOf(
                  viewRead(hash, "marked", "?param.not=$valueMarker"),
                  viewRead(hash, "writes"),
                  viewRead(hash, "missing", "?param.one=1"),
                  viewRead(hash, "missing", "?param.one=$valueMarker"),
              )
          val bodies = answers.map { it.bodyAsText() }
          (application.dependencies.resolve<OpenTelemetry>() as OpenTelemetrySdk)
              .sdkTracerProvider
              .forceFlush()
              .join(30, TimeUnit.SECONDS)

          assertEquals(
              listOf(200, 422, 409, 400),
              answers.map { it.status.value },
          )
          val spans =
              MemoryTraceExporter.spans.finishedSpanItems.filter { it.name == "runline.views.read" }
          val seen = spans.map { s ->
            s.attributes.asMap().mapKeys { it.key.key }.mapValues { "${it.value}" }
          }
          assertEquals(
              listOf("ok", "query_rejected", "schema_mismatch", "invalid_parameter"),
              seen.map { it["runline.view.result"] },
          )
          assertEquals("3", seen.first()["runline.view.rows"])
          assertTrue(seen.all { it["runline.view.pipeline"] == "feed" })
          val lines = logs.lines.filter { "View read" in it }
          assertEquals(4, lines.size, lines.toString())
          assertTrue("view=marked" in lines[0] && "result=ok" in lines[0] && "rows=3" in lines[0])
          assertTrue("result=query_rejected" in lines[1])
          assertTrue("result=schema_mismatch" in lines[2])
          assertTrue("result=invalid_parameter" in lines[3])
          val recorded = logs.lines + spans.map { it.toString() }
          for (secret in listOf(queryMarker, valueMarker, "DELETE FROM", "no_such_table")) {
            assertTrue(recorded.none { secret in it }, "$secret is recorded")
            assertTrue(bodies.drop(1).none { secret in it }, "$secret is in an answer")
          }
        }
      }
    } finally {
      logs.close()
    }
  }
}

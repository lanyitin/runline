package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtlpReceiver
import dev.lawlan.runline.engine.support.TraceExportRig
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

/**
 * Spans a run submits to a `trace-export` resource reach a real OTLP receiver (ADR-46): with the
 * service name of the resource, the parent and child relation the pipeline gave, one trace for one
 * run, and the credential from the keystore. Everything is real: the exporter, the socket, the
 * receiver that reads the protocol, the PKCS12 keystore written by `keytool`.
 */
class TraceExportDeliveryTest {
  private val credential = "Basic bGFuZ2Z1c2UtcGs6bGFuZ2Z1c2Utc2s="
  private val keystores = Keystores()
  private val keystore = keystores.pkcs12("trace.p12", mapOf("langfuse-auth" to credential))
  private val secrets =
      KeystoreSecretStore.open(
          keystore,
          SecretValue(Files.readString(keystores.passwordFile()).trim()),
      )
  private val receiver = OtlpReceiver()
  private val rig = TraceExportRig(secrets)

  @AfterTest
  fun close() {
    rig.close()
    receiver.close()
    secrets.close()
  }

  private fun await(what: String, seconds: Long = 15, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  @Test
  fun `a submitted span reaches the receiver with the service name, times, status and attributes`() {
    val binding = rig.bind(rig.resource(receiver.endpoint, serviceName = "transcribe"))
    val start = Instant.parse("2026-10-27T10:00:00.123456789Z")

    rig.submit(
        binding,
        "transcribe.segment",
        "s1",
        start = start,
        end = start.plusMillis(250),
        status = "ERROR",
        statusMessage = "model failed",
        attributes =
            mapOf(
                "text" to "hello",
                "ok" to true,
                "count" to 3L,
                "ratio" to 0.5,
                "tags" to listOf("a", "b"),
            ),
    )
    await("the span at the receiver") { receiver.spans.isNotEmpty() }

    val span = receiver.spans.single()
    assertEquals("transcribe.segment", span.name)
    assertEquals("transcribe", span.serviceName)
    assertEquals(start.epochSecond * 1_000_000_000 + start.nano, span.startNanos)
    assertEquals(span.startNanos + 250_000_000, span.endNanos)
    assertEquals(2, span.statusCode)
    assertEquals("model failed", span.statusMessage)
    assertEquals(
        mapOf(
            "text" to "hello",
            "ok" to true,
            "count" to 3L,
            "ratio" to 0.5,
            "tags" to listOf("a", "b"),
        ),
        span.attributes,
    )
    assertEquals("/v1/traces", receiver.requests.single().path)
  }

  @Test
  fun `the parent of a span is found by the local identifier even when the child comes first`() {
    val binding = rig.bind(rig.resource(receiver.endpoint))

    rig.submit(binding, "child", "c1", parentId = "p1")
    rig.submit(binding, "grandchild", "g1", parentId = "c1")
    rig.submit(binding, "parent", "p1")
    await("three spans") { receiver.spans.size == 3 }

    val byName = receiver.spans.associateBy { it.name }
    assertEquals("", byName.getValue("parent").parentSpanId)
    assertEquals(byName.getValue("parent").spanId, byName.getValue("child").parentSpanId)
    assertEquals(byName.getValue("child").spanId, byName.getValue("grandchild").parentSpanId)
    assertEquals(3, byName.values.map { it.spanId }.toSet().size)
  }

  @Test
  fun `the spans of one run are one trace and another run is another trace`() {
    val resource = rig.resource(receiver.endpoint)
    val first = rig.bind(resource)
    val second = rig.bind(resource)

    rig.submit(first, "a1", "x")
    rig.submit(first, "a2", "y")
    rig.submit(second, "b1", "x")
    await("three spans") { receiver.spans.size == 3 }

    val byName = receiver.spans.associateBy { it.name }
    assertEquals(byName.getValue("a1").traceId, byName.getValue("a2").traceId)
    assertNotEquals(byName.getValue("a1").traceId, byName.getValue("b1").traceId)
    assertEquals(32, byName.getValue("a1").traceId.length)
    assertNotEquals(byName.getValue("a1").spanId, byName.getValue("b1").spanId)
  }

  @Test
  fun `the whole credential of the alias is the authorization header`() {
    val binding = rig.bind(rig.resource(receiver.endpoint))

    rig.submit(binding, "s", "s1")
    await("the span") { receiver.spans.isNotEmpty() }

    assertEquals(credential, receiver.requests.single().authorization)
  }
}

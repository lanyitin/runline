package dev.lawlan.runline.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoreBoundaryTest {

  @Test
  fun `runs on JDK 25`() {
    assertEquals(25, Runtime.version().feature())
  }

  @Test
  fun `does not depend on Ktor`() {
    assertFailsWith<ClassNotFoundException> {
      Class.forName("io.ktor.server.application.Application")
    }
  }

  @Test
  fun `does not depend on the OpenTelemetry helpers`() {
    assertFailsWith<ClassNotFoundException> { Class.forName("io.opentelemetry.api.OpenTelemetry") }
  }

  @Test
  fun `does not depend on the Engine`() {
    assertFailsWith<ClassNotFoundException> { Class.forName("dev.lawlan.runline.engine.MainKt") }
  }
}

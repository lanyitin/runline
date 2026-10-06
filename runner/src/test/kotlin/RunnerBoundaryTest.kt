package dev.lawlan.runline.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RunnerBoundaryTest {

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
  fun `does not depend on the Engine`() {
    assertFailsWith<ClassNotFoundException> { Class.forName("dev.lawlan.runline.engine.MainKt") }
  }
}

package dev.lawlan.runline.runner

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BoundaryTypesTest {
  @Test
  fun `accepts JDK built-in types, nested in JDK collections`() {
    BoundaryTypes.requireJdkOnly(
        java.util.HashMap<String, Any?>().apply {
          put("text", "a")
          put("number", 1L)
          put("flag", true)
          put("nothing", null)
          put("list", java.util.ArrayList(listOf("x", "y")))
          put("map", java.util.LinkedHashMap(mapOf("k" to "v")))
        }
    )
  }

  @Test
  fun `rejects Kotlin and Runner types, even inside a JDK collection`() {
    assertFailsWith<IllegalArgumentException> {
      BoundaryTypes.requireJdkOnly(emptyMap<String, String>())
    }
    assertFailsWith<IllegalArgumentException> {
      BoundaryTypes.requireJdkOnly(
          listOf("a").let { java.util.ArrayList<Any?>(it) + RunFailure("t", null, "") }
      )
    }
    assertFailsWith<IllegalArgumentException> {
      BoundaryTypes.requireJdkOnly(
          java.util.HashMap<String, Any?>().apply { put("failure", RunFailure("t", null, "")) }
      )
    }
    BoundaryTypes.requireJdkOnly(Path.of("/tmp").toString())
  }
}

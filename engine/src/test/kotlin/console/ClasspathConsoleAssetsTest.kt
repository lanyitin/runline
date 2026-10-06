package dev.lawlan.runline.engine.console

import io.ktor.http.*
import kotlin.test.*

/**
 * The Console's files read from the classpath (WI-31), against real files in the test resources.
 */
class ClasspathConsoleAssetsTest {
  private val assets = ClasspathConsoleAssets("console-fixture")

  @Test
  fun `a file under the root is found with its bytes`() {
    val asset = assertNotNull(assets.find("index.html"))

    assertTrue(String(asset.bytes).contains("fixture-entry-page"))
  }

  @Test
  fun `a file in a directory below the root is found`() {
    assertNotNull(assets.find("assets/app-3f9a1c.js"))
  }

  @Test
  fun `content types follow the file name`() {
    assertEquals(
        ContentType.Text.Html.withCharset(Charsets.UTF_8),
        assets.find("index.html")!!.contentType,
    )
    assertEquals(
        ContentType.Image.SVG,
        assets.find("favicon.svg")!!.contentType.withoutParameters(),
    )
    assertEquals(
        "javascript",
        assets.find("assets/app-3f9a1c.js")!!.contentType.contentSubtype.removePrefix("x-"),
    )
  }

  @Test
  fun `a missing file is not found`() {
    assertNull(assets.find("nothing.txt"))
  }

  @Test
  fun `a directory is not a file`() {
    assertNull(assets.find("assets"))
    assertNull(assets.find(""))
  }

  @Test
  fun `a path that climbs out of the root is not found`() {
    // application.yaml is a resource of the Engine, next to the root, not inside it.
    assertNull(ClasspathConsoleAssets("console-fixture").find("../console-fixture/index.html"))
    assertNull(assets.find("assets/../index.html"))
    assertNull(assets.find("/index.html"))
  }

  @Test
  fun `a root that does not exist has no files`() {
    assertNull(ClasspathConsoleAssets("no-such-console").find("index.html"))
  }
}

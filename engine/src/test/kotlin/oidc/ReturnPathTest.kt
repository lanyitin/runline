package dev.lawlan.runline.engine.oidc

import kotlin.test.*

/** Where a person lands after signing in: a path of this site, and nothing else (ADR-029). */
class ReturnPathTest {
  @Test
  fun `a path of this site is kept as it is, with its query and fragment`() {
    assertEquals("/pipelines", ReturnPath.sanitize("/pipelines"))
    assertEquals("/runs/42?tab=log#end", ReturnPath.sanitize("/runs/42?tab=log#end"))
    assertEquals("/", ReturnPath.sanitize("/"))
  }

  @Test
  fun `nothing, or an empty value, goes to the front page`() {
    assertEquals("/", ReturnPath.sanitize(null))
    assertEquals("/", ReturnPath.sanitize(""))
  }

  @Test
  fun `every form that could leave this site is ignored`() {
    val leaving =
        listOf(
            "https://evil.example/x",
            "http://evil.example",
            "//evil.example/x",
            "///evil.example",
            "/\\evil.example",
            "\\\\evil.example",
            "\\evil.example",
            "javascript:alert(1)",
            "evil.example/x",
            "pipelines",
            " /pipelines",
            "/ /evil",
            "/\t/evil.example",
            "/\r\nSet-Cookie: a=b",
            "/a\nb",
            "/a\u0000b",
            "/a\u007fb",
            "/" + "a".repeat(3000),
        )
    for (value in leaving) assertEquals("/", ReturnPath.sanitize(value), value)
  }
}

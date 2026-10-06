package dev.lawlan.runline.analyzer

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** WI-24 / ADR-014: class entries trust one class (and its nested classes), nothing around it. */
class ClassAllowListTest {
  @TempDir lateinit var tmp: Path

  private val javaLang = AllowListEntry("java.lang")

  private fun allowList(vararg entries: AllowListEntry) = AllowList("v1", entries.toList())

  /** Library classes in package `lib`, a sub-package and a lookalike name, all in the jar. */
  private val library =
      mapOf(
          "lib.Out" to
              """
              package lib;
              public class Out {
                public static class Inner { public static class Deep {} }
                public Object other() { return new Other(); }
              }
              """
                  .trimIndent(),
          "lib.Other" to "package lib; public class Other {}",
          "lib.Outer" to "package lib; public class Outer {}",
          "lib.sub.Sub" to "package lib.sub; public class Sub {}",
      )

  private fun analyze(body: String, allowList: AllowList): List<UnsafeReason> =
      SafetyAnalyzer()
          .analyze(
              CompiledJars.build(
                  tmp,
                  "p.jar",
                  mapOf("demo.Hello" to CompiledJars.pipeline("demo.Hello", body = body)) + library,
              ),
              allowList,
          )
          .pipelines
          .single()
          .reasons

  private fun violations(reasons: List<UnsafeReason>) =
      reasons.filterIsInstance<UnsafeReason.NotAllowListed>().map { it.className }

  @Test
  fun `a class entry trusts that class`() {
    val body = "new lib.Out();"

    assertTrue("lib.Out" in violations(analyze(body, allowList(javaLang))))
    assertEquals(listOf(), analyze(body, allowList(javaLang, ClassEntry("lib.Out"))))
  }

  @Test
  fun `a class entry trusts nested classes at any depth`() {
    val reasons =
        analyze(
            "new lib.Out.Inner(); new lib.Out.Inner.Deep();",
            allowList(javaLang, ClassEntry("lib.Out")),
        )

    assertEquals(listOf(), reasons)
  }

  @Test
  fun `a class entry does not trust other classes of the same package`() {
    val reasons = analyze("new lib.Other();", allowList(javaLang, ClassEntry("lib.Out")))

    assertEquals(listOf("lib.Other"), violations(reasons))
  }

  @Test
  fun `a class entry does not trust a class whose name merely starts with the entry`() {
    val reasons = analyze("new lib.Outer();", allowList(javaLang, ClassEntry("lib.Out")))

    assertEquals(listOf("lib.Outer"), violations(reasons))
  }

  @Test
  fun `a class entry does not trust sub-packages of its package`() {
    val reasons = analyze("new lib.sub.Sub();", allowList(javaLang, ClassEntry("lib.Out")))

    assertEquals(listOf("lib.sub.Sub"), violations(reasons))
  }

  @Test
  fun `a trusted class is not expanded`() {
    // lib.Out refers to lib.Other inside; trusting lib.Out does not make that a violation.
    val reasons = analyze("new lib.Out().other();", allowList(javaLang, ClassEntry("lib.Out")))

    assertEquals(listOf(), reasons)
  }

  @Test
  fun `class and package entries combine`() {
    val reasons =
        analyze(
            "new lib.Out(); new lib.sub.Sub(); new lib.Other();",
            allowList(javaLang, ClassEntry("lib.Out"), AllowListEntry("lib.sub")),
        )

    assertEquals(listOf("lib.Other"), violations(reasons))
  }

  @Test
  fun `a package entry named like a class does not trust the class`() {
    val reasons = analyze("new lib.Out();", allowList(javaLang, AllowListEntry("lib.Out")))

    assertTrue("lib.Out" in violations(reasons))
  }

  @Test
  fun `an allowed class still fails on its IO sensitive constructors`() {
    val printStream = allowList(javaLang, ClassEntry("java.io.PrintStream"))

    val stdout = analyze("System.out.println(\"hi\");", printStream)
    val opensFile =
        analyze("try { new java.io.PrintStream(\"out.txt\"); } catch (Exception e) {}", printStream)

    assertEquals(listOf(), stdout)
    assertEquals(listOf(), violations(opensFile))
    assertEquals(
        listOf("java.io.PrintStream.<init>(Ljava/lang/String;)V"),
        opensFile.filterIsInstance<UnsafeReason.IoSensitiveMember>().map { it.member },
    )
  }

  @Test
  fun `other classes of the package of an allowed class stay unsafe`() {
    val printStream = allowList(javaLang, ClassEntry("java.io.PrintStream"))

    val reasons =
        analyze(
            "new java.io.File(\"x\"); try { new java.io.FileInputStream(\"x\"); } catch (Exception e) {}",
            printStream,
        )

    assertTrue(violations(reasons).containsAll(listOf("java.io.File", "java.io.FileInputStream")))
  }
}

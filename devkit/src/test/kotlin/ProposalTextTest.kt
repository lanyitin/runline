package dev.lawlan.runline.devkit

import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.IoAccess
import dev.lawlan.runline.core.IoCategory
import dev.lawlan.runline.runner.RecordedIo
import dev.lawlan.runline.runner.RecordedSummary
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProposalTextTest {
  private fun summary(
      category: IoCategory,
      target: String,
      access: IoAccess,
      scope: FileScope? = null,
      count: Long = 1,
      rejected: Boolean = false,
  ) = RecordedSummary(category, scope, target, access, rejected, count, 1, count)

  private fun text(
      vararg summary: RecordedSummary,
      runSucceeded: Boolean = true,
  ): String {
    val recording = RecordedIo(10, summary.sumOf { it.count }, emptyList(), summary.toList())
    return ProposalText.render(
        "com.example.Report",
        "report",
        MetadataProposal.from(recording),
        recording,
        runSucceeded,
    )
  }

  private val busy =
      arrayOf(
          summary(IoCategory.FILE, "", IoAccess.READ, FileScope.PIPELINE_SHARED, count = 3),
          summary(IoCategory.FILE, "", IoAccess.WRITE, FileScope.PIPELINE_SHARED, count = 2),
          summary(IoCategory.FILE, "", IoAccess.READ, FileScope.RUN_PRIVATE),
          summary(IoCategory.NETWORK, "api.example.com", IoAccess.WRITE, count = 4),
          summary(IoCategory.PROCESS, "git", IoAccess.WRITE),
      )

  @Test
  fun `names the pipeline and says the limits were relaxed and the run is not a normal one`() {
    val text = text(*busy)

    assertTrue("com.example.Report" in text, text)
    assertTrue("report" in text, text)
    assertTrue("錄製模式" in text && "放寬" in text, text)
    assertTrue("不代表一般模式" in text, text)
  }

  @Test
  fun `states what the proposal covers and what it does not`() {
    val text = text(*busy)

    assertTrue("只涵蓋" in text && "走過的路徑" in text, text)
    assertTrue("直接使用 JDK 的 IO 類別不被錄製" in text, text)
    assertTrue("不含共享資源" in text && "參數" in text && "trigger" in text, text)
    assertTrue("不會自動套用" in text, text)
  }

  @Test
  fun `a table shows each use with how much it was used`() {
    val text = text(*busy)

    assertTrue("PIPELINE_SHARED" in text && "READ_WRITE" in text, text)
    assertTrue("讀 3 次" in text && "寫 2 次" in text, text)
    assertTrue("api.example.com" in text && "4 次" in text, text)
    assertTrue("git" in text, text)
  }

  @Test
  fun `gives the Kotlin members to put into the pipeline definition`() {
    val text = text(*busy)

    val expected =
        """
        files = [
            FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE),
            FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_ONLY),
        ],
        network = AccessLimit(allow = ["api.example.com"]),
        processes = AccessLimit(allow = ["git"]),
        """
            .trimIndent()
    assertTrue(expected in text, text)
  }

  @Test
  fun `gives the Java members to put into the pipeline definition`() {
    val text = text(*busy)

    val expected =
        """
        files = {
            @FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE),
            @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_ONLY)
        },
        network = @AccessLimit(allow = {"api.example.com"}),
        processes = @AccessLimit(allow = {"git"})
        """
            .trimIndent()
    assertTrue(expected in text, text)
  }

  @Test
  fun `a run without any IO proposes that nothing is allowed, which is not the same as unrestricted`() {
    val text = text()

    assertTrue("files = []," in text, text)
    assertTrue("network = AccessLimit(allow = [])," in text, text)
    assertTrue("processes = AccessLimit(allow = [])," in text, text)
    assertTrue("files = {}," in text, text)
    assertTrue("network = @AccessLimit(allow = {})," in text, text)
    assertTrue("processes = @AccessLimit(allow = {})" in text, text)
    assertTrue("不允許" in text, text)
    assertFalse("unrestricted" in text.replace("AccessLimit(unrestricted", ""), text)
  }

  @Test
  fun `rejected boundary violations are counted but not proposed`() {
    val text =
        text(
            summary(
                IoCategory.FILE,
                "",
                IoAccess.WRITE,
                FileScope.RUN_PRIVATE,
                count = 2,
                rejected = true,
            )
        )

    assertTrue("2 筆" in text && "被拒絕" in text, text)
    assertTrue("files = []," in text, text)
  }

  @Test
  fun `a run that did not succeed says the proposal only reflects what it did up to the failure`() {
    val text = text(*busy, runSucceeded = false)

    assertTrue("未成功" in text, text)
  }

  @Test
  fun `quotes and backslashes in a host or command are escaped in the members`() {
    val text = text(summary(IoCategory.PROCESS, "a\"b\\c\$d", IoAccess.WRITE))

    assertTrue("""processes = AccessLimit(allow = ["a\"b\\c\${'$'}d"]),""" in text, text)
    assertTrue("""processes = @AccessLimit(allow = {"a\"b\\c${'$'}d"})""" in text, text)
  }
}

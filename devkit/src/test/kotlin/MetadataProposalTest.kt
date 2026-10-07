package dev.lawlan.runline.devkit

import dev.lawlan.runline.core.FileMode
import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.IoAccess
import dev.lawlan.runline.core.IoCategory
import dev.lawlan.runline.runner.RecordedIo
import dev.lawlan.runline.runner.RecordedSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataProposalTest {
  private fun recording(vararg summary: RecordedSummary) =
      RecordedIo(
          maxEvents = 10,
          total = summary.sumOf { it.count },
          events = emptyList(),
          summary = summary.toList(),
      )

  private fun file(scope: FileScope, access: IoAccess, rejected: Boolean = false, count: Long = 1) =
      RecordedSummary(IoCategory.FILE, scope, "", access, rejected, count, 1, count)

  private fun net(host: String) =
      RecordedSummary(IoCategory.NETWORK, null, host, IoAccess.WRITE, false, 1, 1, 1)

  private fun proc(command: String) =
      RecordedSummary(IoCategory.PROCESS, null, command, IoAccess.WRITE, false, 1, 1, 1)

  @Test
  fun `nothing recorded proposes that nothing is allowed`() {
    val proposal = MetadataProposal.from(recording())

    assertTrue(proposal.files.isEmpty())
    assertTrue(proposal.hosts.isEmpty())
    assertTrue(proposal.commands.isEmpty())
  }

  @Test
  fun `a scope that was only read is read-only`() {
    val proposal = MetadataProposal.from(recording(file(FileScope.PIPELINE_SHARED, IoAccess.READ)))

    assertEquals(mapOf(FileScope.PIPELINE_SHARED to FileMode.READ_ONLY), proposal.files)
  }

  @Test
  fun `any write or delete makes the scope writable even if it was also read`() {
    val proposal =
        MetadataProposal.from(
            recording(
                file(FileScope.RUN_PRIVATE, IoAccess.READ, count = 9),
                file(FileScope.RUN_PRIVATE, IoAccess.WRITE),
                file(FileScope.PIPELINE_SHARED, IoAccess.READ),
            )
        )

    assertEquals(
        mapOf(
            FileScope.RUN_PRIVATE to FileMode.READ_WRITE,
            FileScope.PIPELINE_SHARED to FileMode.READ_ONLY,
        ),
        proposal.files,
    )
  }

  @Test
  fun `rejected boundary violations are not part of the proposal`() {
    val proposal =
        MetadataProposal.from(
            recording(
                file(FileScope.PIPELINE_SHARED, IoAccess.WRITE, rejected = true),
                file(FileScope.RUN_PRIVATE, IoAccess.READ, rejected = true),
            )
        )

    assertTrue(proposal.files.isEmpty())
  }

  @Test
  fun `hosts and commands are the ones that were used, sorted`() {
    val proposal =
        MetadataProposal.from(
            recording(
                net("b.example"),
                net("a.example"),
                proc("git"),
                proc("echo"),
                net("a.example"),
            )
        )

    assertEquals(listOf("a.example", "b.example"), proposal.hosts.toList())
    assertEquals(listOf("echo", "git"), proposal.commands.toList())
  }

  @Test
  fun `hosts are compared without case`() {
    val proposal = MetadataProposal.from(recording(net("Example.COM"), net("example.com")))

    assertEquals(listOf("example.com"), proposal.hosts.toList())
  }

  private fun resource(name: String, type: String, access: IoAccess, rejected: Boolean = false) =
      RecordedSummary(IoCategory.RESOURCE, null, name, access, rejected, 1, 1, 1, type)

  @Test
  fun `a resource that was used is proposed by name with its type, however often and however`() {
    val proposal =
        MetadataProposal.from(
            recording(
                resource("log", "file", IoAccess.WRITE),
                resource("log", "file", IoAccess.READ),
                resource("audit", "file", IoAccess.READ),
            )
        )

    assertEquals(mapOf("audit" to "file", "log" to "file"), proposal.resources)
    assertEquals(listOf("audit", "log"), proposal.resources.keys.toList())
  }

  @Test
  fun `no resource used proposes no resource declaration`() {
    assertTrue(MetadataProposal.from(recording(net("a.example"))).resources.isEmpty())
  }
}

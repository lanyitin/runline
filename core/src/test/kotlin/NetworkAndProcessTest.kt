package dev.lawlan.runline.core

import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class NetworkAndProcessTest {
  @TempDir lateinit var tmp: Path

  private fun context(network: AccessPolicy, processes: AccessPolicy): PipelineContext =
      RestrictedContext(
          PipelineMetadata("demo", emptyList(), emptyMap(), network, processes, emptySet()),
          emptyMap(),
          tmp,
          tmp,
      )

  @Test
  fun `unrestricted network connects anywhere`() {
    ServerSocket(0).use { server ->
      context(AccessPolicy.Unrestricted, AccessPolicy.Unrestricted)
          .network
          .connect("localhost", server.localPort)
          .use { assertTrue(it.isConnected) }
    }
  }

  @Test
  fun `network connects to an allowed host`() {
    ServerSocket(0).use { server ->
      context(AccessPolicy.Allow(setOf("localhost")), AccessPolicy.Unrestricted)
          .network
          .connect("LocalHost", server.localPort)
          .use { assertTrue(it.isConnected) }
    }
  }

  @Test
  fun `network rejects a host outside the allowed range, naming the pipeline`() {
    val ctx = context(AccessPolicy.Allow(setOf("api.example.com")), AccessPolicy.Unrestricted)
    ServerSocket(0).use { server ->
      val e =
          assertFailsWith<PipelineAccessDenied> {
            ctx.network.connect("localhost", server.localPort)
          }
      assertEquals(IoCategory.NETWORK, e.category)
      assertEquals("demo", e.pipeline)
      assertTrue(e.message!!.contains("demo") && e.message!!.contains("localhost"))
    }
  }

  @Test
  fun `an empty allow list rejects all network access`() {
    val ctx = context(AccessPolicy.Allow(emptySet()), AccessPolicy.Unrestricted)
    assertFailsWith<PipelineAccessDenied> { ctx.network.connect("localhost", 1) }
  }

  @Test
  fun `unrestricted processes run and report output and exit code`() {
    val r =
        context(AccessPolicy.Unrestricted, AccessPolicy.Unrestricted)
            .processes
            .run(listOf("sh", "-c", "echo out; echo err 1>&2; exit 3"))

    assertEquals(ProcessResult(3, "out\n", "err\n"), r)
  }

  @Test
  fun `allowed executable runs`() {
    val r =
        context(AccessPolicy.Unrestricted, AccessPolicy.Allow(setOf("echo")))
            .processes
            .run(listOf("echo", "hi"))
    assertEquals("hi\n", r.stdout)
  }

  @Test
  fun `executable outside the allowed range is rejected without running`() {
    val marker = tmp.resolve("marker")
    val ctx = context(AccessPolicy.Unrestricted, AccessPolicy.Allow(setOf("echo")))

    val e =
        assertFailsWith<PipelineAccessDenied> {
          ctx.processes.run(listOf("touch", marker.toString()))
        }

    assertEquals(IoCategory.PROCESS, e.category)
    assertTrue(e.message!!.contains("demo") && e.message!!.contains("touch"))
    assertFalse(java.nio.file.Files.exists(marker))
  }

  @Test
  fun `an empty command is rejected`() {
    val ctx = context(AccessPolicy.Unrestricted, AccessPolicy.Unrestricted)
    assertFailsWith<IllegalArgumentException> { ctx.processes.run(emptyList()) }
  }
}

package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.*
import dev.lawlan.runline.engine.support.AllowListRig
import dev.lawlan.runline.engine.support.PipelineJars
import io.opentelemetry.api.OpenTelemetry
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

/**
 * A verdict is stored only under the allow list version that is still current, so a change of the
 * list that rejudges every stored definition cannot miss an upload that was being judged at the
 * same moment (WI-10, 06-data-model).
 */
class UploadAllowListConsistencyTest {
  private val rig = AllowListRig(listOf(AllowListEntry("java.lang")))

  private fun newArtifact(version: String?, label: String): NewArtifact {
    val jar =
        PipelineJars.build(
            rig.dir,
            "$label.jar",
            mapOf("demo.C" to PipelineJars.pipeline("demo.C", label)),
        )
    val size = Files.size(jar)
    return NewArtifact(
        label
            .padEnd(64, '0')
            .take(64)
            .map { if (it in "0123456789abcdef") it else 'a' }
            .joinToString(""),
        jar,
        size,
        "alice",
        rig.clock.instant(),
        listOf(
            NewDefinition(
                "demo.C",
                label,
                MetadataDoc(
                    emptyList(),
                    emptyList(),
                    AccessLimitDoc(false),
                    AccessLimitDoc(false),
                    emptyList(),
                ),
                Verdict.SAFE,
                emptyList(),
                version ?: "x",
            )
        ),
        requiredAllowListVersion = version,
    )
  }

  @Test
  fun `a verdict made under a replaced version is not stored`() {
    val artifact = newArtifact("1", "stale")
    rig.add("java.util")

    assertEquals(SaveResult.AllowListChanged, rig.artifacts.saveIfAbsent(artifact))

    assertNull(rig.artifacts.findByHash(artifact.contentHash))
  }

  @Test
  fun `a verdict made under the current version is stored`() {
    val artifact = newArtifact("1", "current")

    assertIs<SaveResult.Created>(rig.artifacts.saveIfAbsent(artifact))
  }

  @Test
  fun `without a required version nothing is checked`() {
    val artifact = newArtifact(null, "unchecked")
    rig.add("java.util")

    assertIs<SaveResult.Created>(rig.artifacts.saveIfAbsent(artifact))
  }

  @Test
  fun `storing a verdict waits while a change of the list is in progress`() {
    val artifact = newArtifact("1", "waiting")
    val inside = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val change = pool.submit {
        rig.store.change { session ->
          inside.countDown()
          finish.await(30, TimeUnit.SECONDS)
          val version = session.nextVersion()
          session.appendVersion(
              AllowListVersion(version, "root", rig.clock.instant(), VersionAction.ENTRY_ADDED, "x")
          )
        }
      }
      assertTrue(inside.await(10, TimeUnit.SECONDS))
      val save = pool.submit<SaveResult> { rig.artifacts.saveIfAbsent(artifact) }

      assertFailsWith<TimeoutException> { save.get(500, TimeUnit.MILLISECONDS) }
      finish.countDown()
      change.get(30, TimeUnit.SECONDS)

      assertEquals(SaveResult.AllowListChanged, save.get(30, TimeUnit.SECONDS))
    } finally {
      finish.countDown()
      pool.shutdownNow()
    }
  }

  /** The real provider, which lets something happen right after it has handed out a list. */
  private class ChangesAfterReading(
      private val real: dev.lawlan.runline.engine.artifact.AllowListProvider,
      private val between: () -> Unit,
  ) : dev.lawlan.runline.engine.artifact.AllowListProvider {
    private var done = false

    override fun current(): AllowList =
        real.current().also {
          if (!done) {
            done = true
            between()
          }
        }
  }

  @Test
  fun `an upload that was judged while the list changed is judged again under the new version`() {
    val racing =
        UploadService(
            JarExpansionGuard(JarLimits(1000, 64L * 1024 * 1024, 256L * 1024 * 1024)),
            RunnerPipelineNameRule(),
            SafetyAnalyzer(),
            ChangesAfterReading(rig.provider) { rig.add("java.util") },
            rig.artifacts,
            UploadTelemetry(OpenTelemetry.noop()),
            Clock.systemUTC(),
        )
    val jar =
        PipelineJars.build(
            rig.dir,
            "racing.jar",
            mapOf(
                "demo.R" to
                    PipelineJars.pipeline(
                        "demo.R",
                        "racing",
                        body = "new java.util.ArrayList<String>();",
                    )
            ),
        )

    val result =
        UploadStaging(Files.createTempDirectory(rig.dir, "stage"))
            .stage(Files.newInputStream(jar), Long.MAX_VALUE)
            .use { racing.upload(it, "alice") }

    val definition = assertIs<UploadResult.Created>(result).artifact.definitions.single()
    assertEquals("2", definition.allowListVersion)
    assertEquals(Verdict.SAFE, definition.verdict)
    assertEquals(2, rig.admin.current().number)
  }
}

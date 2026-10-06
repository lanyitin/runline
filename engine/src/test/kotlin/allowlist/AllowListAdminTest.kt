package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.ClassEntry
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.ReasonKind
import dev.lawlan.runline.engine.config.InitialAllowList
import dev.lawlan.runline.engine.support.AllowListRig
import dev.lawlan.runline.engine.support.StoredPipelines
import java.time.Duration
import kotlin.test.*

/** The administration of the allow list and the judging again it triggers (WI-10). */
class AllowListAdminTest {
  private val rig = AllowListRig(listOf(AllowListEntry("java.lang")))

  private fun applied(result: ChangeResult) = assertIs<ChangeResult.Applied>(result)

  private fun previewed(result: ChangeResult) = assertIs<ChangeResult.Previewed>(result)

  // ---- initial content ----

  @Test
  fun `the first version holds the initial entries, attributed to the system`() {
    val snapshot = rig.admin.current()

    assertEquals(1, snapshot.number)
    assertEquals("system", snapshot.version.changedBy)
    assertEquals(VersionAction.INITIAL, snapshot.version.action)
    assertEquals(listOf(AllowListEntry("java.lang")), snapshot.entries.map { it.toEntry() })
  }

  @Test
  fun `initialising again changes nothing, so a restart never overwrites the administrator's list`() {
    rig.add("java.util")

    val seeded = rig.admin.initialize(InitialAllowList(listOf(AllowListEntry("kotlin")), true))

    assertFalse(seeded)
    assertEquals(
        listOf("java.lang", "java.util"),
        rig.admin.current().entries.map { it.name },
    )
    assertEquals(2, rig.admin.current().number)
  }

  @Test
  fun `an initial list that names the same entry twice keeps the first one`() {
    val other =
        AllowListRig(listOf(AllowListEntry("kotlin", exactOnly = true), AllowListEntry("kotlin")))

    val entries = other.admin.current().entries
    assertEquals(listOf(AllowListEntry("kotlin", exactOnly = true)), entries.map { it.toEntry() })
  }

  // ---- adding, removing, modifying ----

  @Test
  fun `adding an entry makes a new version that records who and when`() {
    rig.clock.advance(Duration.ofMinutes(5))

    val result = applied(rig.add("java.util"))

    assertEquals(2, result.version!!.version)
    assertEquals("root", result.version!!.changedBy)
    assertEquals(rig.clock.instant(), result.version!!.changedAt)
    assertEquals(VersionAction.ENTRY_ADDED, result.version!!.action)
    val entry = result.entry!!
    assertEquals(EntryKind.PACKAGE, entry.kind)
    assertEquals("root", entry.createdBy)
    assertEquals(rig.clock.instant(), entry.createdAt)
    assertEquals("2", rig.provider.current().version)
    assertEquals(
        listOf(AllowListEntry("java.lang"), AllowListEntry("java.util")),
        rig.provider.current().entries,
    )
  }

  @Test
  fun `a class entry and a package entry are kept apart`() {
    applied(rig.add("java.io.PrintStream", EntryKind.CLASS))
    applied(rig.add("java.io.PrintStream", EntryKind.PACKAGE))

    assertEquals(
        listOf(
            EntryKind.PACKAGE to "java.lang",
            EntryKind.CLASS to "java.io.PrintStream",
            EntryKind.PACKAGE to "java.io.PrintStream",
        ),
        rig.admin.current().entries.map { it.kind to it.name },
    )
    assertEquals(ClassEntry("java.io.PrintStream"), rig.provider.current().entries[1])
  }

  @Test
  fun `adding an entry judges every stored definition again under the new version`() {
    val needsUtil = rig.uploadNeedingUtil("needs-util")
    val plain = rig.upload("plain")
    assertEquals(Verdict.UNSAFE, rig.record(needsUtil, "needs-util").verdict)
    assertEquals("1", rig.record(plain, "plain").allowListVersion)

    val result = applied(rig.add("java.util"))

    assertEquals(2, result.impact.examinedDefinitions)
    assertEquals(1, result.impact.becameSafe)
    assertEquals(0, result.impact.becameUnsafe)
    val now = rig.record(needsUtil, "needs-util")
    assertEquals(Verdict.SAFE, now.verdict)
    assertEquals(emptyList(), now.reasons)
    assertEquals("2", now.allowListVersion)
    assertEquals("2", rig.record(plain, "plain").allowListVersion)
    assertEquals(VersionAction.ENTRY_ADDED, rig.admin.versions().first().action)
    assertEquals(1, rig.admin.versions().first().becameSafe)
  }

  @Test
  fun `removing an entry makes pipelines unsafe and withdraws their permission to run unsafe`() {
    rig.add("java.util")
    val allowed = rig.uploadNeedingUtil("allowed")
    val other = rig.uploadNeedingUtil("other")
    rig.allowUnsafe(allowed, "allowed", true)
    rig.clock.advance(Duration.ofHours(1))

    val result =
        applied(rig.admin.change(AllowListChange.Remove(EntryKind.PACKAGE, "java.util"), rig.ann))

    assertEquals(2, result.impact.becameUnsafe)
    val change = result.impact.changes.single { it.pipeline == "allowed" }
    assertTrue(change.allowUnsafeExecution)
    assertTrue(change.unsafeExecutionRevoked)
    assertFalse(result.impact.changes.single { it.pipeline == "other" }.unsafeExecutionRevoked)
    val revoked = rig.record(allowed, "allowed")
    assertEquals(Verdict.UNSAFE, revoked.verdict)
    assertFalse(revoked.allowUnsafeExecution)
    assertEquals("ann", revoked.unsafeSettingSetBy)
    assertTrue(
        revoked.reasons.any {
          it.kind == ReasonKind.NOT_ALLOW_LISTED && it.className == "java.util.ArrayList"
        }
    )
  }

  @Test
  fun `a definition that stays unsafe or becomes safe keeps its permission setting`() {
    val stays = rig.uploadNeedingUtil("stays")
    val becomes = rig.uploadNeedingUtil("becomes")
    rig.allowUnsafe(stays, "stays", true)
    rig.allowUnsafe(becomes, "becomes", true)

    // java.util.ArrayList still missing: stays unsafe. Adding java.util makes both safe.
    applied(rig.add("java.text"))
    assertTrue(rig.record(stays, "stays").allowUnsafeExecution)
    assertEquals(Verdict.UNSAFE, rig.record(stays, "stays").verdict)
    applied(rig.add("java.util"))

    assertEquals(Verdict.SAFE, rig.record(becomes, "becomes").verdict)
    assertTrue(rig.record(becomes, "becomes").allowUnsafeExecution)
    assertEquals("root", rig.record(becomes, "becomes").unsafeSettingSetBy)
  }

  @Test
  fun `modifying an entry to this package only changes verdicts and makes a version`() {
    rig.add("java.util.concurrent", EntryKind.PACKAGE)
    val concurrent = rig.upload("conc", "new java.util.concurrent.atomic.AtomicInteger();")
    assertEquals(Verdict.SAFE, rig.record(concurrent, "conc").verdict)

    val result =
        applied(
            rig.admin.change(
                AllowListChange.Modify(EntryKind.PACKAGE, "java.util.concurrent", exactOnly = true),
                rig.root,
            )
        )

    assertEquals(VersionAction.ENTRY_CHANGED, result.version!!.action)
    assertTrue(result.entry!!.exactOnly)
    assertEquals(Verdict.UNSAFE, rig.record(concurrent, "conc").verdict)
    assertEquals(1, result.impact.becameUnsafe)
  }

  @Test
  fun `modifying an entry can rename it, keeping who created it`() {
    rig.add("java.utl")
    val entry = rig.admin.find(EntryKind.PACKAGE, "java.utl")!!
    rig.clock.advance(Duration.ofMinutes(1))

    val result =
        applied(
            rig.admin.change(
                AllowListChange.Modify(EntryKind.PACKAGE, "java.utl", newName = "java.util"),
                rig.ann,
            )
        )

    assertNull(rig.admin.find(EntryKind.PACKAGE, "java.utl"))
    val renamed = assertNotNull(rig.admin.find(EntryKind.PACKAGE, "java.util"))
    assertEquals("root", renamed.createdBy)
    assertEquals(entry.createdAt, renamed.createdAt)
    assertEquals("ann", renamed.updatedBy)
    assertEquals(renamed, result.entry)
  }

  // ---- hints and refusals ----

  @Test
  fun `names that are not valid are refused with nothing changed`() {
    val bad = rig.admin.change(AllowListChange.Add(EntryKind.PACKAGE, "java..util"), rig.root)
    val noPackage = rig.admin.change(AllowListChange.Add(EntryKind.CLASS, "PrintStream"), rig.root)
    val exactClass =
        rig.admin.change(AllowListChange.Add(EntryKind.CLASS, "a.B", exactOnly = true), rig.root)

    assertEquals(InvalidChange.NAME, assertIs<ChangeResult.Invalid>(bad).problem)
    assertTrue("java..util" in assertIs<ChangeResult.Invalid>(bad).message)
    assertEquals(InvalidChange.NAME, assertIs<ChangeResult.Invalid>(noPackage).problem)
    assertEquals(
        InvalidChange.EXACT_ONLY_ON_CLASS,
        assertIs<ChangeResult.Invalid>(exactClass).problem,
    )
    assertEquals(1, rig.admin.current().number)
  }

  @Test
  fun `an entry that already exists is a duplicate, whatever its flag`() {
    val result =
        rig.admin.change(
            AllowListChange.Add(EntryKind.PACKAGE, "java.lang", exactOnly = true),
            rig.root,
        )

    assertEquals("java.lang", assertIs<ChangeResult.Duplicate>(result).existing.name)
    assertEquals(1, rig.admin.current().number)
  }

  @Test
  fun `an entry that an existing one already covers is refused, naming that entry`() {
    rig.add("java.io.PrintStream", EntryKind.CLASS)
    val coveredPackage = rig.add("java.lang.invoke")
    val coveredClass = rig.add("java.lang.String", EntryKind.CLASS)
    val nested = rig.add("java.io.PrintStream\$Inner", EntryKind.CLASS)

    assertEquals("java.lang", assertIs<ChangeResult.Covered>(coveredPackage).by.name)
    assertEquals("java.lang", assertIs<ChangeResult.Covered>(coveredClass).by.name)
    assertEquals("java.io.PrintStream", assertIs<ChangeResult.Covered>(nested).by.name)
    assertEquals(2, rig.admin.current().number)
  }

  @Test
  fun `an entry that covers existing ones is accepted and the redundant ones are pointed out`() {
    rig.add("java.util.concurrent")
    rig.add("java.util.function", EntryKind.PACKAGE)
    rig.add("java.util.List", EntryKind.CLASS)

    val result = applied(rig.add("java.util"))

    assertEquals(
        listOf("java.util.concurrent", "java.util.function", "java.util.List"),
        result.redundant.map { it.name },
    )
    assertEquals(5, rig.admin.current().entries.size)
  }

  @Test
  fun `changing or removing an entry that does not exist is not found`() {
    assertEquals(
        ChangeResult.NotFound,
        rig.admin.change(AllowListChange.Remove(EntryKind.CLASS, "java.lang"), rig.root),
    )
    assertEquals(
        ChangeResult.NotFound,
        rig.admin.change(
            AllowListChange.Modify(EntryKind.PACKAGE, "no.such", exactOnly = true),
            rig.root,
        ),
    )
    assertEquals(1, rig.admin.current().number)
  }

  @Test
  fun `a modification must change something and may not rename into an existing or covered entry`() {
    rig.add("java.util")
    val nothing = rig.admin.change(AllowListChange.Modify(EntryKind.PACKAGE, "java.util"), rig.root)
    val onClass =
        rig.admin.change(AllowListChange.Modify(EntryKind.CLASS, "a.B", exactOnly = true), rig.root)
    rig.add("a.B", EntryKind.CLASS)
    val classExact =
        rig.admin.change(AllowListChange.Modify(EntryKind.CLASS, "a.B", exactOnly = true), rig.root)
    val intoExisting =
        rig.admin.change(
            AllowListChange.Modify(EntryKind.PACKAGE, "java.util", newName = "java.lang"),
            rig.root,
        )
    rig.add("java.text")
    val intoCovered =
        rig.admin.change(
            AllowListChange.Modify(EntryKind.PACKAGE, "java.text", newName = "java.lang.text"),
            rig.root,
        )

    assertEquals(InvalidChange.NOTHING_TO_CHANGE, assertIs<ChangeResult.Invalid>(nothing).problem)
    assertIs<ChangeResult.NotFound>(onClass)
    assertEquals(
        InvalidChange.EXACT_ONLY_ON_CLASS,
        assertIs<ChangeResult.Invalid>(classExact).problem,
    )
    assertEquals("java.lang", assertIs<ChangeResult.Duplicate>(intoExisting).existing.name)
    assertEquals("java.lang", assertIs<ChangeResult.Covered>(intoCovered).by.name)
  }

  // ---- previews ----

  @Test
  fun `a preview lists what would flip and writes nothing`() {
    val needsUtil = rig.uploadNeedingUtil("needs-util")
    rig.upload("plain")

    val result =
        previewed(
            rig.admin.change(
                AllowListChange.Add(EntryKind.PACKAGE, "java.util"),
                rig.root,
                ChangeMode.PREVIEW,
            )
        )

    assertEquals(1, result.currentVersion)
    val change = result.impact.changes.single()
    assertEquals(needsUtil, change.contentHash)
    assertEquals("needs-util", change.pipeline)
    assertEquals(Verdict.UNSAFE, change.from)
    assertEquals(Verdict.SAFE, change.to)
    assertEquals(2, result.impact.examinedDefinitions)
    assertEquals(1, rig.admin.current().number)
    assertEquals(listOf("java.lang"), rig.admin.current().entries.map { it.name })
    assertEquals(Verdict.UNSAFE, rig.record(needsUtil, "needs-util").verdict)
    assertEquals("1", rig.record(needsUtil, "needs-util").allowListVersion)
  }

  @Test
  fun `a preview of a removal says whose permission would be withdrawn`() {
    rig.add("java.util")
    val allowed = rig.uploadNeedingUtil("allowed")
    rig.allowUnsafe(allowed, "allowed", true)

    val result =
        previewed(
            rig.admin.change(
                AllowListChange.Remove(EntryKind.PACKAGE, "java.util"),
                rig.root,
                ChangeMode.PREVIEW,
            )
        )

    assertTrue(result.impact.changes.single().unsafeExecutionRevoked)
    assertTrue(rig.record(allowed, "allowed").allowUnsafeExecution)
  }

  @Test
  fun `a preview tells the same as applying the change`() {
    rig.upload("plain")
    rig.uploadNeedingUtil("needs-util")
    val change = AllowListChange.Add(EntryKind.PACKAGE, "java.util")

    val preview = previewed(rig.admin.change(change, rig.root, ChangeMode.PREVIEW))
    val real = applied(rig.admin.change(change, rig.root, ChangeMode.APPLY))

    assertEquals(preview.impact, real.impact)
  }

  @Test
  fun `a refused change is refused in a preview too`() {
    val result =
        rig.admin.change(
            AllowListChange.Add(EntryKind.PACKAGE, "java.lang"),
            rig.root,
            ChangeMode.PREVIEW,
        )

    assertIs<ChangeResult.Duplicate>(result)
  }

  // ---- all or nothing ----

  @Test
  fun `when judging again fails the list stays as it was`() {
    val needsUtil = rig.uploadNeedingUtil("needs-util")
    val broken =
        dev.lawlan.runline.engine.allowlist.AllowListAdmin(
            rig.store,
            dev.lawlan.runline.analyzer.SafetyAnalyzer(),
            rig.clock,
            rig.telemetry,
            rig.dir.resolve("missing-scratch-directory"),
        )

    assertFails { broken.change(AllowListChange.Add(EntryKind.PACKAGE, "java.util"), rig.root) }

    assertEquals(1, rig.admin.current().number)
    assertEquals(listOf("java.lang"), rig.admin.current().entries.map { it.name })
    assertEquals(Verdict.UNSAFE, rig.record(needsUtil, "needs-util").verdict)
    assertEquals("1", rig.record(needsUtil, "needs-util").allowListVersion)
    assertEquals(1, rig.admin.versions().size)
  }

  // ---- judging again on demand ----

  @Test
  fun `a manual recheck judges again with the current list and makes no new version`() {
    val hash = rig.upload("fine")
    // A verdict stored under older rules: the stored jar is safe now but was recorded as unsafe.
    rig.dataSource.connection.use {
      it.createStatement().use { s ->
        s.executeUpdate(
            "UPDATE pipeline_definition SET verdict = 'UNSAFE', allow_list_version = 'config'"
        )
      }
    }
    assertEquals("config", rig.record(hash, "fine").allowListVersion)

    val result = applied(rig.admin.change(AllowListChange.Recheck, rig.root))

    assertNull(result.version)
    assertEquals(1, result.impact.becameSafe)
    assertEquals(Verdict.SAFE, rig.record(hash, "fine").verdict)
    assertEquals("1", rig.record(hash, "fine").allowListVersion)
    assertEquals(1, rig.admin.current().number)
    assertEquals(1, rig.admin.versions().size)
  }

  @Test
  fun `a recheck can be previewed and obeys the same rules about permissions`() {
    val hash = rig.uploadNeedingUtil("needs-util")
    rig.allowUnsafe(hash, "needs-util", true)
    // A verdict stored under older rules: safe on record, although the list does not trust
    // java.util.
    rig.dataSource.connection.use {
      it.createStatement().use { s ->
        s.executeUpdate("UPDATE pipeline_definition SET verdict = 'SAFE', reasons = '[]'::jsonb")
      }
    }

    val preview = previewed(rig.admin.change(AllowListChange.Recheck, rig.root, ChangeMode.PREVIEW))

    val change = preview.impact.changes.single()
    assertEquals(Verdict.UNSAFE, change.to)
    assertTrue(change.unsafeExecutionRevoked)
    assertEquals(Verdict.SAFE, rig.record(hash, "needs-util").verdict)
    assertTrue(rig.record(hash, "needs-util").allowUnsafeExecution)

    applied(rig.admin.change(AllowListChange.Recheck, rig.ann))

    assertEquals(Verdict.UNSAFE, rig.record(hash, "needs-util").verdict)
    assertFalse(rig.record(hash, "needs-util").allowUnsafeExecution)
    assertEquals("ann", rig.record(hash, "needs-util").unsafeSettingSetBy)
  }

  @Test
  fun `a stored jar that cannot be read again is judged unsafe for that reason and counted`() {
    val stored = StoredPipelines(rig.artifacts, rig.dir)
    val hash =
        stored.save("broken", "broken", verdict = Verdict.SAFE, content = "not a jar".toByteArray())

    val result = applied(rig.add("java.util"))

    assertEquals(1, result.impact.unreadable)
    val record = rig.artifacts.findByHash(hash)!!.definitions.single()
    assertEquals(Verdict.UNSAFE, record.verdict)
    assertEquals(ReasonKind.UNREADABLE_CLASS, record.reasons.single().kind)
    assertEquals("2", record.allowListVersion)
  }

  // ---- history ----

  @Test
  fun `the history lists versions newest first, with what each did to the verdicts`() {
    rig.uploadNeedingUtil("needs-util")
    rig.add("java.util")
    rig.remove("java.util")

    val versions = rig.admin.versions()

    assertEquals(listOf(3L, 2L, 1L), versions.map { it.version })
    assertEquals(VersionAction.ENTRY_REMOVED, versions[0].action)
    assertEquals(1, versions[0].becameUnsafe)
    assertEquals(1, versions[1].becameSafe)
    assertEquals(1, versions[1].rejudgedDefinitions)
  }
}

package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.ClassEntry
import dev.lawlan.runline.analyzer.DefaultAllowList
import dev.lawlan.runline.analyzer.PackageEntry
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.config.InitialAllowList
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * The administrator's control of the allow list (WI-10). Every change is one database transaction:
 * the entries, the new version, and the judging again of every stored definition with the new list
 * either all happen or none do. A change can be previewed first: the same planning and the same
 * judging, but nothing is written and no lock is taken. Who changed what is recorded as the name of
 * the caller (ADR-012), never a token.
 */
class AllowListAdmin(
    private val store: AllowListStore,
    analyzer: SafetyAnalyzer,
    private val clock: Clock,
    private val telemetry: AllowListTelemetry,
    scratchDir: Path,
) {
  private val log = LoggerFactory.getLogger(AllowListAdmin::class.java)
  private val rejudger = Rejudger(analyzer, scratchDir)

  /**
   * Gives the allow list its first content unless it has one; true when it was written now. A
   * restart therefore never overwrites what an administrator has made of the list.
   */
  fun initialize(initial: InitialAllowList): Boolean {
    val seeded = store.change { session ->
      if (session.snapshot() != null) return@change false
      val at = clock.instant()
      val entries = initial.entries.distinctBy { it.kind to it.entryName }
      entries.forEach { session.insertEntry(it.kind, it.entryName, it.exactOnly, SYSTEM, at) }
      val source =
          if (initial.fromConfiguration) "configuration"
          else "the default allow list ${DefaultAllowList.VERSION}"
      session.appendVersion(
          AllowListVersion(
              1,
              SYSTEM,
              at,
              VersionAction.INITIAL,
              "Initial content from $source (${entries.size} entries)",
          )
      )
      true
    }
    if (seeded) log.info("Allow list initialised with version 1 ({} entries)", initial.entries.size)
    return seeded
  }

  fun current(): AllowListSnapshot =
      checkNotNull(store.current()) { "The allow list has not been initialised" }

  fun versions(limit: Int = 50): List<AllowListVersion> = store.versions(limit)

  fun find(kind: EntryKind, name: String): StoredEntry? =
      current().entries.firstOrNull { it.kind == kind && it.name == name }

  /** Carries out or previews [change] as [by]. A refusal changes nothing in either mode. */
  fun change(
      change: AllowListChange,
      by: ApiIdentity,
      mode: ChangeMode = ChangeMode.APPLY,
  ): ChangeResult {
    val result =
        telemetry.observe(change.operation, mode == ChangeMode.PREVIEW, by.name) {
          when (mode) {
            ChangeMode.PREVIEW -> store.read { preview(it, change) }
            ChangeMode.APPLY -> store.change { apply(it, change, by) }
          }
        }
    // Only after the transaction has committed: what is counted and logged did happen.
    if (result is ChangeResult.Applied) recordApplied(change, by, result)
    return result
  }

  private fun recordApplied(
      change: AllowListChange,
      by: ApiIdentity,
      applied: ChangeResult.Applied,
  ) {
    val impact = applied.impact
    telemetry.verdictChanges(impact.becameUnsafe, impact.becameSafe)
    log.info(
        "Allow list {} by {}: {} (version {}); {} definitions judged again, {} became unsafe, {} became safe, {} unreadable",
        change.operation,
        by.name,
        applied.version?.detail ?: "judged all definitions again",
        applied.version?.version ?: "unchanged",
        impact.examinedDefinitions,
        impact.becameUnsafe,
        impact.becameSafe,
        impact.unreadable,
    )
  }

  private fun preview(session: AllowListSession, change: AllowListChange): ChangeResult {
    val snapshot = checkNotNull(session.snapshot()) { "The allow list has not been initialised" }
    return when (val planned = plan(snapshot, change)) {
      is Planned.Refused -> planned.result
      is Planned.Go ->
          ChangeResult.Previewed(
              snapshot.number,
              rejudger.run(session, AllowList(snapshot.number.toString(), planned.entries), null),
              planned.redundant,
          )
    }
  }

  private fun apply(
      session: AllowListSession,
      change: AllowListChange,
      by: ApiIdentity,
  ): ChangeResult {
    val snapshot = checkNotNull(session.snapshot()) { "The allow list has not been initialised" }
    val planned =
        when (val p = plan(snapshot, change)) {
          is Planned.Refused -> return p.result
          is Planned.Go -> p
        }
    val at = clock.instant()
    // A change of the entries is a new version; judging again with the same entries is not.
    val number = if (planned.action != null) session.nextVersion() else snapshot.number
    planned.write(session, by.name, at)
    val impact =
        rejudger.run(
            session,
            AllowList(number.toString(), planned.entries),
            WriteContext(by.name, at),
        )
    val version =
        planned.action?.let {
          AllowListVersion(
                  number,
                  by.name,
                  at,
                  it,
                  planned.detail,
                  impact.examinedDefinitions,
                  impact.becameUnsafe,
                  impact.becameSafe,
              )
              .also(session::appendVersion)
        }
    val entry =
        planned.resulting?.let { resulting ->
          session.snapshot()!!.entries.first {
            it.kind == resulting.kind && it.name == resulting.entryName
          }
        }
    return ChangeResult.Applied(version, entry, impact, planned.redundant)
  }

  private sealed interface Planned {
    data class Refused(val result: ChangeResult) : Planned

    /** [entries] is the list as it will be; [write] puts the change into [session]. */
    class Go(
        val entries: List<AllowListEntry>,
        val action: VersionAction?,
        val detail: String,
        val resulting: AllowListEntry?,
        val redundant: List<StoredEntry>,
        val write: (AllowListSession, String, Instant) -> Unit,
    ) : Planned
  }

  private fun plan(snapshot: AllowListSnapshot, change: AllowListChange): Planned =
      when (change) {
        is AllowListChange.Add -> planAdd(snapshot, change)
        is AllowListChange.Modify -> planModify(snapshot, change)
        is AllowListChange.Remove -> planRemove(snapshot, change)
        AllowListChange.Recheck ->
            Planned.Go(
                snapshot.entries.map { it.toEntry() },
                null,
                "Judged all definitions again with the current list",
                null,
                emptyList(),
            ) { _, _, _ ->
            }
      }

  private fun planAdd(snapshot: AllowListSnapshot, add: AllowListChange.Add): Planned {
    val entry =
        when (val built = build(add.kind, add.name, add.exactOnly)) {
          is Built.Bad -> return Planned.Refused(built.result)
          is Built.Ok -> built.entry
        }
    snapshot.entries
        .firstOrNull { it.kind == add.kind && it.name == add.name }
        ?.let {
          return Planned.Refused(ChangeResult.Duplicate(it))
        }
    snapshot.entries
        .firstOrNull { it.toEntry().covers(entry) }
        ?.let {
          return Planned.Refused(ChangeResult.Covered(it))
        }
    return Planned.Go(
        snapshot.entries.map { it.toEntry() } + entry,
        VersionAction.ENTRY_ADDED,
        "Added ${describe(entry)}",
        entry,
        snapshot.entries.filter { entry.covers(it.toEntry()) },
    ) { session, by, at ->
      session.insertEntry(entry.kind, entry.entryName, entry.exactOnly, by, at)
    }
  }

  private fun planModify(snapshot: AllowListSnapshot, modify: AllowListChange.Modify): Planned {
    val existing =
        snapshot.entries.firstOrNull { it.kind == modify.kind && it.name == modify.name }
            ?: return Planned.Refused(ChangeResult.NotFound)
    val newName = modify.newName ?: existing.name
    val exactOnly = modify.exactOnly ?: existing.exactOnly
    val entry =
        when (val built = build(existing.kind, newName, exactOnly)) {
          is Built.Bad -> return Planned.Refused(built.result)
          is Built.Ok -> built.entry
        }
    if (newName == existing.name && exactOnly == existing.exactOnly) {
      return Planned.Refused(
          ChangeResult.Invalid(InvalidChange.NOTHING_TO_CHANGE, "沒有要修改的內容：請提供新的 name 或 exactOnly。")
      )
    }
    val others = snapshot.entries.filter { it !== existing }
    if (newName != existing.name) {
      others
          .firstOrNull { it.kind == existing.kind && it.name == newName }
          ?.let {
            return Planned.Refused(ChangeResult.Duplicate(it))
          }
      others
          .firstOrNull { it.toEntry().covers(entry) }
          ?.let {
            return Planned.Refused(ChangeResult.Covered(it))
          }
    }
    return Planned.Go(
        snapshot.entries.map { if (it === existing) entry else it.toEntry() },
        VersionAction.ENTRY_CHANGED,
        "Changed ${describe(existing.toEntry())} to ${describe(entry)}",
        entry,
        others.filter { entry.covers(it.toEntry()) },
    ) { session, by, at ->
      session.updateEntry(existing.kind, existing.name, newName, entry.exactOnly, by, at)
    }
  }

  private fun planRemove(snapshot: AllowListSnapshot, remove: AllowListChange.Remove): Planned {
    val existing =
        snapshot.entries.firstOrNull { it.kind == remove.kind && it.name == remove.name }
            ?: return Planned.Refused(ChangeResult.NotFound)
    return Planned.Go(
        snapshot.entries.filter { it !== existing }.map { it.toEntry() },
        VersionAction.ENTRY_REMOVED,
        "Removed ${describe(existing.toEntry())}",
        null,
        emptyList(),
    ) { session, _, _ ->
      session.deleteEntry(existing.kind, existing.name)
    }
  }

  private sealed interface Built {
    data class Ok(val entry: AllowListEntry) : Built

    data class Bad(val result: ChangeResult.Invalid) : Built
  }

  /** The analyzer's own validation decides what a valid name is. */
  private fun build(kind: EntryKind, name: String, exactOnly: Boolean): Built =
      try {
        when (kind) {
          EntryKind.PACKAGE -> Built.Ok(PackageEntry(name, exactOnly))
          EntryKind.CLASS ->
              if (exactOnly) {
                Built.Bad(
                    ChangeResult.Invalid(
                        InvalidChange.EXACT_ONLY_ON_CLASS,
                        "「僅此套件」只適用於套件條目，類別條目只放行該類別與其巢狀類別。",
                    )
                )
              } else {
                Built.Ok(ClassEntry(name))
              }
        }
      } catch (e: IllegalArgumentException) {
        Built.Bad(ChangeResult.Invalid(InvalidChange.NAME, e.message ?: "名稱不合法：'$name'"))
      }

  private fun describe(entry: AllowListEntry) =
      when (entry) {
        is PackageEntry ->
            "package ${entry.packageName}" + if (entry.exactOnly) " (this package only)" else ""
        is ClassEntry -> "class ${entry.className}"
      }

  private val AllowListChange.operation: String
    get() =
        when (this) {
          is AllowListChange.Add -> "add"
          is AllowListChange.Modify -> "modify"
          is AllowListChange.Remove -> "remove"
          AllowListChange.Recheck -> "recheck"
        }

  private val AllowListEntry.exactOnly: Boolean
    get() = (this as? PackageEntry)?.exactOnly ?: false

  private companion object {
    const val SYSTEM = "system"
  }
}

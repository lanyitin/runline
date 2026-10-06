package dev.lawlan.runline.analyzer

/** One administrator-maintained allow list entry: a package or a complete class. */
sealed interface AllowListEntry {
  /** Whether [className] (dotted, nested classes joined with `$`) is trusted by this entry. */
  fun coversClass(className: String): Boolean

  /** Whether [other] adds nothing next to this entry, because everything it trusts is trusted. */
  fun covers(other: AllowListEntry): Boolean
}

/**
 * A trusted package. Sub-packages are trusted too unless [exactOnly] is set ("this package only").
 */
data class PackageEntry(val packageName: String, val exactOnly: Boolean = false) : AllowListEntry {
  init {
    require(isDottedName(packageName)) {
      "Invalid allow list package '$packageName': expected dot-separated identifiers"
    }
  }

  override fun coversClass(className: String): Boolean {
    val pkg = className.substringBeforeLast('.', "")
    return if (exactOnly) pkg == packageName else isWithin(pkg, packageName)
  }

  override fun covers(other: AllowListEntry): Boolean =
      when (other) {
        is ClassEntry -> coversClass(other.className)
        is PackageEntry ->
            if (exactOnly) other.exactOnly && other.packageName == packageName
            else isWithin(other.packageName, packageName)
      }
}

/**
 * A trusted class (fully qualified, dotted, for example `java.io.PrintStream`). Only that class and
 * its nested classes (`Outer$Inner`) are trusted; other classes of its package and its package's
 * sub-packages are not (ADR-014). A class must have a package.
 */
data class ClassEntry(val className: String) : AllowListEntry {
  init {
    require(isDottedName(className) && '.' in className) {
      "Invalid allow list class '$className': expected a fully qualified class name"
    }
  }

  override fun coversClass(className: String) =
      className == this.className || className.startsWith("${this.className}$")

  override fun covers(other: AllowListEntry): Boolean =
      when (other) {
        is ClassEntry -> coversClass(other.className)
        is PackageEntry -> false
      }

  companion object {
    /**
     * Marks a class entry in the text forms of configuration (`class:java.io.PrintStream`), which
     * keeps it apart from a package entry whatever the name looks like.
     */
    const val TOKEN_PREFIX = "class:"
  }
}

/** [pkg] is [root] or one of its sub-packages. */
private fun isWithin(pkg: String, root: String) = pkg == root || pkg.startsWith("$root.")

/** Non-empty, dot-separated Java identifiers (no blanks, wildcards or empty segments). */
private fun isDottedName(name: String) =
    name.split('.').all { part ->
      part.isNotEmpty() &&
          Character.isJavaIdentifierStart(part.first()) &&
          part.all(Character::isJavaIdentifierPart)
    }

/** A package entry; the form every allow list used before class entries existed. */
fun AllowListEntry(packageName: String, exactOnly: Boolean = false): AllowListEntry =
    PackageEntry(packageName, exactOnly)

/** The allow list in force, identified by [version] so a verdict can be tied to the rules used. */
data class AllowList(val version: String, val entries: List<AllowListEntry>)

/** The IO categories whose limit can be missing. File access is always scoped (ADR-009). */
enum class IoCategory {
  NETWORK,
  PROCESSES,
}

/** One reason a pipeline is unsafe. Paths run from the pipeline class to the offending class. */
sealed interface UnsafeReason {
  /** The pipeline declares no limit, or declares itself unrestricted, for [category]. */
  data class UnrestrictedAccess(val category: IoCategory) : UnsafeReason

  /** [className] is outside the allow list; [path] is the dependency chain that reaches it. */
  data class NotAllowListed(val className: String, val path: List<String>) : UnsafeReason

  /**
   * [member] (for example `java.lang.System.exit`) is referenced by the last class on [path].
   * Applies even when the member's own package is on the allow list.
   */
  data class JvmExit(val member: String, val path: List<String>) : UnsafeReason

  /**
   * [member] (owner, name and descriptor, for example `java.lang.Runtime.exec(...)`) starts a
   * process, loads native code, or opens files or network directly (ADR-013). It is referenced by
   * the last class on [path]. Applies even when the member's own package is on the allow list.
   */
  data class IoSensitiveMember(val member: String, val path: List<String>) : UnsafeReason

  /** The class file of [className] could not be parsed. */
  data class UnreadableClass(val className: String, val path: List<String>, val detail: String) :
      UnsafeReason

  /** The analysis stopped early because it exceeded its time or size budget. */
  data class LimitExceeded(val detail: String) : UnsafeReason
}

enum class Verdict {
  SAFE,
  UNSAFE,
}

data class PipelineSafety(
    val className: String,
    val metadata: PipelineMetadata,
    val reasons: List<UnsafeReason>,
) {
  val pipelineName: String
    get() = metadata.name

  val verdict: Verdict
    get() = if (reasons.isEmpty()) Verdict.SAFE else Verdict.UNSAFE
}

data class SafetyReport(
    val allowListVersion: String,
    val pipelines: List<PipelineSafety>,
    val limitations: String = LIMITATIONS,
    /** Classes of the jar's own copy of the core package; the Runner's core is always used. */
    val bundledCoreClasses: List<String> = listOf(),
    /** Pipelines whose declaration could not be read; they are not in [pipelines]. */
    val metadataProblems: List<MetadataProblem> = listOf(),
    /** Class files that cannot be parsed. Such a class can never be found as a pipeline. */
    val unreadableClasses: List<String> = listOf(),
) {
  companion object {
    const val LIMITATIONS =
        "靜態分析僅檢查編譯後的類別參照，不涵蓋反射與動態載入；" +
            "此限度同樣適用於 JVM 結束呼叫的判定，也適用於 IO 敏感成員的判定。" +
            "白名單內的類別不被檢查，其內部依賴視為受信任，白名單內類別的內部呼叫不被涵蓋。" +
            "IO 敏感成員的判定只涵蓋已列出的 IO 敏感成員（啟動外部行程、載入原生程式碼，" +
            "以及位於基礎套件內、可直接開啟檔案或網路的成員），且不受套件白名單豁免。" +
            "以子類別作為呼叫接收者時，類別參照中的擁有者是子類別，不會被判；" +
            "此限度與 JVM 結束成員相同。" +
            "會偵測的形式：直接呼叫與建構、方法句柄與方法參照（含 Kotlin 的可呼叫參照）、" +
            "以及 Kotlin 編譯產生的呼叫（內嵌、委派、lambda）。"
  }
}

/** Time and size budget; a run of the analysis that exceeds either is judged unsafe. */
data class AnalysisLimits(
    val maxClasses: Int = 5_000,
    val maxDuration: java.time.Duration = java.time.Duration.ofSeconds(30),
)

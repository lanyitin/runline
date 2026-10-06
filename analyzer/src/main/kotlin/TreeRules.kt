package dev.lawlan.runline.analyzer

internal enum class NodeKind {
  /** Core types, or classes on the allow list: trusted, never expanded. */
  IGNORED,

  /** The pipeline's own classes: allowed, and expanded. */
  OWN,

  /** Not allow listed: a violation, expanded when the class is in the jar. */
  VIOLATION,
}

internal object TreeRules {
  private const val CORE_PACKAGE = "dev.lawlan.runline.core"

  fun classify(className: String, pipelineClass: String, allowList: AllowList): NodeKind {
    val pkg = packageOf(className)
    return when {
      isIn(pkg, CORE_PACKAGE) -> NodeKind.IGNORED
      isOwn(pkg, packageOf(pipelineClass)) -> NodeKind.OWN
      allowList.entries.any { it.coversClass(className) } -> NodeKind.IGNORED
      else -> NodeKind.VIOLATION
    }
  }

  private fun packageOf(className: String) = className.substringBeforeLast('.', "")

  private fun isIn(pkg: String, root: String) = pkg == root || pkg.startsWith("$root.")

  /** A pipeline's own classes: its package and sub-packages (the default package: itself only). */
  private fun isOwn(pkg: String, pipelinePackage: String) =
      if (pipelinePackage.isEmpty()) pkg.isEmpty() else isIn(pkg, pipelinePackage)
}

/**
 * Members that end the whole JVM (ADR-011). Matched on the member itself, so a class's package
 * being allow listed does not excuse a reference to them.
 */
internal object JvmExitMembers {
  private val members =
      setOf(
          "java.lang.System.exit",
          "java.lang.Runtime.exit",
          "java.lang.Runtime.halt",
          "kotlin.system.ProcessKt.exitProcess",
      )

  fun contains(member: MemberReference) = member.toString() in members
}

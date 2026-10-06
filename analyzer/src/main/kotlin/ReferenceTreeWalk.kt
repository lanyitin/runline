package dev.lawlan.runline.analyzer

/** One pipeline's expansion of the class reference tree (ADR-002, ADR-011). */
internal class ReferenceTreeWalk(
    private val jar: PipelineJar,
    private val allowList: AllowList,
    private val limits: AnalysisLimits,
    private val start: String,
) {
  /**
   * Breadth-first expansion of the class reference tree from [start]. Reasons come out in discovery
   * order, so results are reproducible and each reported path is a shortest one.
   */
  fun run(): List<UnsafeReason> {
    val reasons = mutableListOf<UnsafeReason>()
    val parents = mutableMapOf<String, String?>(start to null)
    val queue = ArrayDeque(listOf(start))

    fun pathTo(className: String): List<String> =
        generateSequence(className) { parents[it] }.toList().reversed()

    val started = System.nanoTime()
    var expanded = 0
    while (queue.isNotEmpty()) {
      if (expanded >= limits.maxClasses) {
        reasons += UnsafeReason.LimitExceeded("more than ${limits.maxClasses} classes to analyze")
        break
      }
      if (System.nanoTime() - started >= limits.maxDuration.toNanos()) {
        reasons += UnsafeReason.LimitExceeded("analysis exceeded ${limits.maxDuration}")
        break
      }
      val current = queue.removeFirst()
      expanded++
      val references =
          try {
            jar.parse(current).references()
          } catch (e: RuntimeException) {
            // IllegalArgumentException and friends: bytes the JDK 25 class file reader rejects.
            reasons +=
                UnsafeReason.UnreadableClass(
                    current,
                    pathTo(current),
                    e.message ?: e::class.java.name,
                )
            continue
          }
      references.members.filter(JvmExitMembers::contains).forEach {
        reasons += UnsafeReason.JvmExit(it.toString(), pathTo(current))
      }
      references.members.filter(IoSensitiveMembers::contains).forEach {
        reasons += UnsafeReason.IoSensitiveMember(it.signature, pathTo(current))
      }
      for (referenced in references.classes) {
        if (referenced in parents) continue
        parents[referenced] = current
        when (TreeRules.classify(referenced, start, allowList)) {
          NodeKind.IGNORED -> continue
          NodeKind.OWN -> Unit
          NodeKind.VIOLATION ->
              reasons += UnsafeReason.NotAllowListed(referenced, pathTo(referenced))
        }
        if (jar.contains(referenced)) queue.addLast(referenced)
      }
    }
    return reasons
  }
}

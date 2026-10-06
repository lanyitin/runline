package dev.lawlan.runline.analyzer

import java.nio.file.Path

/**
 * Judges pipelines in a jar safe or unsafe by statically walking compiled class references
 * (ADR-002, ADR-011), and reads each pipeline's declared metadata in the same pass. Never loads or
 * runs pipeline code. No dependency on Ktor or the Engine.
 */
class SafetyAnalyzer(private val limits: AnalysisLimits = AnalysisLimits()) {
  /** Throws an `IOException` when [jar] is not a readable archive. */
  fun analyze(jar: Path, allowList: AllowList): SafetyReport =
      PipelineJar(jar).use { pipelineJar ->
        val pipelines = mutableListOf<PipelineSafety>()
        val problems = mutableListOf<MetadataProblem>()
        for (className in pipelineJar.classNames) {
          val definition =
              runCatching { pipelineJar.parse(className).pipelineDefinition() }.getOrNull()
                  ?: continue
          val metadata =
              try {
                definition.toMetadata()
              } catch (e: RuntimeException) {
                problems +=
                    MetadataProblem(className, "unexpected annotation content (${e.message})")
                continue
              }
          pipelines +=
              PipelineSafety(
                  className,
                  metadata,
                  unrestrictedAccess(metadata) +
                      ReferenceTreeWalk(pipelineJar, allowList, limits, className).run(),
              )
        }
        SafetyReport(
            allowList.version,
            pipelines,
            bundledCoreClasses = pipelineJar.bundledCoreClasses,
            metadataProblems = problems,
            unreadableClasses = pipelineJar.unreadableClasses(),
        )
      }

  private fun unrestrictedAccess(metadata: PipelineMetadata) =
      listOf(IoCategory.NETWORK to metadata.network, IoCategory.PROCESSES to metadata.processes)
          .filter { (_, limit) -> limit.unrestricted }
          .map { (category, _) -> UnsafeReason.UnrestrictedAccess(category) }
}

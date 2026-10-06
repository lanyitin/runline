package dev.lawlan.runline.core

enum class IoCategory {
  FILE,
  NETWORK,
  PROCESS,
}

/** Thrown when a pipeline attempts IO outside what its metadata declares. */
class PipelineAccessDenied(
    val pipeline: String,
    val category: IoCategory,
    val target: String,
    val reason: String,
) : RuntimeException("Pipeline '$pipeline': $category access to '$target' denied: $reason")

/** Thrown when a write would push a file scope beyond its configured disk usage limit. */
class FileQuotaExceeded(
    val pipeline: String,
    val scope: FileScope,
    val target: String,
    val limitBytes: Long,
) :
    RuntimeException(
        "Pipeline '$pipeline': write to '$target' rejected: $scope would exceed its limit of $limitBytes bytes"
    ) {
  val category: IoCategory = IoCategory.FILE
}

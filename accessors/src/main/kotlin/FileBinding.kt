package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceTypes

/** The operations of the `file` type on a [FileEntity]. */
class FileBinding(private val entity: FileEntity) : ResourceBinding {
  override val type: String = ResourceTypes.FILE

  override fun execute(operation: String, arguments: Map<String, Any?>): Any? =
      when (operation) {
        "file.read" -> entity.readBytes().decodeToString()
        "file.write" -> {
          entity.writeBytes((arguments["text"] as String).encodeToByteArray())
          null // an answer holds JDK types only, which Unit is not
        }
        else -> error("unknown operation $operation")
      }
}

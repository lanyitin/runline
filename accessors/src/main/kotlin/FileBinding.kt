package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceTypes

/** The operations of the `file` type on a [FileEntity]. */
class FileBinding(private val entity: FileEntity) : ResourceBinding {
  override val type: String = ResourceTypes.FILE

  override fun execute(operation: String, arguments: Map<String, Any?>): Any? =
      when (operation) {
        "file.read" -> entity.readBytes().decodeToString()
        "file.readBytes" -> entity.readBytes()
        "file.write" ->
            done { entity.writeBytes((arguments["text"] as String).encodeToByteArray()) }
        "file.writeBytes" -> done { entity.writeBytes(arguments["bytes"] as ByteArray) }
        "file.append" ->
            done { entity.appendBytes((arguments["text"] as String).encodeToByteArray()) }
        "file.appendBytes" -> done { entity.appendBytes(arguments["bytes"] as ByteArray) }
        else -> error("unknown operation $operation")
      }

  /** An answer holds JDK types only, which Unit is not. */
  private fun done(action: () -> Unit): Any? {
    action()
    return null
  }
}

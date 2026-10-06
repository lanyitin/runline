package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.UnsafeReason

/** The analyzer's reason as stored and shown by the API. */
fun UnsafeReason.toDoc(): ReasonDoc =
    when (this) {
      is UnsafeReason.UnrestrictedAccess ->
          ReasonDoc(ReasonKind.UNRESTRICTED_ACCESS, category = category.name)
      is UnsafeReason.NotAllowListed ->
          ReasonDoc(ReasonKind.NOT_ALLOW_LISTED, className = className, path = path)
      is UnsafeReason.JvmExit -> ReasonDoc(ReasonKind.JVM_EXIT, member = member, path = path)
      is UnsafeReason.IoSensitiveMember ->
          ReasonDoc(ReasonKind.IO_SENSITIVE_MEMBER, member = member, path = path)
      is UnsafeReason.UnreadableClass ->
          ReasonDoc(
              ReasonKind.UNREADABLE_CLASS,
              className = className,
              path = path,
              detail = detail,
          )
      is UnsafeReason.LimitExceeded -> ReasonDoc(ReasonKind.LIMIT_EXCEEDED, detail = detail)
    }

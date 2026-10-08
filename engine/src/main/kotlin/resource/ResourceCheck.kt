package dev.lawlan.runline.engine.resource

import java.time.Instant

/** Why a check of a resource's entity failed; [wire] is its name in the API. */
enum class CheckFailure(val wire: String) {
  /** The root of the resource files is missing or cannot be used. */
  ROOT_UNAVAILABLE("root_unavailable"),

  /** The directory of the file does not exist and cannot be made. */
  PARENT_NOT_CREATABLE("parent_not_creatable"),

  /** The file is there but cannot be both read and written. */
  NOT_READABLE_WRITABLE("not_readable_writable"),

  /** The path leads out of the resource root. */
  PATH_OUTSIDE_ROOT("path_outside_root"),

  /** The service could not be reached. */
  CONNECTION_FAILED("connection_failed"),

  /** The service refused the credentials. */
  REJECTED("rejected"),

  /** The service answered with a failure of its own (5xx). */
  SERVER_ERROR("server_error"),

  /**
   * The service answered, but not with what a check looks for, or redirected out of its address.
   */
  UNEXPECTED_RESPONSE("unexpected_response"),
  REDIRECT_BLOCKED("redirect_blocked"),

  /** The resource names a key that the keystore does not have (or has no keystore). */
  ALIAS_MISSING("alias_missing"),

  /** The resource names a key that the keystore has but that cannot be used. */
  ALIAS_INVALID("alias_invalid"),

  /** An alias names an entry of another kind than its member wants (WI-52). */
  ALIAS_WRONG_TYPE("alias_wrong_type"),

  /** A certificate the resource uses, or the service's, is past its end of validity (WI-52). */
  CERTIFICATE_EXPIRED("certificate_expired"),

  /** The service's certificate does not lead to one the resource trusts (WI-52). */
  TRUST_FAILED("trust_failed"),

  /** The service's certificate is not for the host the resource connects to (WI-52). */
  HOSTNAME_MISMATCH("hostname_mismatch"),

  /** The service asked for a client certificate and refused the connection (WI-52). */
  CLIENT_CERT_REJECTED("client_cert_rejected"),

  /** The handshake failed for another reason of TLS (WI-52). */
  HANDSHAKE_FAILED("handshake_failed"),

  /** The check did not finish within the limit. */
  TIMEOUT("timeout"),

  /** The check itself failed for a reason not listed; the details are in the Engine's log. */
  ERROR("error");

  companion object {
    fun fromWire(wire: String): CheckFailure = entries.first { it.wire == wire }
  }
}

/** The last check of a resource: whether it passed, why not, and when. No detail beyond that. */
data class CheckResult(val ok: Boolean, val failure: CheckFailure?, val checkedAt: Instant) {
  init {
    require(ok == (failure == null)) { "a check passed or has a failure, not both" }
  }
}

/**
 * A certificate a resource uses, as a check reports it (WI-52): the alias of the entry, its
 * subject, the end of its validity, the whole days left until then (negative once it has passed)
 * and its SHA-256 fingerprint. Never anything of a key.
 */
data class CertificateReport(
    val alias: String,
    val subject: String,
    val notAfter: Instant,
    val daysLeft: Long,
    val fingerprint: String,
)

/** A certificate with fewer days left than the Engine's warning threshold (WI-52). */
data class CertificateWarning(val alias: String, val daysLeft: Long) {
  val warning = "certificate_expiring"
}

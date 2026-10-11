package dev.lawlan.runline.engine.oidc

/**
 * Where a person is sent after signing in (ADR-029): only a path of this site is accepted, so a
 * crafted link cannot make the Engine redirect to another place. Anything else becomes the front
 * page; it is ignored, never repaired.
 */
object ReturnPath {
  private const val FRONT_PAGE = "/"
  private const val MAX_LENGTH = 2048

  fun sanitize(raw: String?): String = if (raw != null && isLocalPath(raw)) raw else FRONT_PAGE

  private fun isLocalPath(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_LENGTH || value[0] != '/') return false
    // "//host" is a link to another host, and browsers read "/\host" the same way.
    if (value.length > 1 && (value[1] == '/' || value[1] == '\\')) return false
    // Control characters and spaces have no place in a path, and a line break would split a header.
    return value.none { it <= ' ' || it == '\u007f' }
  }
}

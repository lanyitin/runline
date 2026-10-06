package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.AllowList

/**
 * Where the allow list used for verdicts comes from. The upload flow only asks for [current]; the
 * Engine provides the administrator-managed list kept in the database (WI-10).
 */
fun interface AllowListProvider {
  fun current(): AllowList
}

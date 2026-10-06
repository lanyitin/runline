package dev.lawlan.runline.engine.console

/** What the Console answers to a `GET` of a path. */
sealed interface ConsoleAnswer {
  /** A file, or the entry page standing in for a path of the single page application. */
  data class Content(val asset: ConsoleAsset, val cacheControl: String) : ConsoleAnswer

  data object NotFound : ConsoleAnswer
}

/**
 * Which file of the Console a path gets (WI-31, ADR-015):
 * - everything under `/api` and `/openapi` is never the Console's: it belongs to the API, whose
 *   unknown paths are 404;
 * - a path of a file of the Console gets that file;
 * - any other path that does not look like a file (no extension) gets the entry page, which is
 *   where the frontend's router takes over;
 * - what looks like a file but is none is 404, as is everything when there is no built Console.
 *
 * Files whose names carry a hash (everything under `assets/`) never change under their name; the
 * entry page and the other files must be asked again at every use, so a new release is seen at
 * once.
 */
class ConsoleContent(private val assets: ConsoleAssets) {
  fun answer(segments: List<String>): ConsoleAnswer {
    if (segments.firstOrNull() in API_ROOTS) return ConsoleAnswer.NotFound
    val path = segments.joinToString("/").ifEmpty { ENTRY_PAGE }
    assets.find(path)?.let {
      return ConsoleAnswer.Content(
          it,
          if (path.startsWith(HASHED_PREFIX)) IMMUTABLE else REVALIDATE,
      )
    }
    if (segments.lastOrNull().orEmpty().contains('.')) return ConsoleAnswer.NotFound
    val entry = assets.find(ENTRY_PAGE) ?: return ConsoleAnswer.NotFound
    return ConsoleAnswer.Content(entry, REVALIDATE)
  }

  private companion object {
    val API_ROOTS = setOf("api", "openapi")
    const val ENTRY_PAGE = "index.html"
    const val HASHED_PREFIX = "assets/"
    const val IMMUTABLE = "public, max-age=31536000, immutable"
    const val REVALIDATE = "no-cache"
  }
}

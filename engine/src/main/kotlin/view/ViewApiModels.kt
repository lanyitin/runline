package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.artifact.ViewColumnDoc
import dev.lawlan.runline.engine.artifact.ViewParameterDoc
import dev.lawlan.runline.engine.artifact.ViewSortDoc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A view as the API tells it: what it shows and takes, and whether it can be read. The text of its
 * query is never part of it (ADR-033).
 */
@Serializable
data class ViewDefinitionDoc(
    val id: String,
    val title: String,
    val description: String,
    /** The resource role the view is mounted on. */
    val resource: String,
    /**
     * `ready`, or why the role cannot be used: `unbound`, `unknown`, `disabled`, `type_mismatch`.
     */
    val status: String,
    val parameters: List<ViewParameterDoc>,
    val columns: List<ViewColumnDoc>,
    val sort: List<ViewSortDoc>,
)

@Serializable
data class ViewListResponse(
    val contentHash: String,
    val uploader: String,
    val pipeline: String,
    val views: List<ViewDefinitionDoc>,
)

fun ViewListResult.Found.toResponse() =
    ViewListResponse(
        definition.contentHash,
        definition.uploadedBy,
        definition.name,
        views.map { (view, resource) ->
          ViewDefinitionDoc(
              view.id,
              view.title,
              view.description,
              view.resource,
              when (resource) {
                is ViewResource.Usable -> "ready"
                is ViewResource.Unusable -> resource.kind.wire
              },
              view.parameters,
              view.columns,
              view.sort,
          )
        },
    )

@Serializable
data class ViewPageResponse(
    val view: String,
    val columns: List<ViewColumnDoc>,
    /** One array per row, a value for each of [columns] in their order. */
    val rows: List<List<JsonElement>>,
    /** Whether another page follows; the total is never counted. */
    val hasMore: Boolean,
    val limit: Int,
    val offset: Long,
)

fun ViewReadResult.Page.toResponse() =
    ViewPageResponse(
        view.id,
        view.columns,
        rows.map { row -> row.map(::cell) },
        hasMore,
        limit,
        offset,
    )

/**
 * A value of a cell as JSON. What the database gives passes as it is; bytes are not shown (a cell
 * is text for people), and a number JSON cannot hold is written as text.
 */
private fun cell(value: Any?): JsonElement =
    when (value) {
      null -> JsonNull
      is Boolean -> JsonPrimitive(value)
      is Long -> JsonPrimitive(value)
      is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
      is java.math.BigDecimal -> JsonPrimitive(value)
      is ByteArray -> JsonPrimitive("(binary, ${value.size} bytes)")
      else -> JsonPrimitive(value.toString())
    }

/** A parameter of the request that is refused: its name and why, never the value. */
@Serializable
data class InvalidViewParameterResponse(
    val error: String,
    val message: String,
    val parameter: String,
    val reason: String,
)

/** The view's role cannot be used; [problem] is the word a run is refused with. */
@Serializable
data class ViewResourceUnavailableResponse(
    val error: String,
    val message: String,
    val problem: String,
    val role: String,
)

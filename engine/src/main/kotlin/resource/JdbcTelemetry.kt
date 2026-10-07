package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.jdbc.JdbcObserver
import dev.lawlan.runline.accessors.jdbc.JdbcPools
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.core.ResourceTypes
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

/**
 * What the Engine records of `jdbc-pool` resources (ADR-019 decision 10, WI-48): the connections in
 * use, the connections that could not be had, and how long statements took. Every label is the
 * resource's name and its type, or the kind of operation or of failure: a fixed set, never a
 * statement, a parameter or an address. Nothing of what is sent or answered is recorded.
 */
class JdbcTelemetry(openTelemetry: OpenTelemetry, private val pools: JdbcPools) : JdbcObserver {
  private val meter = openTelemetry.getMeter("runline.resources.jdbc")
  private val acquireFailures =
      meter
          .counterBuilder("runline.resources.jdbc.acquire.failures")
          .setDescription("Statements that could not get a connection, by category")
          .build()
  private val duration =
      meter
          .histogramBuilder("runline.resources.jdbc.statement.duration")
          .setUnit("s")
          .setDescription("Time of a statement or transaction operation, by operation and outcome")
          .build()

  init {
    meter
        .gaugeBuilder("runline.resources.jdbc.connections.active")
        .ofLongs()
        .setDescription("Connections of a resource that runs are using")
        .buildWithCallback { measurement ->
          pools.snapshot().forEach { (resource, active) ->
            measurement.record(active.toLong(), attributes(resource))
          }
        }
  }

  override fun acquireFailed(resource: String, failure: ResourceFailure) {
    acquireFailures.add(1, attributes(resource, KIND to failure.name.lowercase()))
  }

  override fun finished(
      resource: String,
      operation: String,
      millis: Long,
      failure: ResourceFailure?,
  ) {
    duration.record(
        millis / 1000.0,
        attributes(
            resource,
            OPERATION to operation,
            OUTCOME to (failure?.name?.lowercase() ?: "ok"),
        ),
    )
  }

  private fun attributes(
      resource: String,
      vararg more: Pair<AttributeKey<String>, String>,
  ): Attributes {
    val builder = Attributes.builder().put(RESOURCE, resource).put(TYPE, ResourceTypes.JDBC_POOL)
    more.forEach { (key, value) -> builder.put(key, value) }
    return builder.build()
  }

  private companion object {
    val RESOURCE: AttributeKey<String> = AttributeKey.stringKey("resource")
    val TYPE: AttributeKey<String> = AttributeKey.stringKey("type")
    val OPERATION: AttributeKey<String> = AttributeKey.stringKey("operation")
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    val KIND: AttributeKey<String> = AttributeKey.stringKey("kind")
  }
}

package dev.lawlan.runline.accessors.jdbc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Why the settings of a `jdbc-pool` resource are refused; [wire] names it in the API. */
enum class JdbcSettingsProblem(val wire: String) {
  /** A member that is missing, is not a setting, or has the wrong shape. */
  INVALID_SETTINGS("invalid_settings"),

  /** The kind of database is not one this Engine carries a profile for. */
  UNSUPPORTED_DATABASE("unsupported_database"),

  /** An extra connection property that is not in the profile's list of allowed ones. */
  PROPERTY_NOT_ALLOWED("property_not_allowed"),
  INVALID_TIMEOUT("invalid_timeout"),

  /** Connections per run, or a limit on rows or size. */
  INVALID_LIMIT("invalid_limit"),
}

sealed interface JdbcSettingsResult {
  /** [normalized] has every effective value written out; it is what is stored. */
  class Valid(val settings: JdbcSettings, val normalized: JsonObject) : JdbcSettingsResult

  class Invalid(val problem: JdbcSettingsProblem) : JdbcSettingsResult
}

/**
 * The settings an administrator fixes for a `jdbc-pool` resource (ADR-019): which database kind,
 * where it is, which account, how many connections a run may use, and the limits on time and size.
 * The password is not among them; a resource names it by alias. There is no connection string: the
 * profile of the kind makes the address of the fields.
 */
data class JdbcSettings(
    val kind: String,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    /** How many connections one run may use at once. */
    val connectionsPerRun: Int,
    val connectTimeoutMillis: Long,
    /** The longest one statement may run. */
    val statementTimeoutMillis: Long,
    /** The longest a statement waits for the run's own share of connections. */
    val quotaWaitMillis: Long,
    val maxRows: Int,
    /** The most one answer may hold, counting text and bytes. */
    val maxResponseBytes: Long,
    val properties: Map<String, String>,
) {
  companion object {
    private val MEMBERS =
        setOf(
            "kind",
            "host",
            "port",
            "database",
            "username",
            "connectionsPerRun",
            "timeouts",
            "maxRows",
            "maxResponseBytes",
            "properties",
        )
    private val TIMEOUT_MEMBERS = setOf("connectMs", "statementMs", "quotaWaitMs")

    const val DEFAULT_CONNECT_MS = 10_000L
    const val DEFAULT_STATEMENT_MS = 5L * 60 * 1000
    const val DEFAULT_QUOTA_WAIT_MS = 60_000L
    const val DEFAULT_MAX_ROWS = 10_000
    const val DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    private const val LONGEST_TIMEOUT_MS = 24L * 3600 * 1000
    private const val MOST_CONNECTIONS_PER_RUN = 64L
    private const val MOST_ROWS = 1_000_000L
    private const val MOST_RESPONSE_BYTES = 256L * 1024 * 1024
    private const val LONGEST_NAME = 128
    private const val MOST_PROPERTIES = 16

    /** Checks [settings] against the profile of their kind and writes every effective value out. */
    fun parse(settings: JsonObject, profiles: JdbcProfiles): JdbcSettingsResult {
      if (!MEMBERS.containsAll(settings.keys)) return invalid(JdbcSettingsProblem.INVALID_SETTINGS)
      val kind = text(settings["kind"]) ?: return invalid(JdbcSettingsProblem.INVALID_SETTINGS)
      val profile = profiles.find(kind) ?: return invalid(JdbcSettingsProblem.UNSUPPORTED_DATABASE)
      val host = text(settings["host"]) ?: return invalid(JdbcSettingsProblem.INVALID_SETTINGS)
      val database =
          text(settings["database"]) ?: return invalid(JdbcSettingsProblem.INVALID_SETTINGS)
      val username =
          text(settings["username"])?.takeIf { isPlainName(it) }
              ?: return invalid(JdbcSettingsProblem.INVALID_SETTINGS)
      if (!profile.acceptsAddress(host, database)) {
        return invalid(JdbcSettingsProblem.INVALID_SETTINGS)
      }
      val port =
          boundedLong(settings["port"], 1, 65535, profile.defaultPort.toLong())
              ?: return invalid(JdbcSettingsProblem.INVALID_SETTINGS)
      val timeouts =
          timeoutsOf(settings["timeouts"]) ?: return invalid(JdbcSettingsProblem.INVALID_TIMEOUT)
      val perRun =
          boundedLong(settings["connectionsPerRun"], 1, MOST_CONNECTIONS_PER_RUN, 1)
              ?: return invalid(JdbcSettingsProblem.INVALID_LIMIT)
      val maxRows =
          boundedLong(settings["maxRows"], 1, MOST_ROWS, DEFAULT_MAX_ROWS.toLong())
              ?: return invalid(JdbcSettingsProblem.INVALID_LIMIT)
      val maxBytes =
          boundedLong(
              settings["maxResponseBytes"],
              1,
              MOST_RESPONSE_BYTES,
              DEFAULT_MAX_RESPONSE_BYTES,
          ) ?: return invalid(JdbcSettingsProblem.INVALID_LIMIT)
      val properties =
          when (val result = propertiesOf(settings["properties"], profile)) {
            is Properties.Bad -> return invalid(result.problem)
            is Properties.Good -> result.values
          }
      val parsed =
          JdbcSettings(
              kind,
              host,
              port.toInt(),
              database,
              username,
              perRun.toInt(),
              timeouts[0],
              timeouts[1],
              timeouts[2],
              maxRows.toInt(),
              maxBytes,
              properties,
          )
      return JdbcSettingsResult.Valid(parsed, parsed.normalized())
    }

    private fun invalid(problem: JdbcSettingsProblem) = JdbcSettingsResult.Invalid(problem)

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /** A name of an account: printable, without a control character, not longer than it need be. */
    private fun isPlainName(name: String) =
        name.length <= LONGEST_NAME && name.none { it.isISOControl() }

    /** [element] as a whole number in [min]..[max], [default] when there is none; null if wrong. */
    private fun boundedLong(element: JsonElement?, min: Long, max: Long, default: Long): Long? {
      if (element == null) return default
      val primitive = element as? JsonPrimitive ?: return null
      if (primitive.isString) return null
      return primitive.longOrNull?.takeIf { it in min..max }
    }

    /** The connect, statement and quota-wait limits, in that order. */
    private fun timeoutsOf(element: JsonElement?): List<Long>? {
      if (element == null)
          return listOf(DEFAULT_CONNECT_MS, DEFAULT_STATEMENT_MS, DEFAULT_QUOTA_WAIT_MS)
      val timeouts = element as? JsonObject ?: return null
      if (!TIMEOUT_MEMBERS.containsAll(timeouts.keys)) return null
      return listOf(
          boundedLong(timeouts["connectMs"], 1, LONGEST_TIMEOUT_MS, DEFAULT_CONNECT_MS)
              ?: return null,
          boundedLong(timeouts["statementMs"], 1, LONGEST_TIMEOUT_MS, DEFAULT_STATEMENT_MS)
              ?: return null,
          boundedLong(timeouts["quotaWaitMs"], 1, LONGEST_TIMEOUT_MS, DEFAULT_QUOTA_WAIT_MS)
              ?: return null,
      )
    }

    private sealed interface Properties {
      class Good(val values: Map<String, String>) : Properties

      class Bad(val problem: JdbcSettingsProblem) : Properties
    }

    private fun propertiesOf(element: JsonElement?, profile: JdbcProfile): Properties {
      if (element == null) return Properties.Good(emptyMap())
      val given =
          element as? JsonObject ?: return Properties.Bad(JdbcSettingsProblem.INVALID_SETTINGS)
      if (given.size > MOST_PROPERTIES) return Properties.Bad(JdbcSettingsProblem.INVALID_SETTINGS)
      // A name that is not allowed is the first thing said, whatever its value is.
      if (given.keys.any { it !in profile.allowedProperties }) {
        return Properties.Bad(JdbcSettingsProblem.PROPERTY_NOT_ALLOWED)
      }
      val values = LinkedHashMap<String, String>()
      for ((name, value) in given) {
        val text =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return Properties.Bad(JdbcSettingsProblem.INVALID_SETTINGS)
        if (!profile.allowedProperties.getValue(name).accepts(text)) {
          return Properties.Bad(JdbcSettingsProblem.INVALID_SETTINGS)
        }
        values[name] = text
      }
      return Properties.Good(values)
    }
  }

  /** Every effective value, in a fixed order, so that equal settings are written equally. */
  private fun normalized(): JsonObject {
    val result = LinkedHashMap<String, JsonElement>()
    result["kind"] = JsonPrimitive(kind)
    result["host"] = JsonPrimitive(host)
    result["port"] = JsonPrimitive(port)
    result["database"] = JsonPrimitive(database)
    result["username"] = JsonPrimitive(username)
    result["connectionsPerRun"] = JsonPrimitive(connectionsPerRun)
    result["timeouts"] =
        JsonObject(
            linkedMapOf(
                "connectMs" to JsonPrimitive(connectTimeoutMillis),
                "statementMs" to JsonPrimitive(statementTimeoutMillis),
                "quotaWaitMs" to JsonPrimitive(quotaWaitMillis),
            )
        )
    result["maxRows"] = JsonPrimitive(maxRows)
    result["maxResponseBytes"] = JsonPrimitive(maxResponseBytes)
    if (properties.isNotEmpty()) {
      result["properties"] = JsonObject(properties.mapValues { JsonPrimitive(it.value) })
    }
    return JsonObject(result)
  }
}

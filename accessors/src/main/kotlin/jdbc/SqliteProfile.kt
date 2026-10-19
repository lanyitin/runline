package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.Confinement
import dev.lawlan.runline.accessors.FileProblem
import dev.lawlan.runline.accessors.ReadOnlyFailure
import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Properties
import kotlinx.serialization.json.JsonObject
import org.sqlite.BusyHandler
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteException
import org.sqlite.SQLiteLimits
import org.sqlite.SQLiteOpenMode

/**
 * SQLite, the second database (WI-1003, ADR-024): one file below the resource root, opened by the
 * Engine. There is no account, no password and no network, so none of the settings of those apply,
 * and what the file does is the profile's, not the administrator's: the log is the one that lets
 * readers go on while a writer writes, foreign keys are enforced, and a writer that finds the file
 * locked waits as long as a statement may run.
 */
class SqliteProfile(private val root: Path) : JdbcProfile {
  override val kind = "sqlite"
  override val locationMembers = listOf("path")
  override val networked = false

  /** Nothing of a file's behavior is the administrator's to set. */
  override val allowedProperties: Map<String, PropertyRule> = emptyMap()
  /**
   * Taking the write lock is what shows the file can be written (a read-only file refuses it), and
   * it is let go when the check's connection is closed.
   */
  override val healthQuery = "BEGIN IMMEDIATE"

  /**
   * What a run sets with a pragma or in a temporary table must not outlast it, and no statement
   * clears all of that; opening a file costs next to nothing, so a connection is closed when its
   * run is done with it.
   */
  override val resetStatements: List<String> = emptyList()
  override val keepsConnections = false

  /**
   * Write-ahead is the mode in which readers do not block the writer and the writer does not block
   * readers; it is set when the connection is made ([connect]), where a file that is being
   * converted by another connection is waited for. The wait for a lock is not a pragma but
   * [PatientWriter], installed there too.
   */
  override fun startStatements(settings: JdbcSettings): List<String> =
      listOf("PRAGMA foreign_keys = ON")

  override val interruptsStatements = true

  override val values: JdbcValues = SqliteValues

  /**
   * A path of at most eight plain names below the root, each starting with a letter, a digit or an
   * underscore: nothing that leads out of it, is hidden, or could say more than a file (a query
   * string, a URI, a space) in the address the driver is given.
   */
  override fun locationOf(settings: JsonObject): JdbcLocationResult {
    val path =
        SettingsValues.text(settings["path"]) ?: return JdbcLocationResult.Rejected.INVALID_SETTINGS
    val leavesRoot =
        path.startsWith("/") ||
            runCatching { Path.of(path).normalize().startsWith("..") }.getOrDefault(false)
    if (leavesRoot) return JdbcLocationResult.Rejected(JdbcSettingsProblem.PATH_OUTSIDE_ROOT)
    if (!PATH.matches(path)) return JdbcLocationResult.Rejected.INVALID_SETTINGS
    return JdbcLocationResult.Found(JdbcLocation.LocalFile(path))
  }

  /**
   * The address of the file as it is on the file system now, links resolved, and only if that is
   * below the real root. The directory of the file is not made.
   */
  override fun url(location: JdbcLocation): String {
    val file =
        try {
          Confinement.locate(root, (location as JdbcLocation.LocalFile).path, createParents = false)
        } catch (e: ResourceOperationFailure) {
          throw SQLException("the path leads out of the resource root", STATE_REFUSED, e)
        } catch (e: IOException) {
          throw SQLException("the resource root cannot be used", STATE_UNREACHABLE, e)
        }
    // A `?` would begin the driver's own options, which are the profile's and nobody else's.
    if ('?' in file.toString()) {
      throw SQLException("the path cannot be an address", STATE_UNREACHABLE)
    }
    return "jdbc:sqlite:$file"
  }

  /**
   * Whether the file can be used, by what the file system says: the root is there and usable, the
   * file is below it, the directory it is in is there (and takes the files SQLite keeps beside it),
   * and the file, if it exists, is a file that can be read and written.
   */
  override fun inspect(location: JdbcLocation): FileProblem? {
    if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
      return FileProblem.ROOT_UNAVAILABLE
    }
    val file =
        try {
          Confinement.locate(root, (location as JdbcLocation.LocalFile).path, createParents = false)
        } catch (e: ResourceOperationFailure) {
          return FileProblem.PATH_OUTSIDE_ROOT
        }
    val directory = file.parent
    if (
        !Files.isDirectory(directory) ||
            !Files.isWritable(directory) ||
            !Files.isExecutable(directory)
    ) {
      return FileProblem.PARENT_NOT_CREATABLE
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
    val usable = Files.isRegularFile(file) && Files.isReadable(file) && Files.isWritable(file)
    return if (usable) null else FileProblem.NOT_READABLE_WRITABLE
  }

  override fun properties(
      location: JdbcLocation,
      password: String?,
      connectTimeoutMillis: Long,
      extra: Map<String, String>,
  ): Properties =
      // A transaction takes the write lock when it begins and so waits there, where a statement
      // can be waited for; one that began as a reader and found the file written since would be
      // refused at once, however long it was willing to wait.
      SQLiteConfig()
          .apply { transactionMode = SQLiteConfig.TransactionMode.IMMEDIATE }
          .toProperties()

  /** There is no connection to wrap in TLS; a profile that is a file never has certificates. */
  override fun tlsProperties(contextId: String): Map<String, String> = emptyMap()

  /**
   * The driver is asked directly, never through `DriverManager`, so only the Engine's is used. A
   * database file can `ATTACH` another file by any path, which would take a run out of the root, so
   * a connection allows no attached database.
   */
  override fun connect(url: String, properties: Properties): Connection {
    val connection =
        org.sqlite.JDBC().connect(url, properties) as SQLiteConnection?
            ?: throw SQLException("the driver does not take the address", STATE_UNREACHABLE)
    try {
      BusyHandler.setHandler(connection, PatientWriter())
      connection.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0)
      enterWriteAheadLog(connection)
    } catch (e: Throwable) {
      runCatching { connection.close() }
      throw e
    }
    return connection
  }

  /**
   * The first connections to a file that is not there yet all find it in the default journal mode
   * and ask for the conversion at once; SQLite answers the ones that lose with `SQLITE_BUSY`
   * without consulting the busy handler (the conversion needs the file to itself, and waiting
   * inside it could deadlock), however long that handler would wait. So the loser asks again, as
   * the handler would have it wait, until the winner is done; when the file is in the log already
   * the statement changes nothing. Any other failure, a file that cannot be opened among them, is
   * not retried.
   */
  private fun enterWriteAheadLog(connection: Connection) {
    var tries = 0
    var waitedMillis = 0L
    while (true) {
      try {
        connection.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
        return
      } catch (e: SQLException) {
        val busy = (e as? SQLiteException)?.resultCode?.code?.and(0xff) == BUSY
        if (
            !busy || Thread.currentThread().isInterrupted || waitedMillis >= FIRST_OPEN_WAIT_MILLIS
        ) {
          throw e
        }
        val pause = minOf(LONGEST_PAUSE_MILLIS, 1L + tries++)
        try {
          Thread.sleep(pause)
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw e
        }
        waitedMillis += pause
      }
    }
  }

  override val reading: ReadDialect = SqliteReading

  override fun classify(e: SQLException): Classified {
    val code = (e as? SQLiteException)?.resultCode?.code
    val primary = code?.and(0xff)
    return when {
      // The file cannot be opened, or is not a database.
      primary == CANTOPEN || primary == NOTADB ->
          Classified(ResourceFailure.CONNECTION_FAILED, STATE_UNREACHABLE)
      primary == CONSTRAINT -> Classified(ResourceFailure.SQL_ERROR, constraintState(code))
      // Another connection holds the lock for longer than this one would wait.
      primary == BUSY || primary == LOCKED ->
          Classified(ResourceFailure.SQL_ERROR, STATE_LOCK_CONFLICT)
      e.sqlState == STATE_REFUSED -> Classified(ResourceFailure.DENIED, STATE_REFUSED)
      e.sqlState == STATE_UNREACHABLE ->
          Classified(ResourceFailure.CONNECTION_FAILED, STATE_UNREACHABLE)
      else -> Classified(ResourceFailure.SQL_ERROR, null)
    }
  }

  private fun constraintState(code: Int?) =
      when (code) {
        UNIQUE,
        PRIMARY_KEY -> "23505"
        FOREIGN_KEY -> "23503"
        NOT_NULL -> "23502"
        CHECK -> "23514"
        else -> "23000"
      }

  /**
   * What a connection does when the file is locked by another: it waits, a little longer after each
   * try, for as long as the statement that waits may run (the statement's own limit ends it; the
   * longest limit there is stands behind that), and gives up at once when the thread that waits is
   * interrupted. SQLite's own wait for a lock cannot be stopped, and a run that is cancelled or
   * released by force must not wait on.
   */
  private class PatientWriter : BusyHandler() {
    private var waitedMillis = 0L

    override fun callback(previousTries: Int): Int {
      if (previousTries == 0) waitedMillis = 0
      if (Thread.currentThread().isInterrupted || waitedMillis >= LONGEST_WAIT_MILLIS) return 0
      val pause = minOf(LONGEST_PAUSE_MILLIS, 1L + previousTries)
      try {
        Thread.sleep(pause)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return 0
      }
      waitedMillis += pause
      return 1
    }
  }

  private companion object {
    val PATH = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}(?:/[A-Za-z0-9_][A-Za-z0-9._-]{0,127}){0,7}")
    const val LONGEST_WAIT_MILLIS = 24L * 3600 * 1000
    const val LONGEST_PAUSE_MILLIS = 20L
    const val FIRST_OPEN_WAIT_MILLIS = 30L * 1000
    const val STATE_REFUSED = "28000"
    const val STATE_UNREACHABLE = "08001"
    const val STATE_LOCK_CONFLICT = "40001"

    // The primary and extended result codes of SQLite (sqlite3.h).
    const val BUSY = 5
    const val LOCKED = 6
    const val CANTOPEN = 14
    const val CONSTRAINT = 19
    const val NOTADB = 26
    const val CHECK = 275
    const val FOREIGN_KEY = 787
    const val NOT_NULL = 1299
    const val PRIMARY_KEY = 1555
    const val UNIQUE = 2067
  }
}

/**
 * How values pass between Java and SQLite. SQLite keeps what it is given in five kinds, whatever
 * the column is declared as, and gives them back as they are: an integer is a Long, a real a
 * Double, text a String, a blob bytes. A boolean is the integer 1 or 0, and a date or a time is the
 * text or the number it was stored as.
 */
internal object SqliteValues : JdbcValues {
  override fun bind(statement: PreparedStatement, index: Int, value: Any?) {
    when (value) {
      null -> statement.setNull(index, Types.NULL)
      is String -> statement.setString(index, value)
      is Boolean -> statement.setBoolean(index, value)
      is Long -> statement.setLong(index, value)
      is Double -> statement.setDouble(index, value)
      is BigDecimal -> statement.setBigDecimal(index, value)
      is ByteArray -> statement.setBytes(index, value)
      else -> throw IllegalArgumentException("not a value a run can give")
    }
  }

  override fun read(rows: ResultSet, index: Int): Any? =
      when (val value = rows.getObject(index)) {
        null -> null
        is String,
        is ByteArray -> value
        is Number -> if (value is Double || value is Float) value.toDouble() else value.toLong()
        else -> rows.getString(index)
      }
}

/**
 * Reading a SQLite file for a view (WI-1301). The file is opened the way the runs open it, not with
 * the driver's read-only mode: that mode cannot make the `-wal` and `-shm` files of the log when no
 * run has the file open, and fails
 * (https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/runbook.md
 * section 8). What keeps the read from writing is the text (one statement inside a `SELECT`) and
 * `query_only`.
 */
internal object SqliteReading : ReadDialect {
  override val syntax = SqlSyntax.SQLITE

  override fun quote(name: String) = "`" + name.replace("`", "``") + "`"

  override fun connect(url: String, properties: Properties): Connection {
    val file = java.nio.file.Path.of(url.removePrefix("jdbc:sqlite:"))
    // Opening a file that is not there would make an empty database in its place.
    if (!Files.isRegularFile(file)) throw SQLException("no database file", NO_DATABASE)
    val config = SQLiteConfig().apply { resetOpenMode(SQLiteOpenMode.CREATE) }
    val connection =
        org.sqlite.JDBC().connect(url, config.toProperties()) as SQLiteConnection?
            ?: throw SQLException("the driver does not take the address", "08001")
    try {
      connection.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0)
    } catch (e: Throwable) {
      runCatching { connection.close() }
      throw e
    }
    return connection
  }

  /** With `query_only` the connection refuses every change of the file, whatever it is given. */
  override val startStatements: List<String> = listOf("PRAGMA query_only = ON")

  override fun begin(connection: Connection) {}

  override fun classify(e: SQLException): ReadOnlyFailure =
      when {
        e.sqlState == NO_DATABASE -> ReadOnlyFailure.SCHEMA_MISMATCH
        e !is SQLiteException -> ReadOnlyFailure.FAILED
        e.resultCode.code.and(0xff) == ERROR && NOT_THERE.containsMatchIn(e.message.orEmpty()) ->
            ReadOnlyFailure.SCHEMA_MISMATCH
        // Text the database cannot read as a statement of a read, or a change it refuses.
        e.resultCode.code.and(0xff) in listOf(ERROR, READONLY, AUTH) ->
            ReadOnlyFailure.QUERY_REJECTED
        e.resultCode.code.and(0xff) in listOf(CANTOPEN, NOTADB) -> ReadOnlyFailure.CONNECTION_FAILED
        else -> ReadOnlyFailure.FAILED
      }

  /** What the driver says when a table, a view or a column is not there. */
  private val NOT_THERE = Regex("no such (table|column|view)")

  const val CANTOPEN = 14
  const val NOTADB = 26

  const val ERROR = 1
  const val READONLY = 8
  const val AUTH = 23
  const val NO_DATABASE = "3D000"
}

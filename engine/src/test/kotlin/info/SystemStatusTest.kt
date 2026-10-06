package dev.lawlan.runline.engine.info

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.support.AllowListRig
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class SystemStatusTest {
  private val build =
      BuildInfo("2.3.4", "b".repeat(40), true, Instant.parse("2026-01-02T03:04:05Z"))
  private val rig = AllowListRig(emptyList())
  private val started = rig.clock.instant()
  private val status = SystemStatus(build, rig.clock, rig.store, jdk = "25.0.1")
  private val caller = ApiIdentity("alice", Role.DEVELOPER)

  @Test
  fun `the report carries the build, the JDK, the caller and when the Engine started`() {
    val report = status.report(caller)

    assertEquals("2.3.4", report.version)
    assertEquals("b".repeat(40), report.commitHash)
    assertTrue(report.dirty)
    assertEquals("2026-01-02T03:04:05Z", report.buildTime)
    assertEquals("25.0.1", report.jdk)
    assertEquals(started.toString(), report.startedAt)
    assertEquals(CallerResponse("alice", "developer"), report.caller)
  }

  @Test
  fun `an administrator is reported with the admin role`() {
    assertEquals("admin", status.report(ApiIdentity("root", Role.ADMIN)).caller.role)
  }

  @Test
  fun `the uptime is the time since the Engine started, in whole seconds`() {
    assertEquals(0, status.report(caller).uptimeSeconds)

    rig.clock.advance(Duration.ofMillis(90_500))

    assertEquals(90, status.report(caller).uptimeSeconds)
    assertEquals(started.toString(), status.report(caller).startedAt)
  }

  @Test
  fun `the allow list version is the one in force now`() {
    val before = status.report(caller).allowListVersion
    assertEquals(rig.store.current()!!.number.toString(), before)

    rig.add("com.example")

    val after = status.report(caller).allowListVersion
    assertEquals(rig.store.current()!!.number.toString(), after)
    assertNotEquals(before, after)
  }
}

package dev.lawlan.runline.accessors.fake

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * An address on which a connection is never made: packets to it (TEST-NET-1, reserved for
 * documentation, so nothing answers) are not answered at all, which is what a connect timeout is
 * for. It depends on the network of the machine the test runs on, so it is looked at first: where
 * the network answers at once (no route, no network at all) the test that needs it is skipped, and
 * is reported as skipped, never as passed. A listening socket with a full queue was tried first,
 * and rejected: macOS resets the connections instead of dropping them.
 */
object BlackHole {
  const val HOST = "192.0.2.1"

  /** The base address of an API on the black hole; skips the test if there is none here. */
  fun baseUrl(): String {
    val hangs =
        Socket().use {
          try {
            it.connect(InetSocketAddress(HOST, 80), 400)
            false
          } catch (e: SocketTimeoutException) {
            true
          } catch (e: java.io.IOException) {
            false
          }
        }
    assumeTrue(hangs, "this network answers $HOST at once, so a connect cannot be made to hang")
    return "http://$HOST/v1"
  }
}
